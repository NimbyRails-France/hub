#!/bin/sh
set -eu
python3 .woodpecker/check-release.py
cmake -S . -B build/ci -G Ninja -DCMAKE_TOOLCHAIN_FILE=/opt/nimby-ci/nimby-mingw.cmake -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON -DNRF_DEPLOY_QT=OFF
cmake --build build/ci --parallel 2
xvfb-run -a ctest --test-dir build/ci --output-on-failure --timeout 90
