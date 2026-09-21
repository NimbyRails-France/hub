$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
[Console]::InputEncoding=New-Object Text.UTF8Encoding($false)
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
$r=[Console]::In.ReadToEnd() | ConvertFrom-Json
function Item([string]$path) { Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
function Link([string]$path,[string]$target) {
 $item=Item $path
 if($item -and (!($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or [IO.Path]::GetFullPath($item.Target[0]) -ine [IO.Path]::GetFullPath($target))){throw 'Existing path is not our junction'}
 return $item
}
switch($r.action) {
 'gameRunning' {
  $game=[IO.Path]::GetFullPath((Join-Path $r.game 'NIMBYRails.exe'))
  $running=@(Get-Process -Name NIMBYRails -ErrorAction SilentlyContinue | Where-Object { !$_.Path -or $_.Path -ieq $game })
  if($running.Count){'true'}else{'false'}
 }
 'requestGameClose' {
  $game=[IO.Path]::GetFullPath((Join-Path $r.game 'NIMBYRails.exe'))
  foreach($p in @(Get-Process -Name NIMBYRails -ErrorAction SilentlyContinue)) {
   if(!$p.Path){throw 'Impossible de vérifier le jeu actif. Fermez-le manuellement.'}
   if($p.Path -ieq $game -and !$p.CloseMainWindow()){throw 'Fermez NIMBY Rails manuellement, puis relancez depuis le Hub.'}
  }
 }
 'closed' {
  $game=[IO.Path]::GetFullPath((Join-Path $r.game 'NIMBYRails.exe'))
  foreach($p in @(Get-Process -Name NIMBYRails,NimbyTco,NimbyRailsLoader,NimbyRailsFranceLoader -ErrorAction SilentlyContinue)) {
   if(!$p.Path -or $p.Path -ieq $game -or $p.Path.StartsWith($r.destination+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Fermez le jeu, le TCO et le chargeur avant cette operation.'}
  }
 }
 'linkTarget' {
  $item=Item $r.path
  if($item){if(!($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Existing path is not a junction'};[IO.Path]::GetFullPath($item.Target[0])}
 }
 'createLink' {
  if(!(Link $r.path $r.target)){
   New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($r.path)) | Out-Null
   New-Item -ItemType Junction -Path $r.path -Target $r.target | Out-Null
  }
 }
 'removeLink' { if(Link $r.path $r.target){[IO.Directory]::Delete($r.path)} }
 'checkTree' {
  $item=Get-Item -LiteralPath $r.path -Force
  if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Managed directory is a link'}
  $pending=New-Object 'System.Collections.Generic.Stack[string]'
  $pending.Push($item.FullName)
  while($pending.Count -gt 0){
   foreach($child in @(Get-ChildItem -LiteralPath $pending.Pop() -Force)){
    if($child.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Managed directory contains links'}
    if($child.PSIsContainer){$pending.Push($child.FullName)}
   }
  }
 }
 'shortcut' {
  $folder=Join-Path $r.programs 'NimbyRails France Hub'
  $name=if($r.id -eq 'tco'){'Nimby TCO.lnk'}else{'Nimby TCO ('+$r.id+').lnk'}
  $path=Join-Path $folder $name
  $target=Join-Path $r.destination 'NimbyTco.exe'
  $shell=New-Object -ComObject WScript.Shell
  if(Test-Path -LiteralPath $path){if($shell.CreateShortcut($path).TargetPath -ine $target){throw 'Shortcut belongs to another installation'}}
  if($r.remove -eq 'true'){if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path};break}
  New-Item -ItemType Directory -Force -Path $folder | Out-Null
  $link=$shell.CreateShortcut($path);$link.TargetPath=$target;$link.WorkingDirectory=$r.destination;$link.IconLocation=$target+',0';$link.Description='Nimby TCO';$link.Save()
 }
 default { throw 'Unknown Windows operation' }
}
exit 0
