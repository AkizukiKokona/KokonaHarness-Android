# KokonaHarness

把 DeepSeek Harness（DSH）装进 Android 手机的原生运行器。App 不依赖 PRoot、Ubuntu 容器或 Termux 安装，直接以 Android bionic 的 Node.js 运行完整 DSH 引擎，WebView 加载 DSH Web UI，并通过原生桥接把手机能力暴露给模型。

当前版本：**1.1.2**（versionCode 112），内置运行时 **dsh-0.2.2-rc.1-r27**。

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
│   ├── AndroidManifest.xml        # versionCode 112 / versionName 1.1.2
│   ├── java/com/knh/akizukikokona/
│   │   ├── MainActivity.java      # WebView、KokonaBridge、生命周期
│   │   ├── EngineService.java     # 前台服务、引擎进程管理
│   │   ├── RuntimeInstaller.java  # 运行时解包/校验/替换（r27）
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
├── build.sh                       # aapt2 + javac + d8 + zipalign + apksigner
└── README.md
```

DSH UI 的定制补丁不落在本仓库源码内，而是打在 `node_modules/@deepseek-ai/...` 的构建树上，构建时由 `build-runtime.sh` 一起打包；重要补丁清单见本文末“运行时定制”。

## 构建

```bash
# 1) 准备 /workspace/build/rt/node_modules（npm 安装 @deepseek-ai/dsh 后打入定制补丁）
# 2) 组装运行时（产出 app/assets/runtime.tar.xz）
./build-runtime.sh

# 3) 打包 APK
ANDROID_SDK_ROOT=/workspace/build/android-sdk ./build.sh
```

`build-runtime.sh` 会自动：
- 复制 Termux bionic arm64 的 node/bash/rg 与依赖库；
- 复制 `node_modules`，删除 `.bin` 与符号链接；
- 移除 Linux 桌面专属的 ripgrep 包；
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

## 已知限制

- **glibc 预编译包不可用**：官方 `node-pty`、`sherpa-onnx`、`libreoffice-kit` 的 Linux 二进制是 glibc 的，bionic 无法加载。Android 上图像走 `sharp-wasm32`；语音、办公转换默认不可用。
- **Office 技能**：`dsh-skill-office` 的文档处理全部依赖 `libreoffice-kit`，而 kit 在 `process.platform === "android"` 时明确拒绝启动。因此随包的 `libreoffice-kit-wasm`（约 146MB）在 Android 上是纯死重。
- **终端 PTY**：`node-pty` 无 Android 预编译，终端标签需要自行编译 `prebuilds/android-arm64/pty.node`。
- **Android 10+ 执行限制**：依赖 `targetSdk 28` 的兼容豁免才能从 app 私有目录 exec/加载本地二进制。

## 待实现（本轮）

1. **设置页右上角终端按钮**
   - 在 `settings.action` 槽位注册一个“终端”按钮（位于设置面板右上角、关闭按钮左侧）。
   - 点击后：若当前有会话 → 关闭设置面板并 `ctx.sidebarRight.openTab("terminal")`；若无会话 → 按钮禁用并提示先打开会话。
   - 该按钮作为 PTY 入口，配合下一项的 `node-pty` 使用。
   - 移动端适配：窄屏隐藏文字仅留图标/符号，避免与关闭按钮挤压。

2. **为 Android 编译 node-pty**
   - 目标：`node-pty@1.2.0-beta.15` 的 `pty.node`（N-API，ABI 稳定）。
   - 依据：bionic `<pty.h>` 自 API 23 起提供 `openpty` / `forkpty`；`node-pty` 加载顺序会查 `prebuilds/android-arm64/pty.node`，无需改 JS。
   - 构建：Android NDK `aarch64-linux-android27-clang++` + Termux node 头文件 + `node-addon-api`，输出到 `node_modules/node-pty/prebuilds/android-arm64/pty.node`。
   - 注意：Android 上不能链接 `-lutil`（相关函数在 libc）；运行时可提供 `libc++_shared.so`。

3. **包体瘦身**
   - 移除 Android 不可用的 `os`/`cpu` 限定依赖（办公 WASM、linux 原生二进制）。
   - 清理 `@deepseek-ai` 嵌套依赖中的重复 `zod` 源码（约 147MB），以及 `.d.ts`、测试、文档。
   - 目标：APK 显著小于当前 133,367,208 字节（约 127 MiB），并低于 DSH Go 的 183.8 MiB。

4. **软键盘遮挡布局（WebView / 浏览器）**
   - 原生层：`AndroidManifest` 已设置 `adjustResize`；对忽略该模式的 OEM，用 `WindowInsetsCompat` 监听 IME inset，给根容器补底部 padding，并向 WebView 派发 `resize`。
   - Web 层：注入 `visualViewport` 的 `resize`/`scroll` 监听，写入 CSS 变量 `--kn-keyboard-inset`；`focusin` 时对输入框执行 `scrollIntoView({ block: "center" })`。
   - 页面 meta：`interactive-widget=resizes-content`、`viewport-fit=cover`，配合 `env(safe-area-inset-bottom)`。
   - 目标：输入框与底部操作栏始终位于键盘上方，不改动 DSH 原生布局。

## 运行时定制

构建树中已应用的品牌/移动端定制（升级 DSH 时需重新移植）：

- `dsh-client-ui-settings-general`：移动端导航宽度、`title` 属性、环境高级设置页、手机权限页（九项 allow/ask/deny）。
- `dsh-client-ui-sidebar`：收起为小 K，展开为完整 Kokona 签名 + HARNESS。
- `dsh-client-ui-conversation`：居中布局、移除 Hero 小 K 标记。
- `dsh-client-ui-brand-official`：品牌签名与 HARNESS pill。
- `dsh-client-ui-settings-models`：移动端模型行布局。
- `dsh-client-ui-plugin-manager`：品牌文案。
- `dsh-web-frontend`：标题、KokonaBridge shim、背景层。
- `dsh-fs-local`：Android 硬链接不可用时的独占复制回退。
- `dsh-node-addon-system`、`dsh-api-workspace-controller`、`dsh-attachment-local`、`dsh-session-persistence-jsonl` 等兼容补丁。

## 版本

- App：1.1.2（112）
- DSH：0.2.2-rc.1
- Runtime：dsh-0.2.2-rc.1-r27
