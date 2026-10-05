#!/usr/bin/env bash
# Fallback pairing: if the Echo Show's Bluetooth can't advertise, pair from
# this Linux machine with Meta's own Linux SDK, then hand the identity and
# device token to the Echo over adb. The Echo then connects as that gadget.
#
# Needs: bluez, python-dbus, python-gobject, python-cryptography, git, adb,
# and an SDK token from gadgets.muse.ai.
#
#   tools/pair-via-linux.sh mgst_…            # or put the token in ./sdk_token
#
# Env: ECHO_SERIAL (default G6G11B06033403LD), SDK_DIR (reuse a checkout).
set -euo pipefail

PKG=com.rusty.museecho
SERIAL=${ECHO_SERIAL:-G6G11B06033403LD}
TOKEN=${1:-$(cat sdk_token 2>/dev/null || true)}
command -v adb >/dev/null || PATH="$PATH:$HOME/Android/Sdk/platform-tools"

if [[ ! $TOKEN =~ ^mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$ ]]; then
    echo "Pass your SDK token (mgst_…) from gadgets.muse.ai as the first argument." >&2
    exit 1
fi

SDK_DIR=${SDK_DIR:-$(mktemp -d)/muse-gadget-sdk}
if [[ ! -d $SDK_DIR/linux ]]; then
    git clone --depth 1 https://github.com/facebookincubator/muse-gadget-sdk "$SDK_DIR"
fi

STATE=$(mktemp -d)
chmod 700 "$STATE"
trap 'rm -rf "$STATE"' EXIT
printf '%s' "$TOKEN" > "$STATE/sdk_token"

echo "Opening Bluetooth setup from $(hostname). In the Muse app: Settings > Devices >"
echo "Developer mode on, then Add device and pick the MuseGadget name printed below."
MUSEGADGET_STATE_DIR=$STATE PYTHONPATH=$SDK_DIR/linux/src python3 -m musegadget pair

if [[ ! -s $STATE/pairing.json ]]; then
    echo "Not paired; nothing to copy." >&2
    exit 1
fi

DROP=/sdcard/Android/data/$PKG/files
adb -s "$SERIAL" shell mkdir -p "$DROP"
adb -s "$SERIAL" push "$STATE/identity.json" "$STATE/pairing.json" "$STATE/sdk_token" "$DROP/"
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null
echo "Handed the pairing to the Echo Show; it imports the files on start and connects."
echo "Don't run this pairing on the Linux machine too: one identity, one gadget."
