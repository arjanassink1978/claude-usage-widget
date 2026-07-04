#!/usr/bin/env bash
# Build a native installer with jpackage: .dmg on macOS, .exe/.msi on Windows.
set -euo pipefail
cd "$(dirname "$0")"

mvn -q -DskipTests package

OS_NAME="$(uname -s 2>/dev/null || echo Windows)"
case "$OS_NAME" in
  Darwin) TYPE="dmg" ;;
  MINGW*|MSYS*|CYGWIN*|Windows*) TYPE="exe" ;;
  *) TYPE="app-image" ;;
esac

rm -rf installer
jpackage \
  --input target \
  --main-jar "$(basename "$(ls target/claude-usage-widget-*.jar | grep -v original)")" \
  --main-class com.claudeusage.TrayApp \
  --name "Claude Usage" \
  --app-version 1.0.0 \
  --type "$TYPE" \
  --dest installer \
  --vendor "claude-usage-widget"

echo "Built installer in installer/ (type: $TYPE)"
