# KokonaHarness

把 DeepSeek Harness（DSH）装进 Android 手机的原生运行器。App 不依赖 PRoot、Ubuntu 容器或 Termux 安装，直接以 Android bionic 的 Node.js 运行完整 DSH 引擎，WebView 加载 DSH Web UI，并通过原生桥接把手机能力暴露给模型。

当前版本：**1.2.0**（versionCode 120），内置运行时 **dsh-0.2.2-rc.1-r28**。

## 整体架构

```
┌─────────────────────────────────────────────────────────┐
│  WebView: DSH Web UI（0.2.2-rc.1 + KokonaHarness 定制）  │
│  设置页 / 会话页 / 侧栏 / 插件市场 / 终端标签            │
└───────────────┬─────────────────────────────────────────┘
                │ KokonaBridge (JavaScriptInterface)
┌───────────────▼─────────────────────────────────────────┐
│  Android 壳（Kotlin/Java）                               │
│  MainActivity · EngineService · RuntimeInstaller         │
│  LocalBridgeServer (loopback HTTP + token)               │
│  ConfirmationGate · AndroidPermissions · RunMode         │
│  Accessibility / Notification / Media / Screenshot 服务  │
└───────────────┬─────────────────────────────────────────┘
                │ loopback HTTP / stdio / MCP
┌───────────────▼─────────────────────────────────────────┐
│  内置运行时（app 私有目录）                               │
│  node (Termux bionic arm64) + bash + git/ripgrep/…        │
│  node_modules: @deepseek-ai/dsh 及全部官方依赖            │
│  runtime-skills: android-capabilities / python-runtime    │
│  knhplus-mcp: KNHPlus MCP server（手机能力工具集）         │
└─────────────────────────────────────────────────────────┘
```

### 关键设计

- **无容器**：Node 直接跑在 bionic 上，启动快、体积小、无 ptrace 开销；代价是 glibc 预编译包不可用（见“已知限制”）。
- **薄原生桥**：Java 侧只做 Android 特有的事（无障碍、通知读取、Shizuku/Root、截图、剪贴板、震动、TTS），其余全部由 DSH 插件与 MCP 完成。
- **能力粒度授权**：授权状态由 `AndroidPermissions` 持久化到 SharedPreferences（`kokona_permissions`），九项能力各自可选 `allow` / `ask` / `deny`。MCP 每次调用通过 `/policy` 实时读取，模型只能读、不能改；设置页负责展示与修改。
- **确认闸门**：`ask` 能力触发 `ConfirmationGate` 原生弹窗，45 秒无响应按拒绝处理，App 不可见时不弹窗并提示先打开 App。
- **运行时原子替换**：`RuntimeInstaller` 负责解包、校验与目录切换，失败可回退；引擎启动带 watchdog。

## 目录结构

```
KokonaHarness/
├── app/
│   ├── AndroidManifest.xml        # versionCode 120 / versionName 1.2.0
│   ├── java/com/knh/akizukikokona/
│   │   ├── MainActivity.java      # WebView、KokonaBridge、生命周期
│   │   ├── EngineService.java     # 前台服务、引擎进程管理
│   │   ├── RuntimeInstaller.java  # 运行时解包/校验/替换（r28）
│   │   ├── LocalBridgeServer.java # loopback HTTP：/policy /confirm /screenshot …
│   │   ├── AndroidPermissions.java# 九项能力授权持久化
│   │   ├── ConfirmationGate.java  # 原生确认弹窗
│   │   ├── AndroidCapabilities.java # 无障碍/通知/媒体/剪贴板等实现
│   │   ├── RunMode.java           # 普通 / Shizuku / Root
│   │   ├── ScreenshotAccessibilityService.java
│   │   └── NotificationAccessService.java
│   ├── res/                       # 图标、无障碍说明等
│   ├── libs/                      # Shizuku、xz.jar
│   └── assets/
│       ├── runtime.tar.xz         # 构建产物（不入库）
│       └── seed/                  # DSH profile 种子
├── knhplus-mcp/                   # KNHPlus MCP server（server.mjs）
├── runtime-skills/                # 随运行时下发的技能说明
│   ├── android-capabilities/
│   └── python-runtime/
├── branding/                      # 品牌矢量与说明
├── assets-src/                    # 背景等源资源
├── build-runtime.sh               # 组装 arm64 运行时快照
├── build-nodepty.sh               # 交叉编译 node-pty（NDK + Termux 头文件）
├── build.sh                       # aapt2 + javac + d8 + zipalign + apksigner
├── runtime-patches/               # 运行时定制补丁存档（当前：设置页）
└── README.md
```

DSH UI 的定制补丁主要打在 `node_modules/@deepseek-ai/...` 的构建树上，构建时由 `build-runtime.sh` 一起打包；设置页补丁同时存档在 `runtime-patches/`，其余清单见本文末“运行时定制”。

## 构建

```bash
# 1) 准备 /workspace/build/rt/node_modules（npm 安装 @deepseek-ai/dsh 后打入定制补丁）
# 2) 交叉编译 Android PTY（NDK 27.2 + Termux node 头文件）
./build-nodepty.sh

# 3) 组装运行时（产出 app/assets/runtime.tar.xz）
./build-runtime.sh

# 4) 打包 APK
ANDROID_SDK_ROOT=/workspace/build/android-sdk ./build.sh
```

`build-runtime.sh` 会自动：
- 复制 Termux bionic arm64 的 node/bash/rg 与依赖库；
- 复制 `node_modules`，删除 `.bin` 与符号链接；
- 移除 Linux 桌面专属的 ripgrep、sherpa-onnx、sharp、koffi 二进制；
- 移除 Android 无法使用的 `libreoffice-kit-wasm`（约 146MB）；
- 清理 `.d.ts` 与嵌套重复的 `zod/src`；
- 保留 `node-pty` 的 `android-arm64` / `linux-arm64` 预编译；
- 装入 pnpm 原生 arm64 与 dshmarket；
- 写入 CLI shim（`dsh`、`pnpm`、`gunzip` 等）。

## 能力粒度授权

| capability | 含义 | 默认 |
|---|---|---|
| `screen` | 读取屏幕（无障碍节点/前台应用） | allow |
| `screenshot` | 截图 | allow |
| `input` | 点击、滑动、输入 | ask |
| `system` | 打开应用、返回/Home、系统设置 | ask |
| `notifications_read` | 读取通知 | allow |
| `notifications_act` | 点击/清除通知 | ask |
| `media` | 媒体控制、音量/亮度 | ask |
| `clipboard` | 读写剪贴板 | ask |
| `feedback` | TTS、震动/提示 | allow |

`allow` 直接执行；`ask` 弹原生确认；`deny` 返回“权限已关闭”。模型通过 `android_policy` 只能读取当前状态。

## 安全模型与边界

**定位**：九项授权是“防误操作”的 UX 闸门，不是对抗同 UID 代码的安全边界。引擎、插件、MCP 与 App 同 UID 运行，恶意代码原则上可以读写 App 私有文件；这里能防的是模型手滑、网页静默提权和错误调用。

已实施（1.2.0）：

1. **桥接双重校验**：MCP 先按能力判断，Java 侧 `/plus` 再用自己的 `action → capability` 映射复核；能力声明不匹配、未知 action 一律 403。`deny` 在 App 侧最终生效，过期授权无法复活。
2. **一次性审批凭据**：`ask` 由 `/confirm` 在用户点“允许”后签发随机 grant，绑定能力、120 秒过期、单次消费；`/plus` 必须携带且校验通过才执行。
3. **token 不再进 URL**：改为 `Authorization: Bearer`；控制文件权限从错误的全局可读修正为仅 owner 可读写。token 不进 WebView、不进日志。
4. **确认框防轰炸**：用 `onResume`/`onPause` 判断真实可见性，不可见立即返回 `app_not_visible`，不再空等 45 秒；最多 3 个待处理请求，弹窗最小间隔 1.2 秒。
5. **敏感 Bridge 方法加原生确认**：修改授权、恢复默认、切换运行模式、请求 Root/Shizuku 都会先弹原生框，网页脚本无法静默提权。

已知边界（未覆盖）：

- **fs 不在授权内**：`dsh-fs-local` 没有 allow/ask/deny 检查；DSH 权限 preset 在内核 < 5.13 时直接 `danger-full-access`。
- **同 UID 信任边界**：bash/插件可读写 `kokona_permissions.xml`、token、runtime 中的 `server.mjs`；Root 模式下整个引擎以 root 启动，九项授权对 shell/fs 无约束力。
- **审批绑定粒度**：`/confirm` 文案仍由调用方构造（含模型参数），grant 绑定的是“能力”而非单次具体动作。
- **持久化未脱敏**：截图与无障碍文本会以明文进入 DSH 会话 JSONL；本轮未做字段级脱敏。

## 已知限制

- **glibc 预编译包不可用**：官方 `sherpa-onnx`、`libreoffice-kit` 的 Linux 二进制是 glibc 的，bionic 无法加载。Android 上图像走 `sharp-wasm32`；语音、办公转换默认不可用。
- **Office 技能**：`dsh-skill-office` 的文档处理全部依赖 `libreoffice-kit`，而 kit 在 `process.platform === "android"` 时明确拒绝启动。因此随包的 `libreoffice-kit-wasm`（约 146MB）在 Android 上是纯死重，已从运行时移除。
- **终端 PTY**：已用 NDK 交叉编译 `node-pty` 的 N-API 模块（`prebuilds/android-arm64/pty.node`），不再依赖上游预编译。
- **Android 10+ 执行限制**：依赖 `targetSdk 28` 的兼容豁免才能从 app 私有目录 exec/加载本地二进制。

## 本轮进度

1. ✅ **设置页右上角终端按钮**
   - 在 `settings.action` 槽位注册“终端”按钮（设置面板右上角、关闭按钮左侧）。
   - 点击后：若当前有会话 → 关闭设置面板并 `sidebarRight.openTab("terminal")`；无会话 → 按钮禁用。
   - 窄屏只留 `>_` 符号，避免与关闭按钮挤压。

2. ✅ **为 Android 编译 node-pty**
   - `node-pty@1.2.0-beta.15` 的 N-API 模块，已编译为 `prebuilds/android-arm64/pty.node`（同时放置 `linux-arm64` 以兼容 Termux Node 的平台报告）。
   - 工具链：NDK 27.2 `aarch64-linux-android27-clang++` + Termux Node 头文件 + `node-addon-api`，由 [build-nodepty.sh](file:///workspace/KokonaHarness/build-nodepty.sh) 复现。
   - 产物仅依赖 `libc/libm/libdl`，无需额外共享库。

3. ✅ **包体瘦身**
   - 移除办公 WASM（146MB）、Linux 桌面原生二进制、`.d.ts`、嵌套 `zod/src`。
   - 结果：runtime 原始 606MB / 压缩 90MB；APK 94,684,584 字节（约 90.3 MiB），低于 DSH Go 的 183.8 MiB。

4. ✅ **软键盘遮挡布局（WebView / 浏览器）**
   - 页面 meta：`viewport-fit=cover` + `interactive-widget=resizes-content`。
   - Web 层：`visualViewport` 的 `resize`/`scroll` 写入 `--kn-keyboard-inset`；`focusin` 时 `scrollIntoView({ block: "center" })`，260ms 延迟等键盘动画结束。
   - 原生兜底：`setOnApplyWindowInsetsListener` 按 IME inset 给 WebView 补底部 padding；`adjustResize` 生效时 inset 已为 0，此路径自动无操作。
   - 不改动 DSH 原生布局，插件无需感知。

5. ✅ **安全加固（1.2.0）**
   - Java 侧 `/plus` 独立复核 `action → capability`，`deny` 最终生效；`ask` 需一次性 120 秒 grant。
   - token 改 `Authorization: Bearer`，控制文件 0600；确认框按真实前后台可见性判定，最多 3 个待处理、间隔 1.2 秒。
   - 改授权、重置、切模式、请求 Root/Shizuku 均需原生确认，网页无法静默提权。

## 运行时定制

构建树中已应用的品牌/移动端定制（升级 DSH 时需重新移植）：

- `dsh-client-ui-settings-general`：移动端导航宽度、`title` 属性、环境高级设置页、手机权限页（九项 allow/ask/deny）、右上角终端入口。
- `dsh-client-ui-sidebar`：收起为小 K，展开为完整 Kokona 签名 + HARNESS。
- `dsh-client-ui-conversation`：居中布局、移除 Hero 小 K 标记。
- `dsh-client-ui-brand-official`：品牌签名与 HARNESS pill。
- `dsh-client-ui-settings-models`：移动端模型行布局。
- `dsh-client-ui-plugin-manager`：品牌文案。
- `dsh-web-frontend`：标题、KokonaBridge shim、背景层。
- `dsh-fs-local`：Android 硬链接不可用时的独占复制回退。
- `dsh-node-addon-system`、`dsh-api-workspace-controller`、`dsh-attachment-local`、`dsh-session-persistence-jsonl` 等兼容补丁。

## 版本

- App：1.2.0（120）
- DSH：0.2.2-rc.1
- Runtime：dsh-0.2.2-rc.1-r28
