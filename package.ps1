param([string]$Iscc="$env:LOCALAPPDATA/Programs/InnoSetup/ISCC.exe",[string]$QtRoot='C:/Qt/6.11.2/mingw_64',[string]$QtTools='C:/Qt/Tools',[string]$QtLicenseRoot='C:/Qt/Licenses')
$ErrorActionPreference='Stop'
$version=(Get-Content -LiteralPath "$PSScriptRoot/VERSION" -Raw).Trim()
if($version -notmatch '^\d+\.\d+\.\d+(?:-(?:alpha|beta)\.[1-9]\d*)?$'){throw 'Invalid VERSION'}
& "$PSScriptRoot/build.ps1" -QtRoot $QtRoot -QtTools $QtTools
$stage=Join-Path $PSScriptRoot ('build/package-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item -LiteralPath "$PSScriptRoot/build/NRFHub.exe" -Destination $stage
& "$QtRoot/bin/windeployqt.exe" --release --dir $stage "$stage/NRFHub.exe"
if($LASTEXITCODE){throw 'Qt deployment failed'}
Copy-Item -LiteralPath "$PSScriptRoot/scripts","$PSScriptRoot/README.md","$PSScriptRoot/THIRD_PARTY.md" -Destination $stage -Recurse
New-Item -ItemType Directory -Path "$stage/licenses" | Out-Null
Copy-Item -LiteralPath $QtLicenseRoot -Destination "$stage/licenses/Qt" -Recurse
Copy-Item -Path "$PSScriptRoot/licenses/Qt/*" -Destination "$stage/licenses/Qt"
Copy-Item -LiteralPath "$QtRoot/sbom" -Destination "$stage/licenses/Qt/sbom" -Recurse
Copy-Item -LiteralPath "$QtTools/mingw1310_64/licenses" -Destination "$stage/licenses/MinGW" -Recurse
@{feed='https://github.com/NimbyRails-France/hub/releases/latest/download/hub-latest.json'} | ConvertTo-Json | Set-Content "$stage/hub-update.json" -Encoding UTF8
New-Item -ItemType Directory -Force -Path "$PSScriptRoot/dist" | Out-Null
& $Iscc "/DStage=$stage" "/DOutput=$PSScriptRoot/dist" "/DVersion=$version" "$PSScriptRoot/installer.iss"
if($LASTEXITCODE){throw 'Installer failed'}
$exe=Get-Item "$PSScriptRoot/dist/NRFHub-$version-Setup.exe"
$hash=(Get-FileHash -LiteralPath $exe.FullName).Hash.ToLowerInvariant()
@{schema=1;product='NRFHub';platform='windows-x64';version=$version;url="https://github.com/NimbyRails-France/hub/releases/download/v$version/NRFHub-$version-Setup.exe";sha256=$hash;size=$exe.Length} | ConvertTo-Json | Set-Content "$PSScriptRoot/dist/hub-latest.json" -Encoding UTF8
"$hash  $($exe.Name)" | Set-Content "$PSScriptRoot/dist/SHA256SUMS.txt" -Encoding ascii
