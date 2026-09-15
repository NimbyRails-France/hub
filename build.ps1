param([string]$QtRoot='C:/Qt/6.11.2/mingw_64',[string]$QtTools='C:/Qt/Tools')
$ErrorActionPreference='Stop'
$env:PATH="$QtTools/mingw1310_64/bin;$QtRoot/bin;$env:PATH"
& "$QtTools/CMake_64/bin/cmake.exe" -S $PSScriptRoot -B "$PSScriptRoot/build" -G Ninja "-DCMAKE_MAKE_PROGRAM=$QtTools/Ninja/ninja.exe" "-DCMAKE_CXX_COMPILER=$QtTools/mingw1310_64/bin/g++.exe" "-DCMAKE_PREFIX_PATH=$QtRoot" -DCMAKE_BUILD_TYPE=Release
if($LASTEXITCODE){throw 'Configuration failed'}
& "$QtTools/CMake_64/bin/cmake.exe" --build "$PSScriptRoot/build"
if($LASTEXITCODE){throw 'Build failed'}
& "$QtRoot/bin/windeployqt.exe" --release "$PSScriptRoot/build/NRFHub.exe"
if($LASTEXITCODE){throw 'Deployment failed'}
& "$QtTools/CMake_64/bin/ctest.exe" --test-dir "$PSScriptRoot/build" --output-on-failure
if($LASTEXITCODE){throw 'Tests failed'}
