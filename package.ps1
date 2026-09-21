param([string]$Iscc="$env:LOCALAPPDATA/Programs/InnoSetup/ISCC.exe")
$ErrorActionPreference='Stop'
$version=(Get-Content -LiteralPath "$PSScriptRoot/VERSION" -Raw).Trim()
& "$PSScriptRoot/build.ps1" -Package
$stage=Join-Path $PSScriptRoot 'build/gradle/compose/binaries/main/app/NRFHub'
if(!(Test-Path -LiteralPath "$stage/NRFHub.exe")){throw 'Gradle application image is missing'}
Copy-Item -LiteralPath "$PSScriptRoot/README.md","$PSScriptRoot/THIRD_PARTY.md" -Destination $stage
Copy-Item -LiteralPath "$PSScriptRoot/docs" -Destination $stage -Recurse -Force
New-Item -ItemType Directory -Force -Path "$stage/licenses" | Out-Null
New-Item -ItemType Directory -Force -Path "$stage/licenses/dependencies" | Out-Null
Copy-Item -Path "$PSScriptRoot/build/gradle/dependency-notices/*" -Destination "$stage/licenses/dependencies" -Recurse -Force
New-Item -ItemType Directory -Force -Path "$PSScriptRoot/dist" | Out-Null
& $Iscc "/DStage=$stage" "/DOutput=$PSScriptRoot/dist" "/DVersion=$version" "$PSScriptRoot/installer.iss"
if($LASTEXITCODE){throw 'Installer failed'}
$exe=Get-Item "$PSScriptRoot/dist/NRFHub-$version-Setup.exe"
$hash=(Get-FileHash -LiteralPath $exe.FullName).Hash.ToLowerInvariant()
@{schema=1;product='NRFHub';platform='windows-x64';version=$version;url="https://github.com/NimbyRails-France/hub/releases/download/v$version/NRFHub-$version-Setup.exe";sha256=$hash;size=$exe.Length} | ConvertTo-Json | Set-Content "$PSScriptRoot/dist/hub-latest.json" -Encoding UTF8
"$hash  $($exe.Name)" | Set-Content "$PSScriptRoot/dist/SHA256SUMS.txt" -Encoding ascii
