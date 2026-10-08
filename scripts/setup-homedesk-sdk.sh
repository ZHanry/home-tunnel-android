#!/usr/bin/env bash
set -euo pipefail
sdk=${ANDROID_HOME:?Android SDK is required}
manager=$(find "$sdk/cmdline-tools" -maxdepth 3 -type f -name sdkmanager | sort -V | tail -n 1)
test -x "$manager"
"$manager" "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018"
echo '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17  gradle/wrapper/gradle-wrapper.jar' | sha256sum -c -
android=homedesk-core/client/flutter/android
cp gradle/wrapper/gradle-wrapper.jar "$android/gradle/wrapper/"
printf '\ndistributionSha256Sum=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab\n' >> "$android/gradle/wrapper/gradle-wrapper.properties"
echo "ANDROID_NDK_HOME=$sdk/ndk/27.2.12479018" >> "$GITHUB_ENV"
echo "ANDROID_NDK=$sdk/ndk/27.2.12479018" >> "$GITHUB_ENV"
