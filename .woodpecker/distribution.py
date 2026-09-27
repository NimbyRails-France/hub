"""Publish verified Windows artifacts to the NRF server without GitHub reads.

The files are immutable and become visible before the catalogue is atomically
replaced. A lock serializes concurrent project publications. The HTTP container
only mounts public/ read-only; staging, locks and provenance stay outside it.
"""
import datetime
import fcntl
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

ORIGIN = 'https://releases.nimbyrails-france.fr'
PROJECTS = {'hub', 'sdk', 'tco', 'signalisationfrancaiserealiste'}
VERSION = re.compile(r'\d+\.\d+\.\d+(?:-(?:alpha|beta)\.[1-9]\d*)?')
NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,180}')


def sha(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def write_home(public, index):
    sections = []
    for repo, releases in sorted(index['projects'].items()):
        links = ''.join('<li><a href="/releases/' + html.escape(repo) + '/' + html.escape(r['tag_name']) + '/">' +
                        html.escape(r['tag_name']) + '</a></li>' for r in reversed(releases))
        sections.append('<h2>' + html.escape(repo) + '</h2><ul>' + links + '</ul>')
    temporary = public / 'index.tmp'
    temporary.write_text('<!doctype html><html lang="fr"><meta charset="utf-8">'
        '<title>NimbyRails France - Releases</title><h1>Publications Windows</h1>'
        '<p>Paquets officiels NimbyRails France. Choisissez votre projet et votre version.</p>' +
        ''.join(sections) + '</html>\n', encoding='utf-8')
    os.replace(temporary, public / 'index.html')


def publish(root, repo, version, source, assets, notes, published_at=None):
    root, source = Path(root), Path(source)
    assert repo in PROJECTS and VERSION.fullmatch(version)
    channel = version.split('-', 1)[1].split('.')[0] if '-' in version else 'stable'
    root.mkdir(parents=True, exist_ok=True)
    public = root / 'public'
    public.mkdir(exist_ok=True)
    (root / 'staging').mkdir(exist_ok=True)
    prefix = f'{ORIGIN}/releases/{repo}/v{version}/'
    with (root / '.publish.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        work = Path(tempfile.mkdtemp(prefix=repo + '-', dir=root / 'staging'))
        work.chmod(0o755)
        try:
            for asset in assets:
                name = asset['name']
                assert NAME.fullmatch(name) and name not in ('.', '..')
                path = source / name
                assert path.is_file() and not path.is_symlink()
                assert path.stat().st_size == asset['size']
                assert sha(path) == asset['sha256'], 'Input artifact checksum mismatch: ' + name
                # Existing Linux/macOS artifacts are not republished.
                if any(x in name.lower() for x in ('linux', 'macos', '.dmg', '.deb', '.rpm', '.tar.gz')):
                    continue
                shutil.copyfile(path, work / name)

            manifests = list(work.glob('project*.json')) + list(work.glob('*-latest*.json'))
            for manifest in manifests:
                data = json.loads(manifest.read_text(encoding='utf-8-sig'))
                if data.get('platform', 'windows-x64') != 'windows-x64':
                    manifest.unlink()
                    continue
                assert data['version'] == version
                name = data['url'].rsplit('/', 1)[-1]
                target = work / name
                assert NAME.fullmatch(name) and target.is_file()
                assert sha(target) == data['sha256'].lower() and target.stat().st_size == data['size']
                data.update(url=prefix + name, channel=channel, platform='windows-x64')
                write_json(manifest, data)
            canonical = ('hub-latest' if repo == 'hub' else 'project') + '-windows-x64.json'
            legacy = 'hub-latest.json' if repo == 'hub' else 'project.json'
            if not (work / canonical).exists() and (work / legacy).exists():
                shutil.copyfile(work / legacy, work / canonical)
            # Historical archives without a manifest remain downloadable, but
            # the Hub's selection cannot offer them as installable versions.
            # Manifest URLs change, so regenerate the digest file as well.
            sums = work / 'SHA256SUMS.txt'
            sums.write_text(''.join(sha(p) + '  ' + p.name + '\n' for p in sorted(work.iterdir())
                                   if p.is_file() and p.name != sums.name), encoding='utf-8')
            links = ''.join('<li><a href="' + html.escape(p.name, quote=True) + '">' + html.escape(p.name) + '</a></li>'
                            for p in sorted(work.iterdir()))
            (work / 'index.html').write_text('<!doctype html><html lang="fr"><meta charset="utf-8">'
                '<title>' + html.escape(repo + ' ' + version) + '</title><h1>' + html.escape(repo + ' ' + version) +
                '</h1><pre style="white-space:pre-wrap">' + html.escape(notes) + '</pre><ul>' + links + '</ul></html>', encoding='utf-8')
            target = public / 'releases' / repo / ('v' + version)
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                for p in work.iterdir():
                    assert (target / p.name).is_file() and sha(target / p.name) == sha(p), 'Published release is immutable: ' + str(target)
                assert {p.name for p in target.iterdir()} == {p.name for p in work.iterdir()}
            else:
                os.rename(work, target)
            catalogue = public / 'v1/catalog.json'
            catalogue.parent.mkdir(exist_ok=True)
            index = json.loads(catalogue.read_text()) if catalogue.exists() else {'schema': 1, 'projects': {}}
            assert index['schema'] == 1
            releases = index['projects'].setdefault(repo, [])
            prior = next((x for x in releases if x['tag_name'] == 'v' + version), None)
            timestamp = (prior or {}).get('published_at') or published_at or datetime.datetime.now(datetime.timezone.utc).isoformat()
            release = dict(tag_name='v' + version, draft=False, prerelease=channel != 'stable',
                           published_at=timestamp, body=notes,
                           assets=[dict(name=p.name, state='uploaded', browser_download_url=prefix + p.name,
                                        size=p.stat().st_size, digest='sha256:' + sha(p)) for p in sorted(target.iterdir())])
            releases[:] = [x for x in releases if x['tag_name'] != release['tag_name']] + [release]
            index['generatedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            temporary = catalogue.with_suffix('.tmp')
            with temporary.open('w', encoding='utf-8') as output:
                json.dump(index, output, ensure_ascii=False, indent=2)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, catalogue)
            write_home(public, index)
            print('Published on NRF server:', repo, version, flush=True)
        finally:
            if work.exists():
                shutil.rmtree(work)


if __name__ == '__main__':
    plan = json.loads(Path('.release-plan.json').read_text())
    if not plan['publish']:
        print('No release requested: server publication skipped.')
    else:
        repo = os.environ['CI_REPO'].split('/')[-1]
        assert plan['channel'] == (plan['version'].split('-')[1].split('.')[0] if '-' in plan['version'] else 'stable')
        publish('/distribution', repo, plan['version'], 'dist/release', plan['assets'], plan['notes'])
