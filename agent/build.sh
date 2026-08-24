#!/bin/bash
# ── Minecraft Client Update Java Agent Build Script (Linux/macOS) ──
# Usage: ./build.sh [--javafx]
#   (default)  Swing UI only — no JavaFX dependency.
#   --javafx   Also compile the JavaFX view. Requires the JavaFX 21 runtime
#              jars (javafx-base / javafx-graphics / javafx-controls, win
#              classifier) in ./lib/javafx/. The same jars must be on the
#              client JVM classpath at runtime; the agent falls back to the
#              Swing view if the JavaFX implementation is missing.
# Output: UpdateAgent.jar (launcher) + UpdateAgent_core.jar (core)
# ──────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"   # resolve relative paths below regardless of invocation directory
SRC_DIR="$SCRIPT_DIR/src"
JAVAFX_SRC_DIR="$SCRIPT_DIR/javafx"
JAVAFX_LIB_DIR="$SCRIPT_DIR/lib/javafx"
BUILD_DIR="$SCRIPT_DIR/build"
LAUNCHER_JAR="$SCRIPT_DIR/UpdateAgent.jar"
CORE_JAR="$SCRIPT_DIR/UpdateAgent_core.jar"

JAVAFX=0
if [[ "${1:-}" == "--javafx" ]]; then
    if [[ ! -d "$JAVAFX_LIB_DIR" ]] || ! ls "$JAVAFX_LIB_DIR"/*.jar > /dev/null 2>&1; then
        echo "[build] ERROR: --javafx requested but no JavaFX jars in $JAVAFX_LIB_DIR"
        echo "[build] Download javafx-base, javafx-graphics and javafx-controls"
        echo "[build] (version 21.0.4, win classifier) from:"
        echo "[build]   https://repo1.maven.org/maven2/org/openjfx/"
        echo "[build] into that directory, then retry."
        exit 1
    fi
    JAVAFX=1
fi

echo "[build] Compiling..."
mkdir -p "$BUILD_DIR"
if [[ $JAVAFX == 1 ]]; then
    javac -encoding UTF-8 -cp "lib/javafx/*" -d "$BUILD_DIR" "$SRC_DIR"/*.java "$JAVAFX_SRC_DIR"/*.java
    # Bundle the JavaFX stylesheet so the JavaFX view can load /ui.css.
    cp "$JAVAFX_SRC_DIR/ui.css" "$BUILD_DIR/ui.css"
    # Bundle the status illustrations so the JavaFX view can load /images/*.png.
    cp -r "$SCRIPT_DIR/images" "$BUILD_DIR/images"
else
    javac -encoding UTF-8 -d "$BUILD_DIR" "$SRC_DIR"/*.java
fi

echo "[build] Packaging launcher JAR..."
cd "$BUILD_DIR"
jar cfm "$LAUNCHER_JAR" "$SCRIPT_DIR/META-INF/MANIFEST.MF" Launcher.class

echo "[build] Packaging core JAR..."
# Temporarily exclude Launcher class from core JAR
if [ -f Launcher.class ]; then mv Launcher.class Launcher.class.exclude; fi
if [ -d images ]; then
    jar cf "$CORE_JAR" *.class ui.css images
else
    jar cf "$CORE_JAR" *.class
fi
# Restore Launcher class
if [ -f Launcher.class.exclude ]; then mv Launcher.class.exclude Launcher.class; fi

echo "[build] Done!"
echo "[build] Launcher: $LAUNCHER_JAR"
echo "[build] Core:     $CORE_JAR"

# Clean up temp class files
cd "$SCRIPT_DIR"
rm -rf "$BUILD_DIR"
