# Minecraft Auto Update Service

> Keep Minecraft client resources in sync across machines — via a self-hosted HTTP API and a Java agent.

[中文文档](./README_CN.md)

## How It Works

| Component | Role |
|-----------|------|
| **Server** (Python/Flask, Docker) | Hosts file manifests & resource downloads via REST API. |
| **Agent** (Java, `-javaagent`) | Loaded at Minecraft startup — checks for updates, shows GUI progress, syncs files, then lets the game launch. |

The agent is split into two JARs for safe self-updating:

| JAR | Role |
|-----|------|
| `UpdateAgent.jar` (Launcher) | Thin wrapper loaded by `-javaagent`. Replaces the core JAR at startup, then delegates to it. **Never updated**, so no file-lock issues. |
| `UpdateAgent_core.jar` (Core) | The actual update logic: HTTP sync, GUI, file cleanup. **Can be self-updated** — a new version is downloaded as `.jar.new` and swapped in on next launch. |

```
Minecraft Launch → Launcher → (swap core JAR if .new exists) → Core Agent (GUI) → HTTP → Server → Sync files → Game starts
```

## Quick Start

### Server

```bash
# Build from the parent directory
docker build -t mc-update-service -f Dockerfile .

# Run with file storage mounted
docker run -d -p 25565:25565 -v /path/to/files:/data/files -v /path/to/agent:/data/agent --name mc-update mc-update-service

# Place UpdateAgent_core.jar in the agent directory
cp UpdateAgent_core.jar /path/to/agent/

# Generate manifest after placing files under /data/files
docker exec mc-update python3 /app/generate_manifest.py --dir /data/files --out /data --agent-jar /data/agent/UpdateAgent_core.jar
```

### Agent

```bash
cd agent
./build.sh                              # or build.bat on Windows
./setup-agent.sh ~/.minecraft/versions/1.20.1 http://your-server:25565
```

The setup script writes server configuration to `mc-update.properties` in the game directory, and appends `-javaagent:<path>/UpdateAgent.jar` to the launcher's JVM arguments.

## API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v2/manifest` | GET | Full file manifest (paths, SHA-256, sizes) |
| `/api/files/<path>` | GET | Download a resource file |
| `/api/agent` | GET | Download the latest `UpdateAgent_core.jar` |
| `/api/config` | GET | Managed paths & excluded paths configuration |
| `/api/generate` | POST | Regenerate manifest (token-protected) |
| `/api/health` | GET | Health check |

## Configuration

### Server (env vars)

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `25565` | HTTP port |
| `GENERATE_TOKEN` | *(empty)* | Protects `/api/generate` |
| `DEBUG` | `false` | Flask debug mode |

### Agent (JVM properties)

Configuration is resolved in this order (normal mode):

1. `mc-update.properties` in the game directory *(written by setup script)*
2. Inline `-javaagent` arguments
3. `-D` system properties
4. Built-in defaults

| Property | Default | Description |
|----------|---------|-------------|
| `mc-update.server` | `http://localhost:25565` | Server URL(s) — comma-separated for **multi-source fallback** |
| `mc-update.game-dir` | `.` | Minecraft directory |
| `mc-update.debug` | `false` | Keep GUI open after sync |
| `mc-update.ui` | `swing` | UI toolkit: `swing` (default) or `javafx` (experimental parallel view) |

**Recommended: `mc-update.properties`** (written by setup script):
```properties
server=http://cdn1.example.com:25565,http://cdn2.example.com:8443
```

**Inline agent args**:
```
-javaagent:UpdateAgent.jar=server=http://1.2.3.4:25565,game-dir=C:\mc,debug=true
```

**Multi-server fallback** (automatically tries next server on failure):
```
-javaagent:UpdateAgent.jar=server=http://cdn1.example.com:25565,http://cdn2.example.com:8443
```

**Admin mode** (`admin=true`) reverses config priority: agent args > system props > config file — useful for one-off overrides:
```
-javaagent:UpdateAgent.jar=admin=true,server=http://override:25565
```

### JavaFX UI (experimental)

The update window can also be rendered with JavaFX instead of Swing. This is a
functionally-correct parallel implementation of the same toolkit-agnostic
`UpdateView` contract, living in `agent/javafx/`. It is not the default yet.

To use it:

1. **Build** the core JAR with the JavaFX view:
   ```bash
   cd agent
   ./build.sh --javafx        # or build.bat --javafx on Windows
   ```
   This needs the JavaFX 21 runtime jars (`javafx-base`, `javafx-graphics`,
   `javafx-controls`, win classifier) in `agent/lib/javafx/` — the build prints
   the download location if they are missing.
2. **Switch** the entry layer to JavaFX with `mc-update.ui=javafx`, resolved
   with the same precedence as the other `mc-update.*` properties (config file
   > agent args > system properties > default). Example:
   ```
   mc-update.ui=javafx
   ```
   The default `swing` keeps using the existing Swing view.
3. **Run**: the JavaFX jars must also be on the client JVM's classpath (e.g.
   add `agent/lib/javafx/*` to the launch JVM arguments alongside
   `-javaagent`). If the JavaFX implementation is absent or cannot start,
   `UpdateAgent` logs a warning and falls back to the Swing view.

### Selective Sync (`update-config.json`)

```json
{
  "managed_paths": ["mods/", "config/", "resourcepacks/", "options.txt"],
  "excluded_paths": ["config/secret.cfg", "mods/skip_this/"]
}
```

Paths ending with `/` match directories recursively; bare names match exact files. `excluded_paths` override `managed_paths` — excluded files are neither synced nor cleaned up. Default: `managed_paths: ["*"]`, `excluded_paths: []`.

## Project Structure

```
├── Dockerfile
├── LICENSE
├── README.md
├── README_CN.md
├── server/
│   ├── app.py                  # Flask API (manifest, files, agent, config, health)
│   ├── entrypoint.sh           # Container entrypoint
│   ├── generate_manifest.py    # Scans files, computes SHA-256, writes manifest JSON
│   └── requirements.txt
└── agent/
    ├── META-INF/MANIFEST.MF   # Premain-Class: Launcher
    ├── src/
    │   ├── Launcher.java           # -javaagent entry; swaps core JAR from .new, then loads it
    │   ├── UpdateAgent.java        # Core entry (premain): config resolution + update flow
    │   ├── UpdateApplication.java  # Composition root: wires service + view + controller; holds no flow decisions
    │   ├── UpdateController.java   # Controller/flow layer: coordinates service + view + app flow; decides start/success/failure/close/delay/latch release
    │   ├── UpdateService.java      # Update logic: manifest, hashing, download, cleanup, self-update; emits UpdateEvents
    │   ├── UpdateEvent.java        # Unified business event model (no Swing dependency)
    │   ├── UpdatePhase.java        # Shared visual phase enum for the update flow (toolkit-agnostic)
    │   ├── UpdateListener.java     # Business→UI event callback interface (no Swing dependency)
    │   ├── UpdateView.java         # Toolkit-agnostic UI contract (open/close/status/...; no Swing/JavaFX types)
    │   ├── UpdateViewListener.java # View→controller user-action callback (window close / debug close)
    │   ├── UpdateGUI.java          # Swing UI (status, progress, log, speed); implements UpdateView; opened/closed by the application flow
    │   ├── UiModel.java            # Immutable display data handed to the UI
    │   ├── UiDispatcher.java       # "Run on UI thread" abstraction over the UI toolkit
    │   ├── SwingUiDispatcher.java  # UiDispatcher backed by Swing's EDT
    │   ├── UpdateResult.java       # Update outcome: updated / failed counts
    │   ├── ServerClient.java       # HTTP client with multi-server fallback
    │   ├── FileManager.java        # Path-safety, SHA-256, atomic replace, stale-file cleanup
    │   ├── Manifest.java           # Parsed manifest model (files + managed/excluded paths + agent)
    │   ├── FileEntry.java          # Single manifest file entry (path, hash, size)
    │   ├── DownloadProgress.java   # Per-file download progress snapshot (worker ↔ UI)
    │   ├── JsonParser.java         # Lightweight JSON parsing helpers (no external deps)
    │   └── FormatUtil.java         # Formatting helpers (e.g. download speed)
    ├── javafx/                 # JavaFX UI — parallel impl of UpdateView (built only with --javafx)
    │   ├── JavaFxEntryPoint.java    # JavaFX composition root (reached reflectively from UpdateAgent)
    │   ├── JavaFxUiDispatcher.java  # UiDispatcher backed by Platform.runLater
    │   ├── JavaFxUpdateView.java    # JavaFX view implementing UpdateView (six phases)
    ├── build.sh / build.bat    # Compile + package both JARs (--javafx adds the JavaFX view)
    └── setup-agent.sh / setup-agent.bat  # Write config + append -javaagent to JVM args
```

Build output:
- `UpdateAgent.jar` — Launcher JAR (loaded by `-javaagent`)
- `UpdateAgent_core.jar` — Core agent JAR (loaded dynamically)

## License

MIT
