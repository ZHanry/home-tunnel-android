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
"$adb" start-server
"$sdk/emulator/emulator" -avd "$avd" -port 5554 -no-window -no-audio -no-snapshot \
  -no-boot-anim -no-metrics -gpu swiftshader_indirect > instrumentation-evidence/emulator.log 2>&1 &
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
  "$adb" devices -l > instrumentation-evidence/adb-devices.txt 2>&1 || true
  timeout 10 "$adb" -s "$serial" shell getprop > instrumentation-evidence/boot-properties.txt 2>&1 || true
  timeout 10 "$adb" -s "$serial" logcat -d > instrumentation-evidence/boot-logcat.txt 2>&1 || true
  cat instrumentation-evidence/emulator.log >&2
  cat instrumentation-evidence/adb-devices.txt >&2
  echo 'Android did not finish booting within five minutes' >&2
  exit 1
fi
echo "Run AndroidKeyStore and management UI checks on API $api"
"$adb" -s "$serial" shell input keyevent 82
"$adb" -s "$serial" install -r outputs/android-debug/app-debug.apk
"$adb" -s "$serial" install -r outputs/android-debug/app-debug-androidTest.apk
"$adb" -s "$serial" shell am instrument -w -r \
  io.github.zhanry.hometunnel.test/androidx.test.runner.AndroidJUnitRunner \
  | tee instrumentation-evidence/android-keystore.txt
grep -Eq 'OK \(2 tests\)' instrumentation-evidence/android-keystore.txt
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[12]' instrumentation-evidence/android-keystore.txt; then
  echo 'AndroidKeyStore authentication checks failed' >&2
  exit 1
fi
"$adb" -s "$serial" logcat -c
"$adb" -s "$serial" shell am start -W -n io.github.zhanry.hometunnel/com.carriez.flutter_hbb.MainActivity \
  | tee instrumentation-evidence/startup.txt
sleep 5
app_pid=$("$adb" -s "$serial" shell pidof io.github.zhanry.hometunnel | tr -d '\r')
test -n "$app_pid"
"$adb" -s "$serial" logcat --pid="$app_pid" -d > instrumentation-evidence/app-startup-logcat.txt
if grep -Eq 'FATAL EXCEPTION|Fatal signal|Abort message:' instrumentation-evidence/app-startup-logcat.txt; then
  echo 'HomeDesk native app crashed during startup' >&2
  exit 1
fi
"$adb" -s "$serial" exec-out screencap -p > instrumentation-evidence/startup.png
ANDROID_RUNTIME_API="$api" python3 - <<'PY'
import hashlib, json, os, subprocess
from pathlib import Path
root=Path('instrumentation-evidence')
report={'status':'passed','api_level':int(os.environ['ANDROID_RUNTIME_API']),
        'revision':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'tests':2,'scope':'real AndroidKeyStore round trip, non-exportable key, IV/ciphertext/AAD/version tamper rejection and unconfigured app startup',
        'physical_device':False,'remote_media_acceptance':'not_run',
        'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in root.iterdir() if p.is_file() and p.name!='instrumentation.json'}}
(root/'instrumentation.json').write_text(json.dumps(report,indent=2)+'\n')
PY
"$adb" -s "$serial" shell input keyevent 82
"$adb" -s "$serial" shell settings put global window_animation_scale 0
"$adb" -s "$serial" shell settings put global transition_animation_scale 0
"$adb" -s "$serial" shell settings put global animator_duration_scale 0
