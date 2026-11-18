@echo off
chcp 65001 >nul
REM ── Minecraft Client Update Java Agent Build Script (Windows) ────
REM Usage: build.bat
REM Output: UpdateAgent.jar (launcher) + UpdateAgent_core.jar (core)
REM ──────────────────────────────────────────────────────────────────

setlocal
set "SCRIPT_DIR=%~dp0"
set "SRC_DIR=%SCRIPT_DIR%src"
set "BUILD_DIR=%SCRIPT_DIR%build"
set "LAUNCHER_JAR=%SCRIPT_DIR%UpdateAgent.jar"
set "CORE_JAR=%SCRIPT_DIR%UpdateAgent_core.jar"

echo [build] Compiling...
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"
dir /s /b "%SRC_DIR%\*.java" > "%BUILD_DIR%\sources.txt"
javac --release 17 -encoding UTF-8 -d "%BUILD_DIR%" @"%BUILD_DIR%\sources.txt"
if %ERRORLEVEL% neq 0 (
    echo [build] Compilation failed!
    exit /b 1
)

echo [build] Packaging launcher JAR...
cd /d "%BUILD_DIR%"
del /q "sources.txt" 2>nul
REM Keep the launcher hash stable by normalizing the manifest and entry timestamps.
powershell.exe -NoProfile -NonInteractive -Command ^
    "$ErrorActionPreference = 'Stop';" ^
    "$manifest = [IO.File]::ReadAllText((Join-Path $env:SCRIPT_DIR 'META-INF/MANIFEST.MF'));" ^
    "[IO.Directory]::CreateDirectory('META-INF') | Out-Null;" ^
    "[IO.File]::WriteAllText('META-INF/MANIFEST.MF', $manifest.Replace([Environment]::NewLine, [string][char]10));" ^
    "$stamp = [DateTime]::new(2000, 1, 1, 0, 0, 0, [DateTimeKind]::Utc);" ^
    "foreach ($entry in 'META-INF/MANIFEST.MF', 'Launcher.class') { [IO.File]::SetLastWriteTimeUtc($entry, $stamp) }"
if errorlevel 1 (
    echo [build] Launcher JAR preparation failed!
    exit /b 1
)
jar -J-Djava.io.tmpdir=. -J-Duser.timezone=UTC --create --file "%LAUNCHER_JAR%" --no-manifest --no-compress META-INF/MANIFEST.MF Launcher.class
if %ERRORLEVEL% neq 0 (
    echo [build] Launcher JAR packaging failed!
    exit /b 1
)

REM Record the hash of the packaged launcher in the matching core.
powershell.exe -NoProfile -NonInteractive -Command ^
    "$ErrorActionPreference = 'Stop';" ^
    "$sha256 = [Security.Cryptography.SHA256]::Create();" ^
    "try { $digest = [BitConverter]::ToString($sha256.ComputeHash([IO.File]::ReadAllBytes($env:LAUNCHER_JAR))).Replace('-', '').ToLowerInvariant() } finally { $sha256.Dispose() };" ^
    "[IO.File]::WriteAllLines('core-manifest.mf', @('Manifest-Version: 1.0', ('Built-Launcher-SHA-256: ' + $digest), ''))"
if errorlevel 1 (
    echo [build] Core manifest generation failed!
    exit /b 1
)

echo [build] Packaging core JAR...
REM Keep the launcher out of the self-updatable core, but include the default
REM package UpdateAgent compatibility facade and all named-package classes.
jar -J-Djava.io.tmpdir=. cfm "%CORE_JAR%" core-manifest.mf UpdateAgent.class LauncherIntegrity.class com
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
