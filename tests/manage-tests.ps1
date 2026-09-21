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
$req.destination="$root/migrated-mod";$p.id='fixture-migration';$p.modId='fixture-migration'
$p.Remove('loaderApi');$p.Remove('module');Run $true
$p.loaderApi=1;$p.module='FixtureMod.dll';Run $true
if(!(Test-Path "$root/game/NRFMods/fixture-migration/FixtureMod.dll")){throw 'Older Hub installation was not registered with NRF Loader'}
$req.action='remove';Run $true
Write-Output 'PASS: upgrade native mod installed by an older Hub to NRF Loader registration'

# SDK transaction: a failing new loader must restore the old distribution.
$req.destination="$root/installed-sdk";$p.id='fixture-sdk';$p.kind='sdk';$p.version='1.0.0'
$p.Remove('modId');$p.Remove('module');$p.loaderApi=1
New-Item -ItemType Directory -Path "$root/package/Fixture/loader" -Force | Out-Null
[IO.File]::WriteAllText("$root/package/Fixture/loader/install-proxy.ps1",'param($Action,$GameDirectory,$SourceDirectory); exit 0')
$zip=Archive;$req.action='install';$req.archive=$zip;$p.sha256=(Get-FileHash $zip).Hash.ToLowerInvariant();$p.size=(Get-Item $zip).Length
Run $true
[IO.File]::WriteAllText("$root/package/Fixture/loader/install-proxy.ps1",'param($Action,$GameDirectory,$SourceDirectory); if($Action -eq "Install"){exit 7}; exit 0')
$zip=Archive;$req.archive=$zip;$p.version='1.1.0';$p.sha256=(Get-FileHash $zip).Hash.ToLowerInvariant();$p.size=(Get-Item $zip).Length
Run $false
$restored=Get-Content -LiteralPath "$root/installed-sdk/.nrf-project.json" -Raw | ConvertFrom-Json
if($restored.version -ne '1.0.0'){throw 'Failed SDK promotion did not restore the previous distribution'}
$req.action='remove';Run $true
Write-Output 'PASS: failed SDK loader installation restores previous distribution'

# A junction inside an owned directory must never be traversed on removal.
$req.destination="$root/linked-tree";$p.id='fixture-links';$p.kind='tco';$p.Remove('loaderApi');$p.version='1.0.0'
$req.action='install';Run $true
New-Item -ItemType Directory -Path "$root/foreign-data" | Out-Null
[IO.File]::WriteAllText("$root/foreign-data/keep.txt",'foreign data')
New-Item -ItemType Junction -Path "$root/linked-tree/unsafe" -Target "$root/foreign-data" | Out-Null
$req.action='remove';Run $false
if([IO.File]::ReadAllText("$root/foreign-data/keep.txt") -ne 'foreign data'){throw 'Foreign data was changed'}
[IO.Directory]::Delete("$root/linked-tree/unsafe")
Run $true
Write-Output 'PASS: reparse-point protection preserves foreign data'

# A development package must not register links or replace the normal SDK proxy.
$req.action='install';$req.detached=$true;$req.origin='local'
$req.destination="$root/detached-sdk";$p.id='sdk';$p.kind='sdk';$p.loaderApi=1
[IO.File]::WriteAllText("$root/package/Fixture/loader/install-proxy.ps1",'param($Action,$GameDirectory,$SourceDirectory); [IO.File]::WriteAllText((Join-Path $GameDirectory "proxy-called.txt"),$Action); exit 0')
$zip=Archive;$req.archive=$zip;$p.sha256=(Get-FileHash $zip).Hash.ToLowerInvariant();$p.size=(Get-Item $zip).Length
Run $true
if(Test-Path "$root/game/proxy-called.txt"){throw 'Detached SDK activated its proxy'}
$detached=Get-Content -LiteralPath "$root/detached-sdk/.nrf-project.json" -Raw | ConvertFrom-Json
if($detached.origin -ne 'local'){throw 'Local origin was not recorded'}
$req.destination="$root/detached-mod";$p.id='detached-mod';$p.kind='native-mod';$p.modId='DetachedMod';$p.module='FixtureMod.dll'
Run $true
if(Test-Path "$root/game/NRFMods/detached-mod"){throw 'Detached mod registered with loader'}
if(Test-Path "$root/native-mods/DetachedMod"){throw 'Detached mod registered resources'}
Write-Output 'PASS: development SDK and mod preparation never activates the proxy or registers game links'
