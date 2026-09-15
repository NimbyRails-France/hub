param([Parameter(Mandatory=$true)][string]$RequestFile,
 [string]$ProgramsDirectory=[Environment]::GetFolderPath('Programs'))
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$request=Get-Content -LiteralPath $RequestFile -Raw | ConvertFrom-Json
$project=$request.project
if($project.id -notmatch '^[a-z][a-z0-9-]{0,63}$'){throw 'Invalid project ID'}
if($request.action -notin @('install','remove','rollback')){throw 'Invalid operation'}
$destination=[IO.Path]::GetFullPath($request.destination).TrimEnd('\','/')
if(![IO.Path]::IsPathRooted($request.destination) -or $destination.Length -lt 8 -or $destination -eq [IO.Path]::GetPathRoot($destination).TrimEnd('\')){throw 'Unsafe destination'}
$parent=[IO.Path]::GetDirectoryName($destination)
$previous=$destination+'.nrf-previous'
function ReadRecord([string]$path){
 if(!(Test-Path -LiteralPath "$path/.nrf-project.json")){throw "Unmanaged directory: $path"}
 if((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Managed directory must not be a link'}
 $r=Get-Content -LiteralPath "$path/.nrf-project.json" -Raw | ConvertFrom-Json
 if($r.id -ne $project.id){throw 'Project identity mismatch'}
 return $r
}
function RemoveOwned([string]$path){
 $full=[IO.Path]::GetFullPath($path)
 if($full -ne $destination -and $full -ne $previous){throw 'Unexpected removal path'}
 $null=ReadRecord $full
 foreach($item in @(Get-ChildItem -LiteralPath $full -Recurse -Force)){
  if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing to recursively remove a directory containing links'}
 }
 Remove-Item -LiteralPath $full -Recurse -Force
}
function TcoShortcut([bool]$remove=$false){
 # Shortcut failures must not invalidate an otherwise completed installation.
 try{
  $folder=Join-Path $ProgramsDirectory 'NimbyRails France Hub'
  $name=if($project.id -eq 'tco'){'Nimby TCO.lnk'}else{"Nimby TCO ($($project.id)).lnk"}
  $path=Join-Path $folder $name
  $target=Join-Path $destination 'NimbyTco.exe'
  $shell=New-Object -ComObject WScript.Shell
  if(Test-Path -LiteralPath $path){
   $existing=$shell.CreateShortcut($path)
   if($existing.TargetPath -ine $target){throw 'Shortcut belongs to another installation'}
  }
  if($remove){if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path};return}
  New-Item -ItemType Directory -Force -Path $folder | Out-Null
  $shortcut=$shell.CreateShortcut($path)
  $shortcut.TargetPath=$target
  $shortcut.WorkingDirectory=$destination
  $shortcut.IconLocation="$target,0"
  $shortcut.Description='Nimby TCO'
  $shortcut.Save()
 }catch{Write-Warning "Start menu shortcut: $($_.Exception.Message)"}
}
function Closed {
 $gameExe=[IO.Path]::GetFullPath((Join-Path $request.gameDirectory 'NIMBYRails.exe'))
 foreach($process in @(Get-Process -Name NIMBYRails,NimbyTco,NimbyRailsLoader -ErrorAction SilentlyContinue)){
  if(!$process.Path -or $process.Path -ieq $gameExe -or $process.Path.StartsWith($destination+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Close the game, TCO and external loader first'}
 }
}
function CompatibleGame {
 $exe=Join-Path $request.gameDirectory 'NIMBYRails.exe'
 $hash=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
 if($hash -ne $request.expectedGameHash -or $hash -notin @($project.gameSha256)){throw 'Game changed or unsupported'}
}
function Proxy([string]$path,[string]$action){
 & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$path/loader/install-proxy.ps1" -Action $action -GameDirectory $request.gameDirectory -SourceDirectory "$path/loader"
 if($LASTEXITCODE){throw "SDK loader $action failed"}
}
function LinkMod([string]$path,[object]$record){
 if(!$record.modLink){return}
 $link=[IO.Path]::GetFullPath($record.modLink)
 if(Test-Path -LiteralPath $link){
  $item=Get-Item -LiteralPath $link
  if(!($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or [IO.Path]::GetFullPath($item.Target[0]) -ne $destination){throw 'Existing mod path is not our junction'}
  [IO.Directory]::Delete($link)
 }
 if($path){$null=New-Item -ItemType Junction -Path $link -Target $destination}
}
Closed
$old=$null
if(Test-Path -LiteralPath $destination){$old=ReadRecord $destination}
if($request.action -eq 'remove'){
 if(!$old){throw 'Project not installed'}
 if($old.kind -eq 'sdk'){Proxy $destination 'Remove'}
 if($old.kind -eq 'native-mod'){LinkMod '' $old}
 RemoveOwned $destination
 if(Test-Path -LiteralPath $previous){RemoveOwned $previous}
 if($old.kind -eq 'tco'){TcoShortcut $true}
 '{}' | Set-Content -LiteralPath $request.resultFile -Encoding UTF8
 Write-Output 'Uninstalled';exit 0
}
if($request.action -eq 'rollback'){
 if(!$old){throw 'Project not installed'}
 $prior=ReadRecord $previous
 # A saved version can be restored only on its supported game build.
 $hash=(Get-FileHash -LiteralPath (Join-Path $request.gameDirectory 'NIMBYRails.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
 if($hash -notin @($prior.gameSha256)){throw 'Previous version does not support this game'}
 if($old.kind -eq 'sdk'){Proxy $destination 'Remove'}
 $swap=$destination+'.nrf-swap-'+[guid]::NewGuid().ToString('N')
 Move-Item -LiteralPath $destination -Destination $swap
 try{Move-Item -LiteralPath $previous -Destination $destination;if($prior.kind -eq 'sdk'){Proxy $destination 'Install'}}catch{
  if(Test-Path -LiteralPath $destination){Move-Item -LiteralPath $destination -Destination $previous}
  Move-Item -LiteralPath $swap -Destination $destination
  if($old.kind -eq 'sdk'){Proxy $destination 'Install'}
  throw
 }
 Move-Item -LiteralPath $swap -Destination $previous
 if($prior.kind -eq 'tco'){TcoShortcut}
 Get-Content -LiteralPath "$destination/.nrf-project.json" -Raw | Set-Content -LiteralPath $request.resultFile -Encoding UTF8
 Write-Output 'Previous version restored';exit 0
}
CompatibleGame
if($project.kind -notin @('sdk','tco','native-mod')){throw 'Unsupported project type'}
if($project.rootFolder -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,100}$'){throw 'Invalid archive root'}
if((Get-FileHash -LiteralPath $request.archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $project.sha256.ToLowerInvariant()){throw 'Archive hash mismatch'}
if((Get-Item -LiteralPath $request.archive).Length -ne $project.size){throw 'Archive size mismatch'}
if($old -and $old.kind -ne $project.kind){throw 'Project type changed'}
if($project.kind -eq 'sdk' -and !$old -and (Test-Path -LiteralPath (Join-Path $request.gameDirectory 'NimbyRailsSDK-install.json'))){throw 'SDK loader already installed outside the Hub: remove it with its original installer before adopting it'}
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$stage=Join-Path $parent ('.nrf-stage-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
$stagePrefix=[IO.Path]::GetFullPath($stage).TrimEnd('\')+'\'
$zip=[IO.Compression.ZipFile]::OpenRead($request.archive)
try {
 if($zip.Entries.Count -gt 30000){throw 'Too many archive files'}
 $expanded=0L;$prefix=$project.rootFolder+'/'
 foreach($entry in $zip.Entries){
  $name=$entry.FullName.Replace('\','/')
  if(!$name.StartsWith($prefix,[StringComparison]::Ordinal)){throw 'Unexpected archive root'}
  $relative=$name.Substring($prefix.Length)
  if(!$relative){continue}
  if($relative.Contains(':') -or $relative.StartsWith('/') -or ($relative.Split('/') -contains '..') -or ($relative.Split('/') -contains '.')){throw 'Unsafe archive path'}
  if(($entry.ExternalAttributes -band 0xF0000000) -eq 0xA0000000){throw 'Archive links are not supported'}
  $out=[IO.Path]::GetFullPath((Join-Path $stage $relative))
  if(!$out.StartsWith($stagePrefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Archive path escapes staging directory'}
  $expanded+=$entry.Length;if($expanded -gt 2147483648){throw 'Expanded archive exceeds 2 GB'}
  if($name.EndsWith('/')){New-Item -ItemType Directory -Force -Path $out | Out-Null;continue}
  New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($out)) | Out-Null
  [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$out,$false)
 }
}finally{$zip.Dispose()}
$record=[ordered]@{id=$project.id;kind=$project.kind;version=$project.version;directory=$destination;gameSha256=@($project.gameSha256);installedUtc=[DateTime]::UtcNow.ToString('o')}
if($project.sdkMin){$record.sdkMin=$project.sdkMin;$record.sdkMaxExclusive=$project.sdkMaxExclusive}
if($project.kind -eq 'sdk' -and !(Test-Path -LiteralPath "$stage/loader/install-proxy.ps1")){throw 'SDK loader missing'}
if($project.kind -eq 'tco' -and !(Test-Path -LiteralPath "$stage/NimbyTco.exe")){throw 'TCO executable missing'}
if($project.kind -eq 'native-mod'){
 if($project.modId -notmatch '^[A-Za-z0-9_-]{1,100}$' -or !(Test-Path -LiteralPath "$stage/mod.txt")){throw 'Invalid native mod'}
 $mods=if($request.nativeModsDirectory){[IO.Path]::GetFullPath($request.nativeModsDirectory)}else{Join-Path $env:USERPROFILE 'Saved Games/Weird and Wry/NIMBY Rails/mods'}
 New-Item -ItemType Directory -Force -Path $mods | Out-Null
 $record.modLink=Join-Path $mods $project.modId
 if(!$old -and (Test-Path -LiteralPath $record.modLink)){throw 'A mod with this ID already exists'}
 if($old -and $old.modLink -ne $record.modLink){throw 'Mod ID changed'}
}
$record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$stage/.nrf-project.json" -Encoding UTF8
Closed;CompatibleGame
if(Test-Path -LiteralPath $previous){RemoveOwned $previous}
$proxyRemoved=$false;$promoted=$false;$proxyInstalled=$false;$modLinked=$false
try{
 if($old -and $old.kind -eq 'sdk'){Proxy $destination 'Remove';$proxyRemoved=$true}
 if($old){Move-Item -LiteralPath $destination -Destination $previous}
 Move-Item -LiteralPath $stage -Destination $destination;$promoted=$true
 if($project.kind -eq 'sdk'){Proxy $destination 'Install';$proxyInstalled=$true}
 if($project.kind -eq 'native-mod'){LinkMod $destination ([pscustomobject]$record);$modLinked=$true}
 $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $request.resultFile -Encoding UTF8
}catch{
 if($proxyInstalled){Proxy $destination 'Remove'}
 if($modLinked -and !$old){LinkMod '' ([pscustomobject]$record)}
 if($promoted){RemoveOwned $destination}
 if(Test-Path -LiteralPath $previous){Move-Item -LiteralPath $previous -Destination $destination}
 if($proxyRemoved){Proxy $destination 'Install'}
 throw
}
if($project.kind -eq 'tco'){TcoShortcut}
Write-Output 'Installed. Previous version retained for rollback.'
