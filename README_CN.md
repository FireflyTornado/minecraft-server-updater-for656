# Minecraft 自动更新服务

> 通过自托管 HTTP API 与 Java Agent，在多台机器间保持 Minecraft 客户端资源同步更新。

[English](./README.md)

## 工作原理

| 组件 | 职责 |
|------|------|
| **服务端** (Python/Flask, Docker) | 托管文件清单与资源下载的 REST API。 |
| **Agent** (Java, `-javaagent`) | Minecraft 启动时加载 — 检查更新、显示 GUI 进度、同步文件，完成后启动游戏。 |

Agent 分为两个 JAR，实现安全的自更新：

| JAR | 职责 |
|-----|------|
| `UpdateAgent.jar`（启动器） | 由 `-javaagent` 加载的薄封装层。启动时替换核心 JAR，然后委托给核心逻辑。**永不更新**，因此无文件锁问题。 |
| `UpdateAgent_core.jar`（核心） | 实际的更新逻辑：HTTP 同步、GUI、文件清理。**可自更新** — 新版本下载为 `.jar.new`，下次启动时替换。 |

```
Minecraft 启动 → 启动器 → （如有 .new 则替换核心 JAR）→ 核心 Agent (GUI) → HTTP 请求 → 服务器 → 同步文件 → 游戏启动
```

## 快速开始

### 服务端

```bash
# 从上级目录构建
docker build -t mc-update-service -f Dockerfile .

# 挂载文件存储目录运行
docker run -d -p 25565:25565 -v /path/to/files:/data/files -v /path/to/agent:/data/agent --name mc-update mc-update-service

# 将 UpdateAgent_core.jar 放入 agent 目录
cp UpdateAgent_core.jar /path/to/agent/

# 将资源文件放入 /data/files 后生成清单
docker exec mc-update python3 /app/generate_manifest.py --dir /data/files --out /data --agent-jar /data/agent/UpdateAgent_core.jar
```

### Agent

```bash
cd agent
./build.sh                              # Windows 用 build.bat
./setup-agent.sh ~/.minecraft/versions/1.20.1 http://your-server:25565
```

安装脚本会将服务器配置写入游戏目录下的 `mc-update.properties`，并向启动器 JVM 参数追加 `-javaagent:<path>/UpdateAgent.jar`。

## API

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/v2/manifest` | GET | 完整文件清单（路径、SHA-256、大小） |
| `/api/files/<path>` | GET | 下载指定资源文件 |
| `/api/agent` | GET | 下载最新 `UpdateAgent_core.jar` |
| `/api/config` | GET | 管理路径与排除路径配置 |
| `/api/generate` | POST | 重新生成清单（Token 保护） |
| `/api/health` | GET | 健康检查 |

## 配置

### 服务端（环境变量）

| 变量 | 默认值 | 描述 |
|------|--------|------|
| `PORT` | `25565` | HTTP 端口 |
| `GENERATE_TOKEN` | *(空)* | 保护 `/api/generate` 接口 |
| `DEBUG` | `false` | Flask 调试模式 |

### Agent（JVM 属性）

配置优先级（普通模式）如下：

1. 游戏目录下的 `mc-update.properties` *（由安装脚本写入）*
2. 内联 `-javaagent` 参数
3. `-D` 系统属性
4. 内置默认值

| 属性 | 默认值 | 描述 |
|------|--------|------|
| `mc-update.server` | `http://localhost:25565` | 服务器地址 — 支持**逗号分隔多源**，自动故障转移 |
| `mc-update.game-dir` | `.` | Minecraft 目录 |
| `mc-update.debug` | `false` | 同步完成后保持窗口打开 |

**推荐方式：`mc-update.properties`**（由安装脚本写入）：
```properties
server=http://cdn1.example.com:25565,http://cdn2.example.com:8443
```

**内联 agent 参数**：
```
-javaagent:UpdateAgent.jar=server=http://1.2.3.4:25565,game-dir=C:\mc,debug=true
```

**多源故障转移**（某源不可用时自动切换）：
```
-javaagent:UpdateAgent.jar=server=http://cdn1.example.com:25565,http://cdn2.example.com:8443
```

**管理员模式**（`admin=true`）反转配置优先级：agent 参数 > 系统属性 > 配置文件 — 适用于一次性覆盖：
```
-javaagent:UpdateAgent.jar=admin=true,server=http://override:25565
```

### 选择性同步 (`update-config.json`)

```json
{
  "managed_paths": ["mods/", "config/", "resourcepacks/", "options.txt"],
  "excluded_paths": ["config/secret.cfg", "mods/skip_this/"]
}
```

以 `/` 结尾匹配目录（递归），否则精确匹配文件。`excluded_paths` 优先级高于 `managed_paths` — 被排除的文件既不同步也不清理。默认值：`managed_paths: ["*"]`，`excluded_paths: []`。

## 项目结构

```
├── Dockerfile
├── LICENSE
├── README.md
├── README_CN.md
├── server/
│   ├── app.py                  # Flask API（清单、文件、Agent、配置、健康检查）
│   ├── entrypoint.sh           # 容器入口
│   ├── generate_manifest.py    # 扫描文件、计算 SHA-256、生成清单 JSON
│   └── requirements.txt
└── agent/
    ├── META-INF/MANIFEST.MF   # Premain-Class: Launcher
    ├── src/
    │   ├── Launcher.java           # -javaagent 入口；用 .new 替换核心 JAR 并动态加载
    │   ├── UpdateAgent.java        # 核心入口（premain）：配置解析 + 更新流程
    │   ├── UpdateApplication.java  # 组合根：串联服务、视图与控制器；不持有任何流程决策
    │   ├── UpdateController.java   # 控制器/流程层：协调服务、视图与应用流程；决定启动/成功/失败/关闭/延迟/释放 latch
    │   ├── UpdateService.java      # 更新逻辑：清单、哈希校验、下载、清理、自更新；发出 UpdateEvent
    │   ├── UpdateEvent.java        # 统一的业务事件模型（不依赖 Swing）
    │   ├── UpdateListener.java     # 业务层 → 界面事件回调接口（不依赖 Swing）
    │   ├── UpdateView.java         # 与 UI 工具包无关的视图契约（open/close/状态等；不含 Swing/JavaFX 类型）
    │   ├── UpdateViewListener.java # 视图 → 控制器的用户操作回调（关闭窗口 / 调试关闭按钮）
    │   ├── UpdateGUI.java          # Swing 界面（状态、进度、日志、速度）；实现 UpdateView；由应用流程打开/关闭
    │   ├── UiModel.java            # 传给界面的不可变展示数据
    │   ├── UiDispatcher.java       # 对 UI 工具包「在 UI 线程执行」的抽象
    │   ├── SwingUiDispatcher.java  # 基于 Swing EDT 的 UiDispatcher 实现
    │   ├── UpdateResult.java       # 更新结果：updated / failed 计数
    │   ├── ServerClient.java       # 支持多源故障转移的 HTTP 客户端
    │   ├── FileManager.java        # 路径安全检查、SHA-256、原子替换、过期文件清理
    │   ├── Manifest.java           # 解析后的清单模型（文件 + 管理/排除路径 + Agent）
    │   ├── FileEntry.java          # 清单中的单个文件条目（路径、哈希、大小）
    │   ├── DownloadProgress.java   # 单文件下载进度快照（工作线程 ↔ 界面）
    │   ├── JsonParser.java         # 轻量 JSON 解析辅助（无外部依赖）
    │   └── FormatUtil.java         # 格式化辅助（如下载速度）
    ├── build.sh / build.bat    # 编译并打包两个 JAR
    └── setup-agent.sh / setup-agent.bat  # 写入配置并追加 -javaagent 到 JVM 参数
```

构建产物：
- `UpdateAgent.jar` — 启动器 JAR（由 `-javaagent` 加载）
- `UpdateAgent_core.jar` — 核心 Agent JAR（动态加载）

## 许可证

MIT
