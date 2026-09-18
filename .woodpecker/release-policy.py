"""Shared release policy. Copied into each repository; no network or secrets."""
import datetime
import json
import os
import pathlib
import re

VERSION = r'(?:0|[1-9][0-9]{0,3})\.(?:0|[1-9][0-9]{0,3})\.(?:0|[1-9][0-9]{0,3})(?:-(?:alpha|beta|dev)\.[1-9][0-9]{0,8})?'

def requested_version(message):
    subject = message.splitlines()[0].strip() if message else ''
    match = re.fullmatch(r'(?:release|relase):?\s+v?(' + VERSION + ')', subject, re.I)
    if match:
        return match.group(1)
    if re.match(r'^(?:release|relase)\b', subject, re.I):
        raise ValueError('Release subject must be: release X.Y.Z (or X.Y.Z-alpha.N / beta.N / dev.N)')
    return None

def notes_for(changelog, version, dated=False):
    section = re.search(r'^## \[' + re.escape(version) + r'\](?: - ([^\n]+))?\n(.*?)(?=^## \[|\Z)', changelog, re.M | re.S)
    if not section or len(section.group(2).strip()) < 15:
        raise ValueError('Add customer-facing release notes to CHANGELOG.md for ' + version)
    if dated:
        datetime.date.fromisoformat((section.group(1) or '').strip())
    return section.group(2).strip()

def plan(root, message, branch, event):
    root = pathlib.Path(root)
    version = (root / 'VERSION').read_text(encoding='utf-8').strip()
    if not re.fullmatch(VERSION, version):
        raise ValueError('Invalid VERSION')
    policy = json.loads((root / 'release-channels.json').read_text(encoding='utf-8'))
    channel = version.split('-')[1].split('.')[0] if '-' in version else 'stable'
    if channel not in policy['channels']:
        raise ValueError('Channel is not enabled for this project')
    requested = requested_version(message) if event in ('push', 'manual') else None
    if requested:
        if requested != version:
            raise ValueError('Release commit version must match VERSION: ' + version)
        if policy['branches'].get(branch) != channel:
            raise ValueError('Release channel does not match branch: ' + branch)
    notes = notes_for((root / 'CHANGELOG.md').read_text(encoding='utf-8'), version, bool(requested))
    return dict(publish=bool(requested), version=version, channel=channel, notes=notes,
                branch=branch, commit=os.environ.get('CI_COMMIT_SHA', ''), assets=[])

if __name__ == '__main__':
    result = plan('.', os.environ.get('CI_COMMIT_MESSAGE', ''), os.environ.get('CI_COMMIT_BRANCH', ''), os.environ.get('CI_PIPELINE_EVENT', ''))
    pathlib.Path('.release-plan.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(('Release requested: ' if result['publish'] else 'Checks only; no publication: ') + result['version'])
