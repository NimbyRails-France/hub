"""Offline publication tests: no network, token, tag or production directory."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import sys
import types
import os
import runpy
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import project_identities as identities

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('github-release.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class FakeApi:
    repository = 'NimbyRails-France/time-change'

    def __init__(self, repository=None):
        if repository is not None:
            self.repository = repository
        self.releases = []
        self.calls = []
        self.fail_catalogue = False

    def request(self, method, path, data=None, asset=None, name=None):
        self.calls.append((method, path, name))
        if path.startswith('/releases/tags/'):
            result = next((r for r in self.releases if r['tag_name'] == path.split('/')[-1]), None)
            if result is None or result['draft']:
                raise publisher.ApiError(404)
            return copy.deepcopy(result)
        if path.startswith('/releases?'):
            return copy.deepcopy(self.releases)
        if path == '/releases' and method == 'POST':
            result = dict(data, id=len(self.releases) + 1, assets=[], published_at=None)
            self.releases.append(result)
            return copy.deepcopy(result)
        if path.startswith('/releases/assets/'):
            for release in self.releases:
                release['assets'] = [a for a in release['assets'] if a['id'] != int(path.split('/')[-1])]
            return None
        release = next(r for r in self.releases if r['id'] == int(path.split('/')[2]))
        if method == 'GET':
            return copy.deepcopy(release['assets'])
        if method == 'PATCH':
            release.update(data)
            if not release['draft']:
                release['published_at'] = '2026-09-28T00:00:00Z'
            return copy.deepcopy(release)
        if self.fail_catalogue and name == 'releases.json':
            raise publisher.ApiError(503)
        result = dict(name=name, id=1000 * release['id'] + len(release['assets']), state='uploaded',
            size=Path(asset).stat().st_size, digest='sha256:' + publisher.digest(asset),
            browser_download_url=f"https://github.com/{self.repository}/releases/download/{release['tag_name']}/{name}")
        release['assets'].append(result)
        return copy.deepcopy(result)


class Publication(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / 'release'
        self.source.mkdir()
        (self.source / 'mod.zip').write_bytes(b'verified Windows fixture')
        self.commit = 'a' * 40
        self.plan = dict(version='0.1.0-alpha.1', channel='alpha', commit=self.commit,
            notes='Change the game date and time with train interventions.',
            assets=[dict(name='mod.zip', size=(self.source / 'mod.zip').stat().st_size,
                         sha256=publisher.digest(self.source / 'mod.zip'))])
        self.api = FakeApi()

    def tearDown(self):
        self.temp.cleanup()

    def test_release_is_complete_before_catalogue_becomes_public(self):
        publisher.publish(self.plan, self.source, self.api, self.commit)
        self.assertEqual(['v0.1.0-alpha.1', 'catalogue'], [r['tag_name'] for r in self.api.releases])
        self.assertTrue(all(not r['draft'] for r in self.api.releases))
        catalog = json.loads((self.root / 'github-releases.json').read_text())
        self.assertEqual('time-change', catalog['project'])
        self.assertEqual(1, len(catalog['releases']))
        self.assertEqual('sha256:' + self.plan['assets'][0]['sha256'], catalog['releases'][0]['assets'][0]['digest'])

    def test_retry_after_catalogue_failure_never_reuploads_a_binary(self):
        self.api.fail_catalogue = True
        with self.assertRaises(publisher.ApiError):
            publisher.publish(self.plan, self.source, self.api, self.commit)
        self.assertFalse(self.api.releases[0]['draft'])
        self.api.fail_catalogue = False
        publisher.publish(self.plan, self.source, self.api, self.commit)
        self.assertEqual(1, sum(name == 'mod.zip' for method, path, name in self.api.calls))

    def test_modified_input_or_existing_asset_is_never_overwritten(self):
        publisher.publish(self.plan, self.source, self.api, self.commit)
        self.api.releases[0]['assets'][0]['digest'] = 'sha256:' + 'b' * 64
        with self.assertRaises(ValueError):
            publisher.publish(self.plan, self.source, self.api, self.commit)
        (self.source / 'mod.zip').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            publisher.publish(self.plan, self.source, self.api, self.commit)
        self.assertEqual(1, sum(name == 'mod.zip' for method, path, name in self.api.calls))

    def test_invalid_commit_repository_and_traversal_fail_before_network(self):
        for change in [dict(commit='b' * 40), dict(version='../escape'), dict(assets=[dict(name='../mod.zip', size=0, sha256='')])]:
            with self.assertRaises(ValueError):
                publisher.publish(dict(self.plan, **change), self.source, self.api, self.commit)
        self.assertFalse(self.api.calls)
        with self.assertRaises(ValueError):
            publisher.GitHub('OtherOrg/time-change', 'never-transmitted')

    def test_renamed_repositories_keep_legacy_catalogue_ids_urls_and_titles(self):
        for project, current in identities.REPOSITORIES.items():
            for slug in {project, current}:
                with self.subTest(repository=slug):
                    api = FakeApi('NimbyRails-France/' + slug)
                    publisher.publish(self.plan, self.source, api, self.commit)
                    catalogue = json.loads((self.root / 'github-releases.json').read_text())
                    self.assertEqual(project, catalogue['project'])
                    self.assertEqual(publisher.PROJECT_NAMES[project] + ' ' + self.plan['version'], api.releases[0]['name'])
                    asset = catalogue['releases'][0]['assets'][0]
                    self.assertEqual(f'https://github.com/NimbyRails-France/{project}/releases/download/v0.1.0-alpha.1/mod.zip', asset['browser_download_url'])
                    self.assertEqual('sha256:' + self.plan['assets'][0]['sha256'], asset['digest'])
                    self.assertEqual('NimbyRails-France/' + slug, api.repository)

    def test_catalogue_accepts_both_exact_aliases_but_no_other_project_or_host(self):
        api = FakeApi('NimbyRails-France/bb-timechange')
        publisher.publish(self.plan, self.source, api, self.commit)
        asset = api.releases[0]['assets'][0]
        for slug in ('time-change', 'bb-timechange'):
            asset['browser_download_url'] = f'https://github.com/NimbyRails-France/{slug}/releases/download/v0.1.0-alpha.1/mod.zip'
            self.assertEqual(1, len(publisher.public_catalogue(api)['releases'][0]['assets']))
        for url in (
            'https://github.com/OtherOrg/bb-timechange/releases/download/v0.1.0-alpha.1/mod.zip',
            'https://github.com/NimbyRails-France/ba-signal-placement/releases/download/v0.1.0-alpha.1/mod.zip',
            'https://github.com.evil.example/NimbyRails-France/bb-timechange/releases/download/v0.1.0-alpha.1/mod.zip',
            'https://github.com/NimbyRails-France/bb-timechange/releases/download/v0.1.0-alpha.2/mod.zip',
            'https://github.com/NimbyRails-France/bb-timechange/releases/download/v0.1.0-alpha.1/mod.zip?redirect=elsewhere',
        ):
            asset['browser_download_url'] = url
            self.assertEqual([], publisher.public_catalogue(api)['releases'][0]['assets'])

    def test_repository_identity_rejects_unlisted_owner_alias_and_path(self):
        for repository in (
            'OtherOrg/bb-timechange', 'NimbyRails-France/unknown-mod',
            'bb-timechange', 'NimbyRails-France/bb-timechange/extra',
            'NimbyRails-France/../bb-timechange', 'NimbyRails-France/bb-timechange?x=1',
            'https://github.com/NimbyRails-France/bb-timechange', None,
        ):
            with self.subTest(repository=repository):
                with self.assertRaises(ValueError):
                    publisher.GitHub(repository, 'never-transmitted')
                api = FakeApi()
                api.repository = repository
                with self.assertRaises(ValueError):
                    publisher.publish(self.plan, self.source, api, self.commit)
                self.assertEqual([], api.calls)

    def test_authenticated_api_uses_exact_validated_ci_repository_without_redirects(self):
        api = publisher.GitHub('NimbyRails-France/bb-timechange', 'fixture-only')
        with mock.patch.object(api.opener, 'open') as opened:
            opened.return_value.__enter__.return_value.read.return_value = b'[]'
            self.assertEqual([], api.request('GET', '/releases?per_page=100&page=1'))
        request = opened.call_args.args[0]
        self.assertEqual('https://api.github.com/repos/NimbyRails-France/bb-timechange/releases?per_page=100&page=1', request.full_url)
        self.assertTrue(any(isinstance(handler, publisher.NoRedirect) for handler in api.opener.handlers))

    def test_distribution_boundary_maps_repository_to_stable_project_before_publish(self):
        distribution_spec = importlib.util.spec_from_file_location('distribution', Path(__file__).with_name('distribution.py'))
        distribution = importlib.util.module_from_spec(distribution_spec)
        # The production publisher runs on Linux. These boundary tests mock the
        # entire publishing operation, so Windows need not emulate file locks.
        with mock.patch.dict(sys.modules, {'fcntl': types.ModuleType('fcntl')}):
            distribution_spec.loader.exec_module(distribution)
        with mock.patch.object(distribution, 'publish') as publish:
            for project, current in identities.REPOSITORIES.items():
                for slug in {project, current}:
                    distribution.publish_plan('unused-root', 'NimbyRails-France/' + slug, self.plan, self.source)
                    self.assertEqual(project, publish.call_args.args[1])
            publish.reset_mock()
            with self.assertRaises(ValueError):
                distribution.publish_plan('unused-root', 'OtherOrg/bb-timechange', self.plan, self.source)
            with self.assertRaises(ValueError):
                distribution.publish_plan('unused-root', 'NimbyRails-France/bb-timechange', dict(self.plan, channel='stable'), self.source)
            publish.assert_not_called()


class ModPackaging(unittest.TestCase):
    def setUp(self):
        self.script = Path(__file__).with_name('package.py').resolve()
        if not self.script.is_file() or not self.script.parent.parent.joinpath('mod.json').is_file():
            self.skipTest('This repository does not use the mod packager')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def fixture(self, folder, project):
        root = self.root / folder
        source = root / 'build/gradle/distributions'
        source.mkdir(parents=True)
        archive = source / (project + '-0.1.0-alpha.1-windows-x64.zip')
        with zipfile.ZipFile(archive, 'w') as content:
            content.writestr('verified/mod.txt', b'fixture')
        manifest = dict(id=project, version='0.1.0-alpha.1', platform='windows-x64', channel='alpha',
            url='https://github.com/NimbyRails-France/' + project + '/releases/download/v0.1.0-alpha.1/' + archive.name,
            sha256=publisher.digest(archive), size=archive.stat().st_size)
        for name in ('project.json', 'project-windows-x64.json'):
            (source / name).write_text(json.dumps(manifest), encoding='utf-8')
        (root / '.release-plan.json').write_text(json.dumps(dict(version=manifest['version'], channel='alpha')), encoding='utf-8')
        return root, manifest

    def package(self, root, repository):
        previous = Path.cwd()
        try:
            os.chdir(root)
            with mock.patch.dict(os.environ, {'CI_REPO': repository}):
                runpy.run_path(str(self.script), run_name='__main__')
        finally:
            os.chdir(previous)

    def test_old_and_new_mod_repositories_keep_manifest_ids_and_distribution_urls(self):
        for project, current in identities.REPOSITORIES.items():
            if project in ('hub', 'sdk', 'tco'):
                continue
            for slug in {project, current}:
                with self.subTest(repository=slug):
                    root, original = self.fixture(slug, project)
                    self.package(root, 'NimbyRails-France/' + slug)
                    out = root / 'dist/release'
                    manifest = json.loads((out / 'project-windows-x64.json').read_text(encoding='utf-8'))
                    self.assertEqual(project, manifest['id'])
                    self.assertTrue(manifest['url'].startswith('https://releases.nimbyrails-france.fr/releases/' + project + '/v0.1.0-alpha.1/'))
                    self.assertEqual(original['sha256'], manifest['sha256'])
                    self.assertEqual(original['sha256'], publisher.digest(out / manifest['url'].rsplit('/', 1)[1]))

    def test_repository_owner_and_manifest_identity_must_match_before_output_creation(self):
        cases = [
            ('wrong-owner', 'time-change', 'OtherOrg/bb-timechange'),
            ('wrong-project', 'signal-placement', 'NimbyRails-France/bb-timechange'),
            ('unknown-repo', 'time-change', 'NimbyRails-France/unlisted-timechange'),
        ]
        for folder, project, repository in cases:
            root, _ = self.fixture(folder, project)
            with self.subTest(repository=repository):
                with self.assertRaises((ValueError, AssertionError)):
                    self.package(root, repository)
                self.assertFalse((root / 'dist').exists())


if __name__ == '__main__':
    unittest.main()
