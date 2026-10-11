#!/usr/bin/env python3
"""Send this laptop's desktop colours and wallpaper to echo-hub, for the Muse Echo's
"Laptop colours" theme and "Laptop wallpaper" background.

Reads what illogical-impulse (ii dots) generates with matugen whenever the wallpaper
changes: ~/.local/state/quickshell/user/generated/colors.json, and the wallpaper path
next to it. A systemd path unit runs this when those files change; it does nothing
(quietly) when the Latitude can't be reached, e.g. away from home.
"""
import base64
import io
import json
import os
import sys
import urllib.request
from pathlib import Path

HUB = os.environ.get("ECHO_HUB", "http://192.168.1.158:8094")
GEN = Path.home() / ".local/state/quickshell/user/generated"
KEYS = ["background", "surface_container_lowest", "surface_container_low", "surface_container_high",
        "on_surface", "on_surface_variant", "primary", "on_primary_container", "tertiary", "error"]
# The Echo's screen, a little bigger: it's blurred there anyway.
SIZE = (960, 480)


def wallpaper_path() -> Path | None:
    try:
        p = Path((GEN / "wallpaper/path.txt").read_text().strip())
        if p.is_file():
            return p
    except OSError:
        pass
    try:
        cfg = json.loads((Path.home() / ".config/illogical-impulse/config.json").read_text())
        p = Path(cfg["background"]["wallpaperPath"])
        return p if p.is_file() else None
    except (OSError, KeyError, TypeError, json.JSONDecodeError):
        return None


def wallpaper_jpeg(path: Path) -> bytes | None:
    try:
        from PIL import Image, ImageOps
    except ImportError:
        return None
    try:
        with Image.open(path) as im:
            im = ImageOps.fit(im.convert("RGB"), SIZE)
            buf = io.BytesIO()
            im.save(buf, "JPEG", quality=80)
            return buf.getvalue()
    except OSError:  # a video wallpaper or something Pillow can't read: colours only
        return None


def main() -> int:
    colors = json.loads((GEN / "colors.json").read_text())
    body = {"colors": {k: colors[k] for k in KEYS if k in colors}}
    wp = wallpaper_path()
    jpeg = wallpaper_jpeg(wp) if wp else None
    if jpeg:
        body["wallpaper_b64"] = base64.b64encode(jpeg).decode()
    req = urllib.request.Request(f"{HUB}/desktop", data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-Requested-With": "echo-settings"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            print(r.read().decode())
    except OSError as exc:
        print(f"echo-hub not reachable ({exc}); skipping", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
