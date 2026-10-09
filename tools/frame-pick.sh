#!/usr/bin/env bash
# Pick pictures for the Echo Show's photo frame and send them over.
#
#   tools/frame-pick.sh                 # file picker (multi-select), starts in ~/Pictures/wallpapers
#   tools/frame-pick.sh a.png b.jpg     # or name the files
#   tools/frame-pick.sh --list          # what's on the Echo now
#   tools/frame-pick.sh --clear         # remove them all
#
# Each picture is shrunk to a 1280px-wide JPEG (the screen is 960x480) before
# it's copied, so big wallpapers don't fill up the Echo.
# Device: $ECHO_ADB (serial or ip:port), else the one adb already sees.
set -euo pipefail
command -v adb >/dev/null || PATH="$PATH:$HOME/Android/Sdk/platform-tools"
FRAME=/sdcard/Android/data/com.rusty.museecho/files/frame

if [[ -n ${ECHO_ADB:-} ]]; then
    [[ $ECHO_ADB == *:* ]] && adb connect "$ECHO_ADB" >/dev/null
    ADB=(adb -s "$ECHO_ADB")
else
    ADB=(adb)
fi
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    echo "Can't reach the Echo. Plug it in, or set ECHO_ADB=ip:port (Developer options > Wireless debugging)." >&2
    exit 1
fi
"${ADB[@]}" shell mkdir -p "$FRAME"

case "${1:-}" in
    --list)
        "${ADB[@]}" shell ls "$FRAME"
        exit 0
        ;;
    --clear)
        "${ADB[@]}" shell "rm -f $FRAME/*"
        echo "Photo frame cleared."
        exit 0
        ;;
esac

files=("$@")
if [[ ${#files[@]} -eq 0 ]]; then
    picked=$(zenity --file-selection --multiple --separator=$'\n' --title="Pick photos for the Echo Show frame" \
        --filename="$HOME/Pictures/wallpapers/" \
        --file-filter="Images | *.png *.jpg *.jpeg *.webp *.PNG *.JPG *.JPEG *.WEBP") || exit 0
    mapfile -t files <<<"$picked"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
sent=0
for f in "${files[@]}"; do
    [[ -f $f ]] || continue
    # Name by content hash, so picking the same picture twice doesn't duplicate it.
    name="pick-$(sha1sum "$f" | cut -c1-10).jpg"
    if magick "$f[0]" -auto-orient -resize '1280x>' -quality 85 -strip "$tmp/$name" 2>/dev/null; then
        "${ADB[@]}" push "$tmp/$name" "$FRAME/$name" >/dev/null && sent=$((sent + 1))
        echo "  + $(basename "$f")"
    else
        echo "  ! skipped $(basename "$f") (not an image?)" >&2
    fi
done
total=$("${ADB[@]}" shell "ls $FRAME | wc -l" | tr -d '\r')
echo "Sent $sent; the frame has $total photos."
