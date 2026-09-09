#!/usr/bin/env bash
set -euo pipefail

api=${ANDROID_API:?Select the Android API level}
[[ "$api" == 26 || "$api" == 35 ]]
sdk=${ANDROID_HOME:?Android SDK is required}
export ANDROID_USER_HOME="${RUNNER_TEMP:?Runner temporary directory is required}/home-tunnel-android"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
sdkmanager=$(find "$sdk/cmdline-tools" -maxdepth 3 -type f -name sdkmanager | sort -V | tail -n 1)
test -x "$sdkmanager"
avdmanager="$(dirname "$sdkmanager")/avdmanager"
adb="$sdk/platform-tools/adb"
serial=emulator-5554
avd="home-tunnel-ci-$api"
image="system-images;android-$api;google_apis;x86_64"
mkdir -p instrumentation-evidence "$ANDROID_AVD_HOME"
echo "Install the Android API $api emulator image"
"$sdkmanager" "platform-tools" "emulator" "$image" > instrumentation-evidence/sdk-install.log
echo no | "$avdmanager" create avd --name "$avd" --package "$image" \
  --path "$ANDROID_AVD_HOME/$avd.avd" --device pixel_6
"$sdk/emulator/emulator" -list-avds | tee instrumentation-evidence/avds.txt
grep -Fxq "$avd" instrumentation-evidence/avds.txt
test -e /dev/kvm
sudo chmod a+rw /dev/kvm
"$sdk/emulator/emulator" -avd "$avd" -port 5554 -no-window -no-audio -no-snapshot \
  -no-boot-anim -gpu swiftshader_indirect > instrumentation-evidence/emulator.log 2>&1 &
emulator_pid=$!
trap '"$adb" -s "$serial" emu kill >/dev/null 2>&1 || true' EXIT
echo "Wait for $avd to boot"
booted=false
deadline=$((SECONDS + 300))
while (( SECONDS < deadline )); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    cat instrumentation-evidence/emulator.log >&2
    echo 'Emulator exited before Android was ready' >&2
    exit 1
  fi
  if [[ $(timeout 5 "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true) == 1 ]]; then
    booted=true
    break
  fi
  sleep 2
done
if [[ "$booted" != true ]]; then
  cat instrumentation-evidence/emulator.log >&2
  echo 'Android did not finish booting within five minutes' >&2
  exit 1
fi
echo "Run AndroidKeyStore and management UI checks on API $api"
"$adb" -s "$serial" shell input keyevent 82
"$adb" -s "$serial" shell settings put global window_animation_scale 0
"$adb" -s "$serial" shell settings put global transition_animation_scale 0
"$adb" -s "$serial" shell settings put global animator_duration_scale 0
python3 scripts/run-instrumentation.py --adb "$adb" --serial "$serial"
