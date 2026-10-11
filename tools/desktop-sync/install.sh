#!/bin/sh
# Install the desktop-sync user units on this laptop.
set -e
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p ~/.config/systemd/user
for u in echo-desktop-sync.service echo-desktop-sync.path echo-desktop-sync.timer; do
    ln -sf "$here/$u" ~/.config/systemd/user/$u
done
systemctl --user daemon-reload
systemctl --user enable --now echo-desktop-sync.path echo-desktop-sync.timer
