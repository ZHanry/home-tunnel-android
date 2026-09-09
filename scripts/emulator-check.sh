#!/usr/bin/env bash
set -euo pipefail

api=${ANDROID_API:?Select the Android API level}
[[ "$api" == 26 || "$api" == 35 ]]
sdk=${ANDROID_HOME:?Android SDK is required}
sdkmanager=$(find "$sdk/cmdline-tools" -maxdepth 3 -type f -name sdkmanager | sort -V | tail -n 1)
test -x "$sdkmanager"
avdmanager="$(dirname "$sdkmanager")/avdmanager"
adb="$sdk/platform-tools/adb"
serial=emulator-5554
avd="home-tunnel-ci-$api"
image="system-images;android-$api;google_apis;x86_64"
mkdir -p instrumentation-evidence
"$sdkmanager" "platform-tools" "emulator" "$image" > instrumentation-evidence/sdk-install.log
echo no | "$avdmanager" create avd --name "$avd" --package "$image"
test -e /dev/kvm
sudo chmod a+rw /dev/kvm
"$sdk/emulator/emulator" -avd "$avd" -port 5554 -no-window -no-audio -no-snapshot \
  -no-boot-anim -gpu swiftshader_indirect > instrumentation-evidence/emulator.log 2>&1 &
trap '"$adb" -s "$serial" emu kill >/dev/null 2>&1 || true' EXIT
timeout 300 "$adb" -s "$serial" wait-for-device
booted=false
for _ in $(seq 1 120); do
  if [[ $("$adb" -s "$serial" shell getprop sys.boot_completed | tr -d '\r') == 1 ]]; then
    booted=true
    break
  fi
  sleep 2
done
[[ "$booted" == true ]]
"$adb" -s "$serial" shell input keyevent 82
"$adb" -s "$serial" shell settings put global window_animation_scale 0
"$adb" -s "$serial" shell settings put global transition_animation_scale 0
"$adb" -s "$serial" shell settings put global animator_duration_scale 0
python3 scripts/run-instrumentation.py --adb "$adb" --serial "$serial"
