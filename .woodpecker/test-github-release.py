"""Offline publication tests: no network, token, tag or production directory."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('github-release.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class FakeApi:
    repository = 'NimbyRails-France/time-change'

    def __init__(self):
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


if __name__ == '__main__':
    unittest.main()
