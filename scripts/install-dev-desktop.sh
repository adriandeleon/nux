#!/usr/bin/env bash
# Registers a desktop entry for a *development* run (mvn javafx:run) so the window
# gets the Nux icon instead of a generic one.
#
# Desktops identify an app by matching the window's WM_CLASS (com.nux.App) against a
# .desktop file's StartupWMClass. Stage.getIcons() only sets _NET_WM_ICON, which
# Wayland ignores and GNOME deprioritises — so without an entry there is nothing to
# match and you get the generic icon. Installed packages ship their own entry
# (packaging/linux/Nux.desktop); this is only for running from the source tree.
#
# Usage: scripts/install-dev-desktop.sh [--uninstall]
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ENTRY="$DEST/nux-dev.desktop"

if [[ "${1:-}" == "--uninstall" ]]; then
    rm -f "$ENTRY"
    update-desktop-database "$DEST" 2>/dev/null || true
    echo "Removed $ENTRY"
    exit 0
fi

ICON="$REPO/branding/nux-256.png"
if [[ ! -f "$ICON" ]]; then
    echo "Icon missing — run: java scripts/gen_icon.java" >&2
    exit 1
fi

mkdir -p "$DEST"
cat > "$ENTRY" <<EOF
[Desktop Entry]
Name=Nux (dev)
Comment=NUT (Network UPS Tools) client — development build
Exec=sh -c 'cd "$REPO" && mvn -q javafx:run'
Icon=$ICON
Terminal=false
Type=Application
Categories=System;Monitor;Utility;
StartupWMClass=com.nux.App
EOF

update-desktop-database "$DEST" 2>/dev/null || true
echo "Installed $ENTRY"
echo "Restart Nux for the desktop to match the window to this entry."
