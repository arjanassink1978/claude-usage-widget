#!/usr/bin/env bash
# Build Claude Usage.app with PyInstaller, then wrap it in a DMG.
set -euo pipefail
cd "$(dirname "$0")"

APP_NAME="Claude Usage"
DIST_DIR="dist"
BUILD_DIR="build"

rm -rf "$DIST_DIR" "$BUILD_DIR"

pyinstaller \
  --name "$APP_NAME" \
  --windowed \
  --noconfirm \
  --add-data "../core.py:." \
  --paths .. \
  claude_usage_mac.py

APP_PATH="$DIST_DIR/$APP_NAME.app"
DMG_PATH="$DIST_DIR/ClaudeUsage.dmg"

rm -f "$DMG_PATH"
hdiutil create -volname "$APP_NAME" -srcfolder "$APP_PATH" -ov -format UDZO "$DMG_PATH"

echo "Built $DMG_PATH"
echo "Unsigned build — first launch needs right-click > Open to bypass Gatekeeper."
