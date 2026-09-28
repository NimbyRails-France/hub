"""Publish CI-verified assets, then a public catalogue usable without API tokens.

Copied into consumer repositories so a release does not depend on uncommitted
shared tooling. Never import this into a desktop application. NRF_RELEASE_TOKEN
is a Woodpecker secret, used only for exact api.github.com/uploads.github.com URLs.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

PROJECTS = {'sdk', 'hub', 'tco', 'signalisationfrancaiserealiste', 'signal-placement', 'time-change'}
VERSION = re.compile(r'(?:0|[1-9][0-9]{0,3})\.(?:0|[1-9][0-9]{0,3})\.(?:0|[1-9][0-9]{0,3})(?:-(?:alpha|beta)\.[1-9][0-9]{0,8})?')
NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,180}')


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


class ApiError(RuntimeError):
    def __init__(self, status):
        self.status = status
        super().__init__('GitHub API HTTP ' + str(status))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class GitHub:
    def __init__(self, repository, token):
        if repository not in {'NimbyRails-France/' + name for name in PROJECTS}:
            raise ValueError('Unsupported repository')
        if not token:
            raise ValueError('Configure the Woodpecker nrf_release_token secret')
        self.repository, self.token = repository, token
        self.opener = urllib.request.build_opener(NoRedirect)

    def request(self, method, path, data=None, asset=None, name=None):
        # No URL from a manifest/release is trusted with the publisher credential.
        base = ('https://uploads.github.com' if asset is not None else 'https://api.github.com')
        if not path.startswith('/releases') or '..' in path:
            raise ValueError('Unexpected API path')
        url = base + '/repos/' + self.repository + path
        if asset is not None:
            url += '?name=' + urllib.parse.quote(name, safe='')
        headers = {'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
                   'User-Agent': 'NRF-Woodpecker-Publisher', 'X-GitHub-Api-Version': '2026-03-10'}
        stream = None
        try:
            if asset is not None:
                stream = Path(asset).open('rb')
                payload = stream
                headers.update({'Content-Type': 'application/octet-stream', 'Content-Length': str(Path(asset).stat().st_size)})
            else:
                payload = json.dumps(data).encode('utf-8') if data is not None else None
                if payload is not None:
                    headers['Content-Type'] = 'application/json'
            request = urllib.request.Request(url, data=payload, headers=headers, method=method)
            with self.opener.open(request, timeout=180) as response:
                body = response.read(8 * 1024 * 1024 + 1)
                if len(body) > 8 * 1024 * 1024:
                    raise ValueError('GitHub response too large')
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            error.close()
            # Avoid dumping HTTP objects/headers, which contain credentials.
            raise ApiError(error.code) from None
        finally:
            if stream is not None:
                stream.close()


def find_release(api, tag):
    try:
        return api.request('GET', '/releases/tags/' + tag)
    except ApiError as error:
        if error.status != 404:
            raise
    # The tag endpoint can hide drafts. Authenticated list calls include them,
    # so interrupted uploads resume the same draft instead of creating another.
    for page in range(1, 11):
        batch = api.request('GET', f'/releases?per_page=100&page={page}')
        matches = [release for release in batch if release['tag_name'] == tag]
        if len(matches) > 1:
            raise ValueError('Ambiguous release drafts for ' + tag)
        if matches:
            return matches[0]
        if len(batch) < 100:
            return None
    raise ValueError('Release history exceeds the lookup limit')


def assets_for(api, release):
    result = []
    for page in range(1, 11):
        batch = api.request('GET', f"/releases/{int(release['id'])}/assets?per_page=100&page={page}")
        result.extend(batch)
        if len(batch) < 100:
            return result
    raise ValueError('Too many release assets')


def validate_inputs(plan, source, repository, commit):
    if repository not in {'NimbyRails-France/' + name for name in PROJECTS}:
        raise ValueError('Unsupported repository')
    if not VERSION.fullmatch(plan['version']) or not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Invalid release version or CI commit')
    if plan.get('commit') != commit:
        raise ValueError('Release plan belongs to another commit')
    channel = plan['version'].split('-')[1].split('.')[0] if '-' in plan['version'] else 'stable'
    if channel != plan['channel'] or not plan['notes'].strip() or not plan['assets']:
        raise ValueError('Incomplete release plan')
    if len(plan['assets']) > 100:
        raise ValueError('Too many artifacts')
    names = set()
    for item in plan['assets']:
        name = item['name']
        if not NAME.fullmatch(name) or name in names or name in ('.', '..'):
            raise ValueError('Invalid or duplicate artifact name')
        if any(part in name.lower() for part in ('linux', 'macos', '.dmg', '.deb', '.rpm', '.tar.gz')):
            raise ValueError('Only Windows artifacts may be published')
        names.add(name)
        path = source / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size != item['size'] or digest(path) != item['sha256']:
            raise ValueError('CI artifact changed: ' + name)
    return names


def verify_asset(actual, expected):
    if (actual.get('state') != 'uploaded' or actual.get('size') != expected['size'] or
            actual.get('digest', '').lower() != 'sha256:' + expected['sha256'].lower()):
        raise ValueError('Published asset differs from CI input: ' + expected['name'])


def public_catalogue(api):
    releases = []
    repository = api.repository
    for page in range(1, 11):
        batch = api.request('GET', f'/releases?per_page=100&page={page}')
        for release in batch:
            version = release['tag_name'].removeprefix('v')
            if (not release['tag_name'].startswith('v') or not VERSION.fullmatch(version) or
                    release['draft'] or not release.get('published_at') or release['prerelease'] != ('-' in version)):
                continue
            prefix = f"https://github.com/{repository}/releases/download/{release['tag_name']}/"
            assets = []
            for item in assets_for(api, release):
                if not NAME.fullmatch(item['name']) or item['state'] != 'uploaded' or item['browser_download_url'] != prefix + item['name']:
                    continue
                assets.append({key: item.get(key) for key in ('name', 'state', 'browser_download_url', 'size', 'digest')})
            releases.append(dict(tag_name=release['tag_name'], draft=False, prerelease=release['prerelease'],
                                 published_at=release['published_at'], body=release.get('body') or '', assets=assets))
        if len(batch) < 100:
            return dict(schema=1, project=repository.split('/')[1], releases=releases)
    raise ValueError('Release history exceeds the public catalogue limit')


def publish(plan, source, api, commit):
    source = Path(source)
    names = validate_inputs(plan, source, api.repository, commit)
    tag = 'v' + plan['version']
    release = find_release(api, tag)
    if release is None:
        release = api.request('POST', '/releases', dict(tag_name=tag, target_commitish=commit, name=tag,
            body=plan['notes'], draft=True, prerelease=plan['channel'] != 'stable'))
    if release.get('target_commitish') != commit or release['prerelease'] != (plan['channel'] != 'stable') or release.get('body') != plan['notes']:
        raise ValueError('Release already belongs to another build; increment the version')
    uploaded = assets_for(api, release)
    if any(item['name'] not in names for item in uploaded):
        raise ValueError('Release contains assets outside the verified plan')
    for expected in plan['assets']:
        existing = [a for a in uploaded if a['name'] == expected['name']]
        if len(existing) > 1:
            raise ValueError('Duplicate published asset')
        if existing:
            verify_asset(existing[0], expected)
        elif release['draft']:
            result = api.request('POST', f"/releases/{int(release['id'])}/assets", asset=source / expected['name'], name=expected['name'])
            verify_asset(result, expected)
        else:
            raise ValueError('Published release is incomplete; do not modify it')
    if release['draft']:
        api.request('PATCH', f"/releases/{int(release['id'])}", dict(draft=False, make_latest='true' if plan['channel'] == 'stable' else 'false'))
    # Per-repository CI concurrency=1 prevents competing catalogue replacements.
    # A failed catalogue upload is retryable without replacing any release binary.
    catalog = source.parent / 'github-releases.json'
    catalog.write_text(json.dumps(public_catalogue(api), ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
    if catalog.stat().st_size > 4 * 1024 * 1024:
        raise ValueError('Public catalogue exceeds Hub limit')
    index = find_release(api, 'catalogue')
    if index is None:
        index = api.request('POST', '/releases', dict(tag_name='catalogue', target_commitish=commit,
            name='Hub release catalogue', body='Public release index for NRF Hub. No application binaries in this entry.', draft=True, prerelease=True))
    for item in assets_for(api, index):
        if item['name'] == 'releases.json':
            api.request('DELETE', f"/releases/assets/{int(item['id'])}")
    result = api.request('POST', f"/releases/{int(index['id'])}/assets", asset=catalog, name='releases.json')
    verify_asset(result, dict(name='releases.json', size=catalog.stat().st_size, sha256=digest(catalog)))
    if index['draft']:
        api.request('PATCH', f"/releases/{int(index['id'])}", dict(draft=False, make_latest='false'))
    print('Published GitHub release and public Hub catalogue:', api.repository, tag)


if __name__ == '__main__':
    plan = json.loads(Path('.release-plan.json').read_text(encoding='utf-8'))
    if not plan['publish']:
        print('Checks only: GitHub publication skipped.')
    else:
        publish(plan, 'dist/release', GitHub(os.environ['CI_REPO'], os.environ.get('NRF_RELEASE_TOKEN', '')), os.environ['CI_COMMIT_SHA'])
