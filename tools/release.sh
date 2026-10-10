#!/usr/bin/env bash
# Publish the current build to the Latitude's echo-hub; the Echo Show picks it
# up within a few hours (or at once via setup > Check for updates).
#
#   tools/release.sh ["what changed"]
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
NOTES=${1:-$(git log -1 --format=%s)}
HUB_DIR=.local/share/echo-hub/museecho

if [[ -n $(git status --porcelain --untracked-files=no) ]]; then
    echo "Commit first: the version number comes from the commit count." >&2
    exit 1
fi
./gradlew assembleRelease testDebugUnitTest --console=plain -q
APK=app/build/outputs/apk/release/app-release.apk
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
read -r CODE NAME < <("$BT/aapt" dump badging "$APK" | sed -nE "s/^package: .*versionCode='([0-9]+)' versionName='([^']+)'.*/\1 \2/p")
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
python3 - "$CODE" "$NAME" "$SHA" "$NOTES" > /tmp/museecho-latest.json <<'PY'
import json, sys
code, name, sha, notes = sys.argv[1:]
print(json.dumps({"versionCode": int(code), "versionName": name, "sha256": sha, "notes": notes}))
PY
scp -q "$APK" "latitude:$HUB_DIR/app.apk.tmp"
scp -q /tmp/museecho-latest.json "latitude:$HUB_DIR/latest.json.tmp"
# APK first, then the pointer to it, so a check never sees a half-published release.
ssh latitude "mv $HUB_DIR/app.apk.tmp $HUB_DIR/app.apk && mv $HUB_DIR/latest.json.tmp $HUB_DIR/latest.json"
echo "Published $NAME (code $CODE): $NOTES"
