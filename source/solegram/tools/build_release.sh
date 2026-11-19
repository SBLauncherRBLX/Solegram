#!/bin/bash
# Builds the signed release APK (needs local.properties and keystore/solegram-release.jks). Prints BUILD_OK.
cd "$(dirname "$0")/../.." || exit 1
LOG="${BUILD_LOG:-/tmp/solegram_build.log}"
for i in 1 2 3; do
  ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}" ./gradlew :TMessagesProj_App:assembleAfatRelease -q --max-workers=4 --no-build-cache > "$LOG" 2>&1 && { echo BUILD_OK; exit 0; }
  echo "attempt $i failed"; tail -5 "$LOG"
done
exit 1
