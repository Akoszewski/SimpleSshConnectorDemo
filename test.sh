#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME=/home/debian/Android/Sdk
export ANDROID_SDK_ROOT=/home/debian/Android/Sdk
export JAVA_HOME=/opt/android-studio/jbr
export PATH=/home/debian/Android/Sdk/platform-tools:/home/debian/Android/Sdk/cmdline-tools/latest/bin:/home/debian/.local/bin:/usr/local/bin:/usr/bin:/bin

gradle testDebugUnitTest
