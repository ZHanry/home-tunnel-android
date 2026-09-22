#!/usr/bin/env bash
# Source this helper so the following native builder sees the resolved SDK root.
set -euo pipefail

android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
test -d "$android_sdk/cmdline-tools"
android_sdkmanager=$(find "$android_sdk/cmdline-tools" -maxdepth 3 -type f -name sdkmanager | sort -V | tail -n 1)
test -x "$android_sdkmanager"
export ANDROID_HOME="$android_sdk"
export ANDROID_SDK_ROOT="$android_sdk"
"$android_sdkmanager" 'ndk;27.2.12479018' 'cmake;3.22.1'
