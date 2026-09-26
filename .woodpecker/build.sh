#!/bin/sh
set -eu
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
python3 .woodpecker/check-release.py
# Pin shared tooling and the Kotlin client to an exact, reviewed SDK commit.
git clone https://github.com/NimbyRails-France/sdk.git .ci/sdk
git -C .ci/sdk checkout --detach "$(cat .woodpecker/sdk-revision.txt)"
export JAVA_HOME="$(python3 .ci/sdk/.woodpecker/toolchain.py java-linux)"
export PATH="$JAVA_HOME/bin:$PATH"
# Host-side unit/UI tests do not publish a Linux application.
apt-get update
apt-get install -y --no-install-recommends fontconfig libxi6 libxtst6 libxrender1 libgl1
skip_tests=$(python3 -c 'import json,os; p=json.load(open(".release-plan.json")); print("yes" if p["publish"] and p["channel"] == "alpha" and "Release-Validation: skip-tests" in os.environ.get("CI_COMMIT_MESSAGE", "").splitlines() else "no")')
if [ "$skip_tests" = yes ]; then
  echo 'Tests skipped for this alpha by explicit release request'
  sh gradlew prepareWindowsRuntime ciDependencyNotices -PnrfTargetWindows=true --no-daemon --max-workers=2 --console=plain
else
  xvfb-run -a sh gradlew check --no-daemon --max-workers=2 --console=plain
  sh gradlew prepareWindowsRuntime prepareWindowsTransactionTests ciDependencyNotices -PnrfTargetWindows=true --no-daemon --max-workers=2 --console=plain
fi
python3 .ci/sdk/.woodpecker/windows-app.py
if [ "$skip_tests" != yes ]; then
  xvfb-run -a python3 .woodpecker/test-installer.py
fi
