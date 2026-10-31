# 多平台 GUI 预设发布与迁移

新版核心按启动 Minecraft 所用 JVM 的操作系统、架构和 Java 版本选择预设。
核心与 JavaFX helper 均需要 Java 17 或更高版本。支持的发布目标为：

| 平台标识 | JVM 环境 | FireflyTornado 包后缀 |
|----------|----------|----------------------|
| `windows-x64` | Windows x64 | `win.jar` |
| `linux-x64` | Linux x64 | `linux.jar` |
| `macos-x64` | macOS x64 | `mac.jar` |
| `macos-arm64` | macOS ARM64 | `mac-aarch64.jar` |

`amd64` / `x86_64` / `x64` 会归一化为 x64，`aarch64` / `arm64` 会归一化为 ARM64。
Apple Silicon 上使用 x64 Java 时选择 macOS x64 包。未知系统、32 位 JVM 和没有
匹配变体的环境使用内建 Swing，普通资源更新流程继续执行。

## 发布配置

先在新版 [FireflyTornado JavaFX UI Preset](https://github.com/FireflyTornado/UI-preset-for-Zack88604-Minecraft-server-updater)
项目根目录执行 `build.bat` 重新构建，将需要发布的产物复制到服务端
`/srv/mc-update/gui-presets/`。旧产物若缺少平台元数据，可继续作为旧接口的单包发布，
不能直接配置成新版平台变体。

在现有 `update-config.json` 中合并以下 `generation` 配置，保留其他同步及维护设置：

```json
{
  "generation": {
    "targets": ["manifest", "gui-preset"],
    "gui_preset": {
      "id": "fireflytornado-javafx",
      "version": "1.2.4",
      "file": "fireflytornado-javafx-preset-1.2.4-win.jar",
      "variants": {
        "windows-x64": "fireflytornado-javafx-preset-1.2.4-win.jar",
        "linux-x64": "fireflytornado-javafx-preset-1.2.4-linux.jar",
        "macos-x64": "fireflytornado-javafx-preset-1.2.4-mac.jar",
        "macos-arm64": "fireflytornado-javafx-preset-1.2.4-mac-aarch64.jar"
      }
    }
  }
}
```

只发布 Windows 时，`variants` 只保留 `windows-x64` 一项，其他三个包无需上传。
只有配置中声明的文件才是发布必需项；声明四个平台却只上传一个，会导致此次发布失败，
上一份有效清单保持不变。`variants` 不允许为空对象或 `null`。

`file` 是可选的旧客户端单包入口，不会被新版客户端当成“找不到匹配平台时的默认包”。
不需要旧客户端兼容时可以省略它；只保留 `file` 而不配置 `variants`，则维持旧版单包行为。
保持原来的 `id` 可沿用已批准的服务器预设身份，不必为了新平台更换 ID。

服务端自动计算哈希和大小，从每个包的运行时清单读取最低 Java 版本，并检查包内版本号、
系统和架构与配置相符。全部已配置文件验证成功后，以一次原子替换发布 `gui-preset.json`。
沿用现有 `POST /api/generate` 和 `X-Generate-Token`；没有上传代码的新接口。

客户端 `mc-update.properties` 中启用：

```properties
server-gui=recommended
```

`disabled` 仍为默认值；`recommended` 尊重已记住的本地选择；`required` 优先使用服务器
预设，也仍需首次信任确认。两种启用策略下，没有兼容变体或 helper 启动失败均回退 Swing。
自动回退不会保存成用户主动选择的 Swing 默认值，后续发布兼容包后可以重新使用预设。

## 接口与兼容性

`GET /api/v3/gui-preset` 返回：

```json
{
  "id": "fireflytornado-javafx",
  "version": "1.2.4",
  "variants": {
    "windows-x64": {
      "os": "windows",
      "arch": "x64",
      "minimum_java_version": 17,
      "path": "/api/v2/gui-presets/fireflytornado-javafx-preset-1.2.4-win.jar",
      "sha256": "由服务端生成的64位十六进制哈希",
      "size": 8830000
    }
  }
}
```

上例哈希和大小仅用于说明字段，实际值由服务端生成。所有下载继续使用
`/api/v2/gui-presets/<filename>`。客户端按对象层级验证完整 JSON，下载后校验大小和哈希，
并再次检查 JAR 自身的平台、版本和最低 Java 版本，通过后才替换本地缓存。

| 客户端 / 服务端 | 行为 |
|------------------|------|
| 新客户端 / 多平台发布 | 读取 v3，精确选择变体；不匹配或 Java 版本不足则 Swing |
| 新客户端 / 旧服务端或仅配置 `file` | v3 返回 404/405 时尝试 v2，保留旧预设兼容行为 |
| 旧客户端 / 新服务端 | 继续读取 v2 的 `file`；不具备平台识别能力 |
| 新客户端 / v3 无效或返回 503、下载失败 | 不向同一服务器的 v2 降级；可尝试其他已配置源 |

未发布预设或没有旧式 `file` 时，对应接口返回 404。已有多平台发布若被改坏或所引用文件
发生变化，v3 返回 503，不会把发布损坏伪装成旧接口兼容。完整 v3 清单缺少当前平台时，
也不会再通过同一服务器的 v2 下载 Windows 包。

## 本地缓存与预设元数据

服务端预设统一保存在 `.mc-update/gui-presets/server-<id>.jar`，该名称保留给更新器。
版本更新或 JVM 平台变化时，继续在同一文件路径校验、下载并原子替换对应的包；
服务端各平台发布文件的名称和下载地址保持不变。早期测试版带平台后缀的缓存文件不自动
迁移或清理。helper 解压目录继续按整个 JAR 的 SHA-256 隔离，保留现有清理策略。
已批准的服务器 URL 与 preset ID 保持信任，更换服务器 URL 或 ID 仍需重新确认。

JAR 内 `META-INF/mc-update-gui.properties` 增加可选字段：

```properties
target-os=windows
target-arch=x64
```

两个字段必须同时提供，规范值分别为 `windows` / `linux` / `macos` 和 `x64` / `arm64`。
本地选择器会过滤不兼容目标及 Java 版本不足的包。没有这两个字段的旧预设保留旧兼容路径，
不根据文件名猜测平台；它们仍可能因原生依赖不兼容而在启动时回退 Swing。

建议依次发布：新版 preset 包 → 支持新旧接口的服务端 → 新版核心。多平台打包与协议测试
不能代替各系统上的窗口实测。

## 验证

安装 `server/requirements.txt` 中已有的依赖，使用 JDK 17+，在项目根目录运行：

```powershell
./validation/run.ps1
```

测试使用临时数据和本机 HTTP 服务，不连接生产服务器，不打开 GUI。
如需同时验证真实构建产物的发布和 Java 下载链路，可在运行脚本前设置：

```powershell
$env:MC_UPDATE_PRESET_DIST = 'C:\path\to\preset\dist'
./validation/run.ps1
```
