#!/usr/bin/env bash
# Build Muse Echo, install it on the Echo Show, restart it, and confirm it
# reconnects to Muse.
#
#   tools/deploy.sh [adb-serial-or-ip:port]
#
# Default device: $ECHO_ADB, else the only device adb sees. A wireless
# address (ip:port) is connected first.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
command -v adb >/dev/null || PATH="$PATH:$ANDROID_HOME/platform-tools"
PKG=com.rusty.museecho

DEVICE=${1:-${ECHO_ADB:-}}
if [[ $DEVICE == *:* ]]; then adb connect "$DEVICE" >/dev/null; fi
ADB=(adb ${DEVICE:+-s "$DEVICE"})

./gradlew assembleRelease testDebugUnitTest --console=plain -q
"${ADB[@]}" install -r app/build/outputs/apk/release/app-release.apk >/dev/null
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am force-stop $PKG
"${ADB[@]}" shell am start -n $PKG/.MainActivity >/dev/null
echo -n "Installed; waiting for Muse"
for _ in $(seq 1 30); do
    if "${ADB[@]}" logcat -d -s MuseEcho:I | grep -q "registered with the Muse"; then
        echo " - connected."
        exit 0
    fi
    echo -n "."
    sleep 2
done
echo
echo "Didn't connect within a minute. Recent log:" >&2
"${ADB[@]}" logcat -d -s MuseEcho:I AndroidRuntime:E | tail -15 >&2
exit 1
