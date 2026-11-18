@echo off
chcp 65001 >nul
REM ── Minecraft Client Update Java Agent Build Script (Windows) ────
REM Usage: build.bat
REM Output: UpdateAgent.jar (launcher) + UpdateAgent_core.jar (core)
REM ──────────────────────────────────────────────────────────────────

setlocal DisableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
set "SRC_DIR=%SCRIPT_DIR%src"
set "BUILD_DIR=%SCRIPT_DIR%build"
set "LAUNCHER_JAR=%SCRIPT_DIR%UpdateAgent.jar"
set "CORE_JAR=%SCRIPT_DIR%UpdateAgent_core.jar"

echo [build] Compiling...
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"
pushd "%SRC_DIR%" || exit /b 1
(
    for /r %%f in (*.java) do (
        set "_src=%%f"
        setlocal EnableDelayedExpansion
        echo "!_src:\=/!"
        endlocal
    )
) > "%BUILD_DIR%\sources.txt"
popd
javac --release 17 -encoding UTF-8 -d "%BUILD_DIR%" @"%BUILD_DIR%\sources.txt"
if %ERRORLEVEL% neq 0 (
    echo [build] Compilation failed!
    exit /b 1
)

echo [build] Packaging launcher JAR...
set "KEY_ARG="
if defined MC_UPDATE_RELEASE_PUBLIC_KEY_FILE (
    mkdir "%BUILD_DIR%\META-INF"
    copy /y "%MC_UPDATE_RELEASE_PUBLIC_KEY_FILE%" "%BUILD_DIR%\META-INF\mc-update-release-public-key.txt" >nul
    if errorlevel 1 exit /b 1
    set "KEY_ARG=META-INF/mc-update-release-public-key.txt"
) else (
    echo [build] No release public key supplied. These JARs cannot start a game.
)
cd /d "%BUILD_DIR%"
del /q "sources.txt" 2>nul
jar "-J-Djava.io.tmpdir=%BUILD_DIR%" cfm "%LAUNCHER_JAR%" "%SCRIPT_DIR%META-INF\MANIFEST.MF" Launcher.class com/zack88604/autoupdater/infrastructure/security/UpdaterRelease.class %KEY_ARG%
if %ERRORLEVEL% neq 0 (
    echo [build] Launcher JAR packaging failed!
    exit /b 1
)

echo [build] Packaging core JAR...
REM Keep the launcher out of the self-updatable core, but include the default
REM package UpdateAgent compatibility facade and all named-package classes.
jar "-J-Djava.io.tmpdir=%BUILD_DIR%" cf "%CORE_JAR%" UpdateAgent.class com %KEY_ARG%
if %ERRORLEVEL% neq 0 (
    echo [build] Core JAR packaging failed!
    exit /b 1
)

echo [build] Done!
echo [build] Launcher: %LAUNCHER_JAR%
echo [build] Core:     %CORE_JAR%

REM Clean up temp files
cd /d "%SCRIPT_DIR%"
rmdir /s /q "%BUILD_DIR%" 2>nul
endlocal
