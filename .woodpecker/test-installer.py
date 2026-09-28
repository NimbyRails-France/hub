"""Exercise the real Windows installer in disposable CI, never on a developer PC.

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
    result = subprocess.run(['wine', *map(str, args)], check=False, timeout=180,
                            env=dict(os.environ, WINEDEBUG='-all'), capture_output=True)
    if check and result.returncode:
        # Keep the actual JVM/installer failure, not only Python's exit status.
        print(result.stdout.decode(errors='replace')[-14000:], flush=True)
        print(result.stderr.decode(errors='replace')[-14000:], flush=True)
        result.check_returncode()
    return result


def put(path, data=b'old payload'):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


# This worker's Wine prefix omits the 64-bit view queried by Inno, even though
# the directories exist. Provision only the disposable emulator registry.
# Inno source: Setup.MainFunc.pas, InitializeSystemDirs / GetPath(rv64Bit).
for name, value in [('ProgramFilesDir', r'C:\Program Files'),
                    ('CommonFilesDir', r'C:\Program Files\Common Files')]:
    wine('reg', 'add', r'HKLM\Software\Microsoft\Windows\CurrentVersion',
         '/v', name, '/t', 'REG_SZ', '/d', value, '/f', '/reg:64')


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
# Inno adds the shortcut/uninstaller icon separately from the JDK app image.
# Keep the byte-for-byte payload check exhaustive, including this extra file.
expected[str(pathlib.Path('app/NRFHub.ico'))] = digest(ROOT / 'src/desktopMain/resources/branding/hub.ico')


def assert_profile():
    for path, content in sentinels.items():
        assert path.read_bytes() == content, f'User data changed: {path}'


def install(name, target=TARGET, installer=INSTALLER, success=True):
    logs = DATA / 'logs/hub'
    previous_logs = set(logs.glob(f'installer-{VERSION}-*.log'))
    result = wine(win(installer), '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART',
                  '/DIR=' + win(target), '/LOG=' + win(REPORTS / (name + '.log')), check=False)
    if (result.returncode == 0) != success:
        log = REPORTS / (name + '.log')
        if log.exists():
            print(log.read_text(encoding='utf-8-sig', errors='replace')[-18000:], flush=True)
    if success:
        if result.returncode != 0:
            log = REPORTS / (name + '.log')
            if log.exists():
                print(log.read_text(encoding='utf-8-sig', errors='replace')[-10000:], flush=True)
        assert result.returncode == 0, (name, result.returncode, result.stderr[-1000:])
        actual = {str(p.relative_to(target)): digest(p) for p in target.rglob('*')
                  if p.is_file() and not (p.parent == target and
                  (p.name.startswith('unins') or p.name == '.nrfhub-install.ini'))}
        assert actual == expected, (name, 'Installed payload differs from the fresh image',
                                    actual.keys() - expected.keys(), expected.keys() - actual.keys())
        assert not BACKUP.exists(), 'Successful replacement left a rollback directory'
    else:
        assert result.returncode != 0, (name, 'Unsafe/incomplete install accepted')
    attempts = set(logs.glob(f'installer-{VERSION}-*.log')) - previous_logs
    assert len(attempts) == 1, (name, 'One persistent Inno log is required per attempt', attempts)
    attempt = attempts.pop()
    latest = logs / 'installer-latest.log'
    assert latest.read_bytes() == attempt.read_bytes(), (name, 'Latest log does not match this attempt')
    diagnostic = latest.read_text(encoding='utf-8-sig')
    assert f'Hub installer version={VERSION}' in diagnostic
    assert 'NRF diagnostics: Windows=' in diagnostic
    assert 'NRF diagnostics: original Inno log=' in diagnostic
    assert ('NRF diagnostics: outcome=committed' if success else 'NRF diagnostics: outcome=not committed') in diagnostic
    if success:
        assert 'NRF diagnostics: component=' in diagnostic and 'NRFHub.exe' in diagnostic
    if name == 'failure-restores-previous':
        assert 'Injected CI failure after snapshot' in diagnostic
        assert 'Previous program restored' in diagnostic
    if name == 'missing-component-restores-previous':
        assert 'NRF diagnostics: MISSING component=' in diagnostic and r'app\NRFHub.cfg' in diagnostic
        assert 'Previous program restored' in diagnostic
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

# A real incomplete payload must preserve the exact missing component in the
# persistent Inno log and restore the old installation, not report success.
missing_stage = REPORTS / 'missing-component-stage'
shutil.copytree(STAGE, missing_stage)
(missing_stage / 'app/NRFHub.cfg').unlink()
missing_output = REPORTS / 'missing-component-installer'
missing_output.mkdir()
wine('/opt/inno/ISCC.exe', '/Qp', '/DStage=' + win(missing_stage), '/DOutput=' + win(missing_output),
     '/DVersion=' + VERSION, '/DNativeVersion=' + VERSION.split('-')[0], win(ROOT / 'tools/windows/installer.iss'))
before = {str(p.relative_to(TARGET)): digest(p) for p in TARGET.rglob('*') if p.is_file()}
install('missing-component-restores-previous', installer=missing_output / INSTALLER.name, success=False)
after = {str(p.relative_to(TARGET)): digest(p) for p in TARGET.rglob('*') if p.is_file()}
assert before == after, 'Missing-component rollback did not restore the previous installation'
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
for channel in ['stable', 'alpha']:
    # Use the installed Windows JVM while retaining stdout for diagnostics.
    result = wine(win(TARGET / 'runtime/bin/java.exe'), '-cp', win(TARGET / 'app') + r'\*',
                  'fr.nimby.hub.MainKt', '--network-test', channel)
    print(result.stdout.decode(errors='replace'), flush=True)
print('PASS: migrated Windows launcher and external profile preservation', flush=True)
