$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
[Console]::InputEncoding=[Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$request=[Console]::In.ReadToEnd() | ConvertFrom-Json
$notes=[Collections.Generic.List[string]]::new()
$files=[Collections.Generic.List[object]]::new()
$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$watch=[Diagnostics.Stopwatch]::StartNew()
function Describe([string]$path,[string]$role) {
 if($files.Count -ge 1024 -or $watch.Elapsed.TotalSeconds -gt 65){return}
 try {
  $item=Get-Item -LiteralPath $path -Force
  if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){return}
  if(!$seen.Add($item.FullName)){return}
  $version=[Diagnostics.FileVersionInfo]::GetVersionInfo($item.FullName)
  $hash=$null
  if($item.Length -le 256MB){$hash=(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash}
  else{$notes.Add("Hash skipped (file exceeds 256 MiB): $($item.FullName)")}
  $files.Add([ordered]@{role=$role;path=$item.FullName;bytes=$item.Length;modifiedUtc=$item.LastWriteTimeUtc.ToString('o');
   fileVersion=$version.FileVersion;productVersion=$version.ProductVersion;product=$version.ProductName;
   company=$version.CompanyName;sha256=$hash})
 }catch{$notes.Add("Cannot inspect $path : $($_.Exception.Message)")}
}
function Inventory([string]$root,[string]$role,[int]$maxDepth) {
 if(!$root -or !(Test-Path -LiteralPath $root -PathType Container)){return}
 $pending=[Collections.Generic.Queue[object]]::new();$pending.Enqueue(@($root,0))
 while($pending.Count -and $files.Count -lt 1024 -and $watch.Elapsed.TotalSeconds -lt 65) {
  $next=$pending.Dequeue()
  try {
   $folder=Get-Item -LiteralPath $next[0] -Force
   if($folder.Attributes -band [IO.FileAttributes]::ReparsePoint){$notes.Add("Link not traversed: $($folder.FullName)");continue}
   foreach($entry in Get-ChildItem -LiteralPath $folder.FullName -Force) {
    if($entry.Attributes -band [IO.FileAttributes]::ReparsePoint){continue}
    if($entry.PSIsContainer){if($next[1] -lt $maxDepth){$pending.Enqueue(@($entry.FullName,($next[1]+1)))}}
    elseif($entry.Extension -in @('.dll','.exe')){Describe $entry.FullName $role}
   }
  }catch{$notes.Add("Cannot enumerate $($next[0]): $($_.Exception.Message)")}
 }
}
$os=[ordered]@{platform=[Environment]::OSVersion.VersionString;architecture=$env:PROCESSOR_ARCHITECTURE;
 utc=[DateTime]::UtcNow.ToString('o');timezone=[TimeZoneInfo]::Local.Id;logicalProcessors=[Environment]::ProcessorCount}
try {
 $v=Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
 $os.edition=$v.EditionID;$os.displayVersion=$v.DisplayVersion;$os.build=$v.CurrentBuildNumber;$os.revision=$v.UBR
 $w=Get-CimInstance Win32_OperatingSystem -OperationTimeoutSec 5
 $os.name=$w.Caption;$os.version=$w.Version;$os.totalMemoryKiB=$w.TotalVisibleMemorySize;$os.freeMemoryKiB=$w.FreePhysicalMemory
 $os.bootUtc=$w.LastBootUpTime.ToUniversalTime().ToString('o')
}catch{$notes.Add("OS details unavailable: $($_.Exception.Message)")}
$gpu=@()
try{$gpu=@(Get-CimInstance Win32_VideoController -OperationTimeoutSec 5 | Select-Object Name,DriverVersion,AdapterRAM)}catch{$notes.Add('GPU details unavailable')}
$game=$request.game
$steam=$null
if($game -and (Test-Path -LiteralPath $game -PathType Container)) {
 Describe (Join-Path $game 'NIMBYRails.exe') 'game-executable'
 Inventory $game 'game-file' 1
 try {
  $steamapps=Split-Path (Split-Path $game -Parent) -Parent
  foreach($acf in Get-ChildItem -LiteralPath $steamapps -Filter 'appmanifest_*.acf' -File) {
   if($acf.Length -gt 128KB){continue}
   $text=[IO.File]::ReadAllText($acf.FullName)
   $match=[regex]::Match($text,'"installdir"\s+"([^"]+)"')
   if($match.Success -and $match.Groups[1].Value -ieq (Split-Path $game -Leaf)) {
    $steam=[ordered]@{manifest=$acf.FullName}
    foreach($key in @('appid','buildid','LastUpdated','StateFlags','installdir')) {
     $m=[regex]::Match($text,'"'+$key+'"\s+"([^"]*)"');if($m.Success){$steam[$key]=$m.Groups[1].Value}
    }
    break
   }
  }
 }catch{$notes.Add("Steam build unavailable: $($_.Exception.Message)")}
}
foreach($project in $request.projects){Inventory $project.directory ("project:"+$project.id+":"+$project.version) 3}
$processes=[Collections.Generic.List[object]]::new()
foreach($process in @(Get-Process -Name NIMBYRails -ErrorAction SilentlyContinue)) {
 try {
  if(!$game -or $process.Path -ine (Join-Path $game 'NIMBYRails.exe')){continue}
  $modules=[Collections.Generic.List[object]]::new()
  foreach($module in $process.Modules) {
   $modules.Add([ordered]@{name=$module.ModuleName;path=$module.FileName;fileVersion=$module.FileVersionInfo.FileVersion;productVersion=$module.FileVersionInfo.ProductVersion})
   # Hash only game/project DLLs above, not hundreds of Windows system DLLs.
  }
  $processes.Add([ordered]@{pid=$process.Id;executable=$process.Path;startedUtc=$process.StartTime.ToUniversalTime().ToString('o');modules=$modules.ToArray()})
 }catch{$notes.Add("Loaded module inventory unavailable for PID $($process.Id): $($_.Exception.Message)")}
}
if($files.Count -ge 1024 -or $watch.Elapsed.TotalSeconds -ge 65){$notes.Add('Inventory capped at 1024 files / 65 seconds')}
[ordered]@{schema=1;os=$os;gpu=$gpu;gameDirectory=$game;steam=$steam;projects=$request.projects;
 files=$files.ToArray();gameProcesses=$processes.ToArray();notes=$notes.ToArray();elapsedSeconds=$watch.Elapsed.TotalSeconds} | ConvertTo-Json -Depth 12
