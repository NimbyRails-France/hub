"""Exercise the real Windows installer on the VPS, never on a developer PC.

This gates publishing after packaging. Check bytes, not just exit codes: stale
libraries must vanish, missing directories must be recreated, and the external
profile must survive. Wine qualification does not replace physical GPU testing.
"""
import hashlib
import os
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path.cwd()
C = pathlib.Path(os.environ.get('WINEPREFIX', str(pathlib.Path.home() / '.wine'))) / 'drive_c'
TARGET = C / 'nrf-installer-test/NRFHub'
BACKUP = TARGET.with_name(TARGET.name + '.nrf-rollback')
STAGE = C / 'nrf-packaging/hub/image/NRFHub'
VERSION = (ROOT / 'VERSION').read_text().strip()
INSTALLER = ROOT / f'dist/release/NRFHub-{VERSION}-windows-x64-Setup.exe'
REPORTS = ROOT / 'build/installer-tests'
REPORTS.mkdir(parents=True, exist_ok=True)


def win(path):
    path = pathlib.Path(path).resolve()
    if path.is_relative_to(C.resolve()):
        return 'C:\\' + str(path.relative_to(C.resolve())).replace('/', '\\')
    return 'Z:' + str(path).replace('/', '\\')


def wine(*args, check=True):
    return subprocess.run(['wine', *map(str, args)], check=check, timeout=180,
                          env=dict(os.environ, WINEDEBUG='-all'), capture_output=True)


def put(path, data=b'old payload'):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


# Resolve the same per-user location that {localappdata} uses in Inno.
local = wine('cmd', '/c', 'echo %LOCALAPPDATA%').stdout.decode().strip()
assert local.lower().startswith('c:\\'), local
DATA = C / local[3:].replace('\\', '/') / 'NimbyRailsFrance'
sentinels = {DATA / 'NRFHub/settings.json': b'{"channels":{"hub":"alpha"}}',
             DATA / 'NRFHub/projects/custom/preserve.bin': b'local project',
             DATA / 'logs/hub/preserve.log': b'previous diagnostic'}
for path, data in sentinels.items():
    put(path, data)
expected = {str(p.relative_to(STAGE)): digest(p) for p in STAGE.rglob('*') if p.is_file()}


def assert_profile():
    for path, content in sentinels.items():
        assert path.read_bytes() == content, f'User data changed: {path}'


def install(name, target=TARGET, installer=INSTALLER, success=True):
    result = wine(win(installer), '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART',
                  '/DIR=' + win(target), '/LOG=' + win(REPORTS / (name + '.log')), check=False)
    if success:
        assert result.returncode == 0, (name, result.returncode, result.stderr[-1000:])
        actual = {str(p.relative_to(target)): digest(p) for p in target.rglob('*')
                  if p.is_file() and not (p.parent == target and
                  (p.name.startswith('unins') or p.name == '.nrfhub-install.ini'))}
        assert actual == expected, (name, 'Installed payload differs from the fresh image',
                                    actual.keys() - expected.keys(), expected.keys() - actual.keys())
        assert not BACKUP.exists(), 'Successful replacement left a rollback directory'
    else:
        assert result.returncode != 0, (name, 'Unsafe/incomplete install accepted')
    assert_profile()
    print('PASS installer:', name, flush=True)


install('fresh')
# A root DLL can hijack dependency resolution even if Qt itself is unused.
for entry in ['D3Dcompiler_47.dll', 'Qt6Core.dll', 'unknown-obsolete.dll',
              'app/obsolete.jar', 'runtime/bin/obsolete.dll', 'platforms/qwindows.dll']:
    put(TARGET / entry)
install('legacy-and-unknown-files')
install('same-version')

# A separately compiled, unpublished installer exercises the failure handler
# after old files have actually moved. No fault switch enters the release EXE.
fault = REPORTS / 'fault'
fault.mkdir()
wine('/opt/inno/ISCC.exe', '/Qp', '/DStage=' + win(STAGE), '/DOutput=' + win(fault),
     '/DVersion=' + VERSION, '/DNativeVersion=' + VERSION.split('-')[0],
     '/DReplacementFailureTest=1', win(ROOT / 'tools/windows/installer.iss'))
before = {str(p.relative_to(TARGET)): digest(p) for p in TARGET.rglob('*') if p.is_file()}
install('failure-restores-previous', installer=fault / INSTALLER.name, success=False)
after = {str(p.relative_to(TARGET)): digest(p) for p in TARGET.rglob('*') if p.is_file()}
assert before == after, 'Failed replacement did not restore the previous installation'
assert not BACKUP.exists()

shutil.rmtree(TARGET / 'runtime')
(TARGET / 'NRFHub.exe').unlink()
install('missing-runtime-and-launcher')
shutil.rmtree(TARGET)
install('deleted-program-directory')

# Simulate a process/power interruption after a complete snapshot and after
# only some old files moved. The next invocation must recover before replacing.
for phase in ['snapshot', 'ready']:
    payload = BACKUP / 'payload'
    payload.mkdir(parents=True)
    shutil.move(str(TARGET / 'app'), str(payload / 'app'))
    if phase == 'ready':
        for p in list(TARGET.iterdir()):
            if not p.name.startswith('unins'):
                shutil.move(str(p), str(payload / p.name))
        put(TARGET / 'app/incomplete.jar')
    (BACKUP / 'transaction.ini').write_text(
        '[transaction]\ntarget=' + win(TARGET) + '\nphase=' + phase + '\n')
    install('interrupted-' + phase)

# Never replace an unrelated folder even when /DIR points at it.
foreign = TARGET.parent / 'unrelated'
put(foreign / 'keep.txt', b'unrelated data')
install('unrelated-directory-refused', target=foreign, success=False)
assert (foreign / 'keep.txt').read_bytes() == b'unrelated data'

# The installed Windows runtime must also load the actual application after
# migration. No game connection or user profile is opened by this switch.
wine(win(TARGET / 'NRFHub.exe'), '--package-smoke-test')
print('PASS: migrated Windows launcher and external profile preservation', flush=True)
