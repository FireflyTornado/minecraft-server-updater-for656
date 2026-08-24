@echo off
chcp 65001 >nul
REM ── Minecraft Client Update Java Agent Build Script (Windows) ────
REM Usage: build.bat [--javafx]
REM   (default)  Swing UI only -- no JavaFX dependency.
REM   --javafx   Also compile the JavaFX view. Requires the JavaFX 21 runtime
REM              jars (javafx-base / javafx-graphics / javafx-controls, win
REM              classifier) in .\lib\javafx\. The same jars must be on the
REM              client JVM classpath at runtime; the agent falls back to the
REM              Swing view if the JavaFX implementation is missing.
REM Output: UpdateAgent.jar (launcher) + UpdateAgent_core.jar (core)
REM ──────────────────────────────────────────────────────────────────

setlocal
set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"
set "SRC_DIR=%SCRIPT_DIR%src"
set "JAVAFX_SRC_DIR=%SCRIPT_DIR%javafx"
set "JAVAFX_LIB_DIR=%SCRIPT_DIR%lib\javafx"
set "BUILD_DIR=%SCRIPT_DIR%build"
set "LAUNCHER_JAR=%SCRIPT_DIR%UpdateAgent.jar"
set "CORE_JAR=%SCRIPT_DIR%UpdateAgent_core.jar"

set "JAVAFX="
if "%~1"=="--javafx" (
    if not exist "%JAVAFX_LIB_DIR%\*.jar" (
        echo [build] ERROR: --javafx requested but no JavaFX jars in %JAVAFX_LIB_DIR%
        echo [build] Download javafx-base, javafx-graphics and javafx-controls
        echo [build] ^(version 21.0.4, win classifier^) from:
        echo [build]   https://repo1.maven.org/maven2/org/openjfx/
        echo [build] into that directory, then retry.
        exit /b 1
    )
    set "JAVAFX=1"
)

echo [build] Compiling...
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
if defined JAVAFX (
    javac -encoding UTF-8 -cp "lib\javafx\*" -d "%BUILD_DIR%" "%SRC_DIR%\*.java" "%JAVAFX_SRC_DIR%\*.java"
    if %ERRORLEVEL% neq 0 (
        echo [build] Compilation failed!
        exit /b 1
    )
    REM Bundle the JavaFX stylesheet so the JavaFX view can load /ui.css.
    copy /y "%JAVAFX_SRC_DIR%\ui.css" "%BUILD_DIR%\ui.css" >nul
) else (
    javac -encoding UTF-8 -d "%BUILD_DIR%" "%SRC_DIR%\*.java"
)
if %ERRORLEVEL% neq 0 (
    echo [build] Compilation failed!
    exit /b 1
)

echo [build] Packaging launcher JAR...
cd /d "%BUILD_DIR%"
jar cfm "%LAUNCHER_JAR%" "%SCRIPT_DIR%META-INF\MANIFEST.MF" Launcher.class
if %ERRORLEVEL% neq 0 (
    echo [build] Launcher JAR packaging failed!
    exit /b 1
)

echo [build] Packaging core JAR...
REM Temporarily exclude Launcher classes from core JAR
if exist Launcher.class ren Launcher.class Launcher.class.exclude
if exist ui.css (
    jar cf "%CORE_JAR%" *.class ui.css
) else (
    jar cf "%CORE_JAR%" *.class
)
if %ERRORLEVEL% neq 0 (
    echo [build] Core JAR packaging failed!
    exit /b 1
)
REM Restore Launcher classes
if exist Launcher.class.exclude ren Launcher.class.exclude Launcher.class

echo [build] Done!
echo [build] Launcher: %LAUNCHER_JAR%
echo [build] Core:     %CORE_JAR%

REM Clean up temp files
cd /d "%SCRIPT_DIR%"
rmdir /s /q "%BUILD_DIR%" 2>nul
endlocal
