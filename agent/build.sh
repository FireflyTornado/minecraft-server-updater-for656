#!/bin/bash
# ── Minecraft Client Update Java Agent Build Script (Linux/macOS) ──
# Usage: ./build.sh
# Output: UpdateAgent.jar (launcher) + UpdateAgent_core.jar (core)
# ──────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
LAUNCHER_JAR="$SCRIPT_DIR/UpdateAgent.jar"
CORE_JAR="$SCRIPT_DIR/UpdateAgent_core.jar"

echo "[build] Compiling..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
find "$SRC_DIR" -type f -name '*.java' -print0 | xargs -0 javac --release 17 -encoding UTF-8 -d "$BUILD_DIR"

echo "[build] Packaging launcher JAR..."
cd "$BUILD_DIR"
# Normalize the manifest and entry timestamps so identical inputs yield identical JARs.
mkdir META-INF
tr -d '\r' < "$SCRIPT_DIR/META-INF/MANIFEST.MF" > META-INF/MANIFEST.MF
TZ=UTC touch -t 200001010000.00 META-INF/MANIFEST.MF Launcher.class
jar -J-Djava.io.tmpdir=. -J-Duser.timezone=UTC --create --file "$LAUNCHER_JAR" \
    --no-manifest --no-compress META-INF/MANIFEST.MF Launcher.class

# Record the hash of the packaged launcher in the matching core.
if command -v sha256sum >/dev/null 2>&1; then
    launcher_hash=$(sha256sum < "$LAUNCHER_JAR")
else
    launcher_hash=$(shasum -a 256 < "$LAUNCHER_JAR")
fi
printf 'Manifest-Version: 1.0\nBuilt-Launcher-SHA-256: %s\n\n' "${launcher_hash%% *}" > core-manifest.mf

echo "[build] Packaging core JAR..."
# Keep the launcher out of the self-updatable core, but include the default
# package UpdateAgent compatibility facade and all named-package classes.
jar -J-Djava.io.tmpdir=. cfm "$CORE_JAR" core-manifest.mf UpdateAgent.class LauncherIntegrity.class com

echo "[build] Done!"
echo "[build] Launcher: $LAUNCHER_JAR"
echo "[build] Core:     $CORE_JAR"

# Clean up temp class files
cd "$SCRIPT_DIR"
rm -rf "$BUILD_DIR"
