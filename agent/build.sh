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
KEY_ARGS=()
if [[ -n "${MC_UPDATE_RELEASE_PUBLIC_KEY_FILE:-}" ]]; then
    mkdir -p "$BUILD_DIR/META-INF"
    cp "$MC_UPDATE_RELEASE_PUBLIC_KEY_FILE" "$BUILD_DIR/META-INF/mc-update-release-public-key.txt"
    KEY_ARGS=(META-INF/mc-update-release-public-key.txt)
else
    echo "[build] No release public key supplied. These JARs cannot start a game."
fi
cd "$BUILD_DIR"
jar "-J-Djava.io.tmpdir=$BUILD_DIR" cfm "$LAUNCHER_JAR" "$SCRIPT_DIR/META-INF/MANIFEST.MF" Launcher.class \
    com/zack88604/autoupdater/infrastructure/security/UpdaterRelease.class "${KEY_ARGS[@]}"

echo "[build] Packaging core JAR..."
# Keep the launcher out of the self-updatable core, but include the default
# package UpdateAgent compatibility facade and all named-package classes.
jar "-J-Djava.io.tmpdir=$BUILD_DIR" cf "$CORE_JAR" UpdateAgent.class com "${KEY_ARGS[@]}"

echo "[build] Done!"
echo "[build] Launcher: $LAUNCHER_JAR"
echo "[build] Core:     $CORE_JAR"

# Clean up temp class files
cd "$SCRIPT_DIR"
rm -rf "$BUILD_DIR"
