#!/bin/sh
set -eu
python3 .woodpecker/check-release.py
xvfb-run -a sh gradlew desktopTest --no-daemon --console=plain
