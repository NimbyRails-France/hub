$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root=[IO.Path]::GetFullPath("$PSScriptRoot/../build/manager-test-"+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$root/game","$root/package/Fixture" -Force | Out-Null
[IO.File]::WriteAllText("$root/game/NIMBYRails.exe",'fixture game, not executable')
$gameHash=(Get-FileHash "$root/game/NIMBYRails.exe").Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$root/package/Fixture/NimbyTco.exe",'version one')
function Archive {
 $zip=Join-Path $root ([guid]::NewGuid().ToString('N')+'.zip')
 [IO.Compression.ZipFile]::CreateFromDirectory("$root/package",$zip)
 return $zip
}
$zip=Archive
$p=@{id='fixture';kind='tco';version='1.0.0';rootFolder='Fixture';sha256=(Get-FileHash $zip).Hash.ToLowerInvariant();size=(Get-Item $zip).Length;gameSha256=@($gameHash)}
$req=@{action='install';project=$p;archive=$zip;destination="$root/installed";gameDirectory="$root/game";expectedGameHash=$gameHash;resultFile="$root/result.json"}
function Run([bool]$success){
 $req | ConvertTo-Json -Depth 8 | Set-Content "$root/request.json" -Encoding UTF8
 $ErrorActionPreference='Continue'
 & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot/../scripts/manage.ps1" -RequestFile "$root/request.json" -ProgramsDirectory "$root/programs" *> "$root/last-run.log"
 $ErrorActionPreference='Stop'
 if(($LASTEXITCODE -eq 0) -ne $success){Get-Content "$root/last-run.log";throw 'Unexpected operation result'}
}
Run $true
$shortcutPath="$root/programs/NimbyRails France Hub/Nimby TCO (fixture).lnk"
function CheckShortcut {
 if(!(Test-Path -LiteralPath $shortcutPath)){throw 'Start menu shortcut missing'}
 $link=(New-Object -ComObject WScript.Shell).CreateShortcut($shortcutPath)
 if($link.TargetPath -ne [IO.Path]::GetFullPath("$root/installed/NimbyTco.exe") -or $link.WorkingDirectory -ne [IO.Path]::GetFullPath("$root/installed")){throw 'Incorrect shortcut target or working directory'}
}
CheckShortcut
if([IO.File]::ReadAllText("$root/installed/NimbyTco.exe") -ne 'version one'){throw 'Install failed'}
[IO.File]::WriteAllText("$root/package/Fixture/NimbyTco.exe",'version two')
$zip=Archive;$req.archive=$zip;$p.version='1.1.0';$p.sha256=(Get-FileHash $zip).Hash.ToLowerInvariant();$p.size=(Get-Item $zip).Length
Run $true
if([IO.File]::ReadAllText("$root/installed/NimbyTco.exe") -ne 'version two'){throw 'Update failed'}
CheckShortcut
Remove-Item -LiteralPath $shortcutPath
$req.action='rollback';Run $true
if([IO.File]::ReadAllText("$root/installed/NimbyTco.exe") -ne 'version one'){throw 'Rollback failed'}
CheckShortcut
$req.action='install';$p.sha256='0'*64;Run $false
if([IO.File]::ReadAllText("$root/installed/NimbyTco.exe") -ne 'version one'){throw 'Bad hash changed installation'}
$p.sha256=(Get-FileHash $zip).Hash.ToLowerInvariant();$req.expectedGameHash='0'*64;Run $false;$req.expectedGameHash=$gameHash
$req.action='remove';Run $true
if(Test-Path "$root/installed"){throw 'Uninstall failed'}
if(Test-Path -LiteralPath $shortcutPath){throw 'Start menu shortcut remains after uninstall'}
New-Item -ItemType Directory "$root/installed" | Out-Null
$req.action='install';Run $false
Write-Output 'PASS: install, update, rollback, invalid hash, incompatible game, uninstall, foreign directory protection'
$req.destination="$root/installed-mod";$p.id='fixture-mod';$p.kind='native-mod';$p.modId='fixture-mod';$req.nativeModsDirectory="$root/native-mods"
[IO.File]::WriteAllText("$root/package/Fixture/mod.txt",'[ModMeta]')
$zip=Archive;$req.archive=$zip;$p.sha256=(Get-FileHash $zip).Hash.ToLowerInvariant();$p.size=(Get-Item $zip).Length
Run $true
if(!(Test-Path "$root/native-mods/fixture-mod/mod.txt")){throw 'Custom mod directory is not linked to the game mod folder'}
$req.action='remove';Run $true
if(Test-Path "$root/native-mods/fixture-mod"){throw 'Mod junction remains after uninstall'}
$badZip="$root/traversal.zip";$z=[IO.Compression.ZipFile]::Open($badZip,[IO.Compression.ZipArchiveMode]::Create)
$entry=$z.CreateEntry('Fixture/../escape.txt');$stream=$entry.Open();$stream.WriteByte(42);$stream.Close();$z.Dispose()
$req.action='install';$req.archive=$badZip;$p.sha256=(Get-FileHash $badZip).Hash.ToLowerInvariant();$p.size=(Get-Item $badZip).Length
Run $false
Write-Output 'PASS: native mod custom folder junction, uninstall junction, zip traversal rejection'
$req.archive=$zip;$p.sha256=(Get-FileHash $zip).Hash.ToLowerInvariant();$p.size=(Get-Item $zip).Length
$p.loaderApi=1;$p.module='FixtureMod.dll'
Run $false # Declared C++ mod without its DLL.
[IO.File]::WriteAllText("$root/package/Fixture/FixtureMod.dll",'fixture module v1')
$zip=Archive;$req.archive=$zip;$p.sha256=(Get-FileHash $zip).Hash.ToLowerInvariant();$p.size=(Get-Item $zip).Length
$p.module='../FixtureMod.dll';Run $false;$p.module='FixtureMod.dll'
Run $true
$loaderLink="$root/game/NRFMods/fixture-mod"
if((Get-Content -LiteralPath "$loaderLink/nrf-mod.ini" -Raw) -notmatch 'library=FixtureMod.dll'){throw 'NRF module filename manifest missing'}
if([IO.File]::ReadAllText("$loaderLink/FixtureMod.dll") -ne 'fixture module v1'){throw 'NRF Loader registration missing'}
[IO.File]::WriteAllText("$root/package/Fixture/FixtureMod.dll",'fixture module v2')
$zip=Archive;$req.archive=$zip;$p.version='1.2.0';$p.sha256=(Get-FileHash $zip).Hash.ToLowerInvariant();$p.size=(Get-Item $zip).Length
Run $true
if([IO.File]::ReadAllText("$loaderLink/FixtureMod.dll") -ne 'fixture module v2'){throw 'NRF module update failed'}
$req.action='rollback';Run $true
if([IO.File]::ReadAllText("$loaderLink/FixtureMod.dll") -ne 'fixture module v1'){throw 'NRF module rollback failed'}
$req.action='remove';Run $true
if(Get-Item -LiteralPath $loaderLink -Force -ErrorAction SilentlyContinue){throw 'NRF junction remains'}
$req.action='install'
New-Item -ItemType Directory -Path $loaderLink -Force | Out-Null
[IO.File]::WriteAllText("$loaderLink/foreign.txt",'owned by someone else')
Run $false
if(!(Test-Path "$loaderLink/foreign.txt")){throw 'Foreign registration modified'}
Write-Output 'PASS: NRF mod DLL required, register, update, rollback, unregister, foreign registration protection'
