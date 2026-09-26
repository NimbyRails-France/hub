param(
    [Parameter(Mandatory=$true)][string]$NativePackageVersion,
    [string]$Iscc="$env:LOCALAPPDATA/Programs/InnoSetup/ISCC.exe",
    [switch]$SkipTests
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$version=(Get-Content -LiteralPath "$root/VERSION" -Raw).Trim()
if($version -notmatch '^\d+\.\d+\.\d+(?:-(?:alpha|beta)\.[1-9]\d*)?$'){throw 'Invalid VERSION'}
if($NativePackageVersion -notmatch '^\d+\.\d+\.\d+$'){throw 'Native package version must be numeric and increase for every installer'}
if(!(Test-Path -LiteralPath $Iscc -PathType Leaf)){throw "Inno Setup compiler missing: $Iscc"}
# Inno's original AppId and switches are retained for upgrades from the Qt Hub.
# The payload is the complete Kotlin application image, including its JVM.
# No installer is run here; this command only builds local release files.
$tasks=@('createDistributable')
if(!$SkipTests){$tasks=@('check')+$tasks}
& "$root/gradlew.bat" -p $root @tasks "-PnativePackageVersion=$NativePackageVersion" --no-daemon --console=plain
if($LASTEXITCODE){throw 'Hub validation/application packaging failed'}
$stage="$root/build/gradle/compose/binaries/main/app/NRFHub"
if(!(Test-Path -LiteralPath "$stage/NRFHub.exe")){throw 'Kotlin application image missing'}
$out="$root/build/gradle/release/$version/windows-x64"
New-Item -ItemType Directory -Path $out -Force | Out-Null
& $Iscc "/DStage=$stage" "/DOutput=$out" "/DVersion=$version" "/DNativeVersion=$NativePackageVersion" "$PSScriptRoot/installer.iss"
if($LASTEXITCODE){throw 'Inno installer compilation failed'}
$exe=Get-Item -LiteralPath "$out/NRFHub-$version-windows-x64-Setup.exe"
$channel=if($version -match '-(alpha|beta)\.'){ $Matches[1] }else{ 'stable' }
$manifest=[ordered]@{schema=1;product='NRFHub';platform='windows-x64';version=$version;channel=$channel;
    url="https://github.com/NimbyRails-France/hub/releases/download/v$version/$($exe.Name)";
    size=$exe.Length;sha256=(Get-FileHash -LiteralPath $exe.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
# No installer field means the established Inno command contract. Both old Qt
# and new Kotlin Hubs accept this manifest; never label this jpackage-exe.
$manifest | ConvertTo-Json | Set-Content -LiteralPath "$out/hub-latest-windows-x64.json" -Encoding UTF8
$manifest | ConvertTo-Json | Set-Content -LiteralPath "$out/hub-latest.json" -Encoding UTF8
$files=@($exe.FullName,"$out/hub-latest-windows-x64.json","$out/hub-latest.json")
$hashes=foreach($file in $files){"$((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant())  $([IO.Path]::GetFileName($file))"}
$hashes | Set-Content -LiteralPath "$out/SHA256SUMS.txt" -Encoding ascii
Write-Output "Windows release ready: $out"
