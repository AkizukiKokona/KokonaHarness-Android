<p align="center">
  <img src="branding/brand.svg" alt="KokonaHarness" width="380">
</p>

# KokonaHarness

**一整套桌面级 AI 引擎，装进 Android 手机。不用电脑、不用 Root、不用 Termux，也不必先塞一个 Linux 发行版进去。**

[DSH（DeepSeek Harness）](https://github.com/deepseek-ai/deepseek-harness) 会读文件、跑脚本、调工具，只是它一向住在电脑和服务器上。KokonaHarness 把这套引擎原样搬进手机：界面仍是官方 DSH Web UI，手机自己的屏幕、通知、剪贴板、截图再按你的授权一项项交给它。

**它和「手机上装个 AI 客户端」的区别，就在这四点：**

- **电脑上能用的插件，这里直接装。** DSH 插件一半是浏览器界面、一半是 Node 宿主，手机跑的是同一套运行时、同一个插件协议——除移动端显示效果之外，**不需要任何额外适配**。
- **零 PRoot、零容器。** Node 直接跑在 Android 原生 bionic 上，启动就是启动一个普通进程，不是「先开一个 Ubuntu 再想办法穿透」。
- **它真的能动手。** 读屏幕、点按钮、看通知、截图、改音量亮度——九项能力各有独立的「允许 / 询问 / 关闭」。
- **装一次，之后不用管。** 运行时随包分发，升级先解到临时目录再原子替换，不会留下半套。

> Android 8.1+ · arm64 · 当前 **1.1.0**（149）· 内置 DSH **0.2.2-rc.1** / **KokonaRuntime r39**

## 路线：从引擎到兼容层，全程不用 PRoot

把 Linux 工具搬上 Android，常见做法是先造一个完整的 Linux 用户空间，再让所有程序住进去。KokonaHarness 选了另一条路，关键在两个点：

- **Node.js 直接跑在 Android 原生的 bionic 上。** 没有 PRoot、没有 Ubuntu 根文件系统、没有 ptrace 拦截——引擎启动就是启动一个普通进程。KokonaRuntime 因此做得更轻，升级按整包全量替换，状态干净、不残留半更新文件。
- **遇到 glibc 程序，交给 `kokona-grun` 按需接住，而不是回头开一个容器。** 默认所有命令仍走 bionic、性能不变；只有以 glibc 链接的 Linux arm64 程序才切换到构建时可带入的独立 glibc 运行器，同样零 PRoot、零 ptrace，启动接近原生。用法与边界见 [`runtime-skills/glibc-runtime/SKILL.md`](runtime-skills/glibc-runtime/SKILL.md)。

换句话说：**从引擎到兼容层，整套运行时都不依赖 PRoot**。这是本项目与「内置完整 Linux 环境」那类路线最根本的区别。

## 它能替你做什么

- **出门在外也能把活交出去。** 通勤、排队、临时没带电脑，一样可以丢一句「把这个月的账单按类别汇总，做成表格和趋势图」，让它读文件、写脚本、把结果交回你手里。
- **让 AI 帮你点手机。** 读屏幕、看通知、点按输入、截图、调媒体和亮度——九项能力各自设成「允许 / 每次询问 / 关闭」，需要确认时由系统弹窗问你，模型没法自己改授权。
- **还是你熟悉的那套 DSH。** 会话、模型切换、插件市场、终端标签、文件树工作台都在，界面是官方 Web UI 加了一点移动端适配，不是另起炉灶的简化版。
- **Linux 命令行工具也能带进来。** 碰到以 glibc 链接的 arm64 程序，不必为了一个工具先装一整套 Linux；`kokona-grun` 直接接住，模型会按 `glibc-runtime` 技能先判断 ELF 解释器再决定怎么跑。
- **装一次，升级不折腾。** KokonaRuntime 随 App 一起打包；升级时先解到临时目录再原子替换旧树，不会留下半套运行时。小修复走启动时自愈，不必重打整包。

## 插件：电脑上能用的，这里基本都能用

DSH 的插件由两半组成：浏览器里的界面（client）和 Node 侧的宿主（host）。KokonaHarness 跑的是同一套 DSH 运行时、同一份插件协议，所以**安装方式与桌面完全一致**——在插件市场里点安装，或把打包好的 bundle 放进 profile 目录。

- **功能逻辑不用改。** 插件拿到的仍是同一份会话、同一个文件系统接口、同一张工具注册表。写插件的人不需要为「手机版」单独做一套。
- **只有界面需要照顾窄屏。** 手机上的侧栏会变成抽屉、设置面板铺满屏幕，这些由 App 的注入层统一处理；插件按标准 Web UI 写，窄屏下自然能用。真正需要精细适配的，只是那些依赖横向大画布或鼠标悬停的交互。
- **依赖原生模块的插件要留意。** 用到 Node 原生扩展（N-API）的插件，需要有 Android arm64 的构建产物。本包已经内置 `node-pty`，所以终端类插件开箱可用；其它原生模块要么等作者提供 arm64 版本，要么用 `kokona-grun` 走 glibc 路线。
- **已经在手机上跑通的例子**：`dshmarket`（插件市场）、`dsh-better-sidebar`（文件树 / 编辑器 / 任务工作台）、`dsh-thinking-slider`，以及本仓库自带的 `kha-setthings`（KHA 自有设置页与终端入口）。

## 几点须知

- 手机上跑的是 **DSH 引擎和工具环境**；真正动脑的模型请求仍发给你自己配置的服务方，需要联网、按你的账户计费。这里没有离线大模型，和其它 DSH 客户端一样。
- 本项目是**社区第三方作品，与 DeepSeek 官方无关**；DSH 本身由 DeepSeek 开源。
- 仓库已**公开**，[Release](../../releases/latest) 提供可直接安装的 APK，也可按下面的「构建」章节自行打包。设备要求 Android 8.1（API 27）及以上、**仅 arm64-v8a**。<br>
  <sub><i>注：Android 10 以下内核版本过低，沙箱无法启动，只能使用完全权限运行，其余功能正常。</i></sub>
- 让 AI 操作手机需要你主动开启无障碍等权限；不授权的话，它就是一个跑在手机里的 DSH，不涉及屏幕操控。
- 仓库同时在 [GitHub](https://github.com/AkizukiKokona/KokonaHarness-Android) 与 [Codeberg](https://codeberg.org/AkizukiKokona/KokonaHarness-Android) 托管，代码、标签与发行说明两边同步；**两边都发布同名发行版**（Codeberg 侧用 `tools/publish-codeberg-release.sh` 同步，token 从 git 凭据管理器读取，可重复执行，也可用 `CB_REPO` 指定其它仓库）。Codeberg 有 1.5 GiB 的发行版附件配额，因此 **Codeberg 侧仅最新版本保留安装包附件**：更老版本只保留发行页面与说明，并指向 GitHub 同版本的安装包；**GitHub 侧附件不受影响、始终保留**。若配额临时不足，脚本会自动降级为「完整说明 + GitHub 下载链接 + SHA256」。

## 开始使用

1. 直接装 [Release 里的 APK](../../releases/latest)，或准备构建环境（Node/npm 依赖、Android SDK/NDK、Termux arm64 运行时素材）后依次执行 `build-nodepty.sh` → `build-runtime.sh` → `build.sh`。
2. 安装后按系统提示允许「安装未知来源」（项目以侧载为目标，`targetSdk 28`）。
3. 首次启动会解包 `KokonaRuntime`（约 583 MiB），稍等片刻；小修复与执行位补会在每次启动时自愈，无需重装。
4. 在 DSH 设置里配好模型凭据（服务方 / API Key），就可以开始派活了。建议先从不涉及删除、支付的任务试起。

## 和 DSH Go：同一个目标，两条路线

社区里还有一个很受关注的项目 [DSH Go](https://github.com/ccch1mneyyy/dsh-go)（由 ccch1mneyyy 开发），目标同样是「让 DeepSeek Harness 装进口袋」。两者都是围绕开源 DSH 的非官方客户端，工程取舍不同，谈不上谁替代谁：

| | KokonaHarness（本项目） | DSH Go |
|---|---|---|
| 想解决的事 | 让 DSH 引擎在手机本机独立跑起来 | 手机本机可跑，也能接着用电脑上的 DSH 工作区 |
| 引擎落在哪 | Android **bionic 上直接跑 Node.js**，**不用 PRoot**、不装 Ubuntu 根文件系统、无 ptrace | **PRoot 中的 Ubuntu 24.04** 环境，Node.js 与 DSH 在其中 |
| 遇到 glibc 程序 | 默认仍走 bionic；按需用 **`kokona-grun`** 加载独立 glibc，**同样不用 PRoot**（构建时可带入） | 本机环境本身即完整 Ubuntu 用户空间，glibc 程序在其中直接运行 |
| 界面 | 复用**官方 DSH Web UI**（WebView）+ 移动端定制 | 自研 **Flutter 原生界面**，可切网页版 |
| 连回电脑 | 暂未提供 | **SSH / DSH Remote** |
| 手机操控的授权 | **九项能力**各自 allow / ask / deny，ask 走原生确认闸门 | 三档确认（每次询问 / 本任务 / 始终），敏感操作默认交还用户 |
| 运行环境形态 | KokonaRuntime 快照压缩约 97 MiB、解压约 583 MiB，整包全量替换 | 首次本机环境建议预留约 1 GB，arm64 APK 约 185 MiB |
| 获取方式 | 源码发布，自行构建 | Release 提供 arm64 APK 与完整使用文档 |

> 表中体积与占用取自各自公开记录中的构建数据，会随版本变化。

简单说：DSH Go 用一套完整 Linux 环境换来与桌面更一致的体验，并打通了远程主机；我们选择贴着 Android 原生跑——引擎不用 PRoot，遇到 glibc 程序再用 `kokona-grun` 按需接住，把运行时做轻，同时尽量把界面交还给官方 Web UI。两条路线把复杂度放在了不同的地方，没有高下之分。**如果你的工作区在电脑上、想随时切回去，DSH Go 的远程路线更顺手；如果你更在意「引擎就长在手机里」、不引入容器，以及逐项可控的手机能力授权，欢迎试试我们。** 两个项目都在快速迭代，具体功能以各自最新版本为准——也向社区先行者致意。

## 整体架构

一句话概括：WebView 负责「看得见的界面」，Android 原生层负责「手机才有的能力」，最底下是随 App 一起打包的 `KokonaRuntime`（内置 Node.js 运行时）。

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
│  KokonaRuntime（App 私有目录）                           │
│  node (Termux bionic arm64) + bash + git/ripgrep/…        │
│  node_modules: @deepseek-ai/dsh 及全部官方依赖            │
│  runtime-skills: android-capabilities / python-runtime    │
│                 / glibc-runtime                           │
│  knhplus-mcp: KNHPlus MCP server（手机能力工具集）         │
└─────────────────────────────────────────────────────────┘
```

### 关键设计

- **不套容器**：Node 直接跑在 Android 的 bionic 上，启动快、体积小，也没有 ptrace 的额外开销；代价是依赖 glibc 的预编译包需要单独适配（现在可按需用 `kokona-grun`，见「已知限制」）。
- **薄原生桥**：Java 侧只做 Android 特有的事（无障碍、通知读取、Shizuku/Root、截图、剪贴板、震动、TTS），其余全部交给 DSH 插件与 MCP。
- **能力逐项授权**：授权状态由 `AndroidPermissions` 持久化到 SharedPreferences（`kokona_permissions`），九项能力各自可选 `allow` / `ask` / `deny`。MCP 每次调用通过 `/policy` 实时读取，模型只能读、不能改；授权项在 `kha-setthings` 插件提供的专属设置页中展示与修改。
- **确认闸门**：`ask` 能力触发 `ConfirmationGate` 原生弹窗，45 秒无响应按拒绝处理；App 不可见时不弹窗，并提示先打开 App。
- **KokonaRuntime 全量安装**：`RuntimeInstaller` 把新快照解到 `KokonaRuntime.new`，确认完整后把旧目录 rename 为 `KokonaRuntime.old`、staging 转正，再删旧树；`runtimeLooksComplete()` 抽查关键文件，避免「版本号相同但目录是空的」被误判为已安装。小修复（JS 补丁、执行位）由 `EngineService` 每次启动幂等写入，不重打整包。

## 手机能力：九项授权

AI 想动手机上的东西，得先过这九道开关。每一项都能单独设成「直接允许 / 每次问我 / 关掉」：

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

`allow` 直接执行；`ask` 弹原生确认；`deny` 返回「权限已关闭」。模型通过 `android_policy` 只能读取当前状态。

## 安全模型与边界

**定位**：九项授权是「防误操作」的 UX 闸门，不是对抗同 UID 代码的安全边界。引擎、插件、MCP 与 App 同 UID 运行，恶意代码原则上可以读写 App 私有文件；这里能防的是模型手滑、网页静默提权和错误调用。

已实施（1.1.0）：

1. **桥接双重校验**：MCP 先按能力判断，Java 侧 `/plus` 再用自己的 `action → capability` 映射复核；能力声明不匹配、未知 action 一律 403。`deny` 在 App 侧最终生效，过期授权无法复活。
2. **一次性审批凭据**：`ask` 由 `/confirm` 在用户点「允许」后签发随机 grant，绑定能力、120 秒过期、单次消费；`/plus` 必须携带且校验通过才执行。
3. **token 不再进 URL**：改为 `Authorization: Bearer`；控制文件权限从错误的全局可读修正为仅 owner 可读写。token 不进 WebView、不进日志。
4. **确认框防轰炸**：用 `onResume`/`onPause` 判断真实可见性，不可见立即返回 `app_not_visible`，不再空等 45 秒；最多 3 个待处理请求，弹窗最小间隔 1.2 秒。
5. **敏感 Bridge 方法加原生确认**：修改授权、恢复默认、切换运行模式、请求 Root/Shizuku 都会先弹原生框，网页脚本无法静默提权。

已知边界（未覆盖）：

- **fs 不在授权内**：`dsh-fs-local` 没有 allow/ask/deny 检查；DSH 权限 preset 在内核 < 5.13 时直接 `danger-full-access`。
- **同 UID 信任边界**：bash/插件可读写 `kokona_permissions.xml`、token、runtime 中的 `server.mjs`；Root 模式下整个引擎以 root 启动，九项授权对 shell/fs 无约束力。
- **审批绑定粒度**：`/confirm` 文案仍由调用方构造（含模型参数），grant 绑定的是「能力」而非单次具体动作。
- **持久化未脱敏**：截图与无障碍文本会以明文进入 DSH 会话 JSONL；本轮未做字段级脱敏。

## 已知限制

- **glibc 程序按需运行**：默认所有命令仍走 bionic，不经任何兼容层；构建时可带入独立的 `kokona-glibc` 与 `kokona-grun` 启动器，让以 glibc 链接的 arm64 程序直接运行，无需 proot / ptrace。若构建时未提供 glibc staging，该 KokonaRuntime 不附带此能力。用法与限制见 [`runtime-skills/glibc-runtime/SKILL.md`](runtime-skills/glibc-runtime/SKILL.md)；硬编码 `/etc/resolv.conf`、`/etc/ssl` 的 Go/Rust 静态二进制仍无法覆盖。
- **语音与 Office 转换**：上游 `sherpa-onnx`、`libreoffice-kit` 的二进制面向 glibc 且在 `process.platform === "android"` 时主动拒绝启动，Android 上这两类能力默认不可用；图像处理走 `sharp-wasm32`。随包的 `libreoffice-kit-wasm`（约 146 MB）在 Android 上是纯死重，已从 KokonaRuntime 移除。
- **终端 PTY**：已用 NDK 交叉编译 `node-pty` 的 N-API 模块（`prebuilds/android-arm64/pty.node`），不再依赖上游预编译。
- **Android 10+ 执行限制**：依赖 `targetSdk 28` 的兼容豁免才能从 App 私有目录 exec / 加载本地二进制；因此目前以 APK 侧载为目标，不走应用商店。

## 构建

这几条命令做的是「组装」而不是「从零编译」——Node、Python、Termux 工具链都是预先备好的素材，
脚本负责裁剪、打包、签名。动手前先确认下面三份素材，否则会在中途报 `missing ...`。

> **构建前须知**
>
> 首个 Release 提供预编译 APK；模型 API Key 需自备。三个脚本默认按 Linux 布局
> （`/workspace/build`、无后缀 `aapt2`、`prebuilt/linux-x86_64`），在 Windows 上可用
> `BUILD_DIR` / `ANDROID_SDK_ROOT` / `ANDROID_NDK_HOME` / `NODE_MODULES` / `PY_SRC`
> 等环境变量重定向，或直接在 WSL 里跑，不必改代码。
> **Windows 上完整跑通踩过的坑**（路径转换、classpath 分隔符、`-encoding UTF-8`、
> Unix 执行位丢失、tar/PAX、`xz`、批量删除保护、CPython 只能 Linux 宿主等）
> 集中记在 [`WINDOWS-BUILD.md`](WINDOWS-BUILD.md)。
>
> **自己编译要留意的四件事**
> 1. **签名**：`build.sh` 把 keystore 放在 `$BUILD_DIR/kokona.keystore`，**不存在就现场生成一个**。
>    想产出能覆盖安装到现有设备上的包，请复用同一个 keystore（换 `BUILD_DIR` 时记得先复制）。
> 2. **SDK 版本可覆盖**：脚本默认 `build-tools/35.0.0` + `platforms/android-35`，SDK 里没装这两个时用
>    `ANDROID_BUILD_TOOLS` / `ANDROID_PLATFORM` 指过去，不用改脚本。
> 3. **缺 `zip` 也能打**：Git Bash 不附带 `zip`，脚本会自动改用 `tools/zip.py`。
> 4. **SHIM 语法门禁**：注入 WebView 的脚本是一串拼接的 Java 字符串字面量，少一个引号就会整段
>    语法错误且 `evaluateJavascript()` **静默失败**（所有样式与侧栏品牌一起失效，日志里却什么都没有）。
>    打包前会先跑 `tools/check-shim.cjs`，不通过直接中断。

**三份必备素材**

| 素材 | 位置 | 说明 |
|---|---|---|
| Termux arm64 运行时 | `$BUILD_DIR/termux-debs/ext/` | 约 40 个 `.deb` 解包，提供 node / bash / rg 及依赖库。脚本按**固定版本**引用 `.so`（如 `libicu.so.78.3`、`libz.so.1.3.2`），换快照需同步改脚本 |
| DSH 的 npm 树 | `$BUILD_DIR/rt/node_modules/` | 安装 `@deepseek-ai/dsh@0.2.2-rc.1` 后打上 `runtime-patches/`、`tools/*.mjs` 的定制补丁；国内可用 npmmirror |
| Python 3.15.2 | `$BUILD_DIR/py-build/pyinstall/`（可用 `PY_SRC` 覆盖） | **完整 CPython 发行版**：`bin/python3.15` + `lib/libpython3.15.so` + 整套 `lib/python3.15`。仓库不生成它，需自行交叉编译 |

**Python 这份要特别留意**：CPython 的 Android 交叉编译只支持 **Linux / macOS 宿主**
（官方 `Android/README.md`：Windows 上不能在本机完成构建，需从别处拷 `prefix`），
因为它要先用宿主工具链编一份 build Python 来引导。Windows 用户走 WSL2 时，
NDK 也必须用 **Linux 版**（`prebuilt/linux-x86_64`）——`build-nodepty.sh` 用的 Windows 版
不能拿来编 CPython。编好后把 prefix 放进 `pyinstall/`，后续组装在 Windows 上照样能跑。

可选：`$BUILD_DIR/glibc-run/staging/` 提供 `kokona-grun`，缺失时只打印 warning；
上面三份缺任意一份会直接报错退出。

```bash
# 1) 准备 /workspace/build/rt/node_modules（npm 安装 @deepseek-ai/dsh 后打入定制补丁）
# 2) 交叉编译 Android PTY（NDK 27.2 + Termux node 头文件）
./build-nodepty.sh

# 3) 组装 KokonaRuntime（产出 app/assets/runtime.tar.xz）
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
- 用 `runtime-patches/node-addon-require-builtin-android.js` 替换 `node-addon-require-builtin` 的原生入口；
- 装入 pnpm 原生 arm64 与 dshmarket；
- 写入 CLI shim（`dsh`、`pnpm`、`gunzip` 等）；
- 视构建环境带入可选的 `kokona-glibc` / `kokona-grun`。

## 目录结构

```
KokonaHarness-Android/
├── app/
│   ├── AndroidManifest.xml        # versionCode 149 / versionName 1.1.0
│   ├── java/com/kokonaharness/android/akizuki/
│   │   ├── MainActivity.java      # WebView、KokonaBridge、SHIM、生命周期
│   │   ├── EngineService.java     # 前台服务、引擎进程、profile 与启动自愈补丁
│   │   ├── RuntimeInstaller.java  # 运行时全量解包 + 原子替换 + 执行位修复
│   │   ├── TarExtractor.java      # 精简 tar/PAX 解包器
│   │   ├── LocalBridgeServer.java # loopback HTTP：/policy /confirm /screenshot …
│   │   ├── AndroidPermissions.java# 九项能力授权持久化
│   │   ├── ConfirmationGate.java  # 原生确认弹窗
│   │   ├── AndroidCapabilities.java # 无障碍/通知/媒体/剪贴板等实现
│   │   ├── RunMode.java           # 普通 / Shizuku / Root
│   │   ├── Diagnostics.java       # 错误页诊断信息
│   │   ├── ScreenshotAccessibilityService.java
│   │   ├── NotificationAccessService.java
│   │   └── ToastCompat.java
│   ├── res/                       # 图标、无障碍说明等
│   ├── libs/                      # Shizuku、xz.jar
│   └── assets/
│       ├── runtime.tar.xz         # 构建产物（不入库）
│       ├── kha-node-patch.cjs     # Node 26 package.json reader 兜底
│       ├── official-settings-client.js # 官方设置页原版（启动时覆盖）
│       ├── kha-setthings/         # 专属设置插件（启动时解压进 runtime）
│       └── seed/                  # DSH profile 种子
├── knhplus-mcp/                   # KNHPlus MCP server（server.mjs）
├── runtime-skills/                # 随 KokonaRuntime 下发的技能说明
│   ├── android-capabilities/
│   ├── python-runtime/
│   └── glibc-runtime/
├── branding/                      # 品牌矢量与图标源素材
├── assets-src/                    # 背景等源资源
├── tools/                         # 构建期补丁脚本 + check-shim.cjs
├── build-runtime.sh               # 组装 KokonaRuntime 快照
├── build-nodepty.sh               # 交叉编译 node-pty（NDK + Termux 头文件）
├── build.sh                       # aapt2 + javac + d8 + zipalign + apksigner
├── runtime-patches/               # 历史定制补丁存档（设置页已停用、require-builtin 回退）
├── WINDOWS-BUILD.md               # Windows 构建踩坑与速查表
└── README.md
```

设置页现已退回官方原版：`EngineService.restoreOfficialSettings()` 每次启动把 `assets/official-settings-client.js` 覆盖到已安装的 `KokonaRuntime`，不必重打 100 MB 快照；`runtime-patches/` 里的设置页补丁仅作历史存档、不再生效。我们的自有功能改由独立插件 **`kha-setthings`** 提供：启动时把 `assets/kha-setthings/` 的四个小文件解压到 `KokonaRuntime/lib/node_modules/kha-setthings/`，并在**每次启动**时与 profile 对账（缺了补上、重名去重、旧的大写包名一并清掉）。插件只注册一个 `settings.section`（运行模式 / 手机能力授权 / 关于，上下排列）和右上角的终端 `settings.action`，**完全不改官方设置布局**；桌面端专属的「打开配置文件」按钮由 WebView shim 一条 CSS 隐藏。其余 DSH UI 定制仍打在 `node_modules/@deepseek-ai/...` 的构建树上，清单见本文末「运行时定制」。

## 最近更新

<details>
<summary>展开开发记录</summary>

**1.1.0**（运行时 r39）把 `kokona-grun` 从 170 行 sh 包装脚本**整个换成原生 ELF launcher** （`app/native/kokona-run.c`）：进程内完成 DNS 校验、locale、前缀与环境准备，最后一次 `execve()` 直达 glibc loader，**没有 toybox sh、没有 fork、没有外部命令**。设备实测旧脚本单次 175–192ms，其中 65% 花在 `dirname`/`tr`/`getprop`/`cd+pwd` 的 fork+exec 上；同一程序裸 loader 只要 6ms、bionic 基线 26ms——瓶颈从来不是 glibc。七个 `k*` 工具改成同一个 ELF 按 `argv[0]` 分派（设备端 tar 会丢链接，所以是实体副本），新增 `--exec`（跳过准备）与 `--shell`（一次准备、连续跑多个程序）。同批修掉：无 locale 数据时 `LANG`/`LC_ALL` 一起强制为 C（消除 `locale` 的三行 `Cannot set LC_*`）、DNS 先比对再写（`resolv.conf` 的 mtime 不再无故变化）、热路径彻底不调 `getprop`、`--version`/`--help` 给友好输出。另把「打开目录」统一按平台分派：Android 走系统文件管理器 （`ACTION_VIEW` + externalstorage 文档 URI），Windows / WSL / macOS 行为一行未动；补上 `KOKONA_ANDROID=1` 供运行时判定平台（Node 在 Android 上也报 `linux`）。版本号 1.1.0 / versionCode 149，正式发行版。

**1.0.48**（运行时 r38）`kldd` 连修三轮才走对：`kokona-glibc/bin/ldd` 是 `/system/bin/sh` 脚本，r36 把它交给 loader → `invalid ELF header`；r37 改用 `/system/bin/sh` 却顺手导出了 `LD_LIBRARY_PATH`，bionic 的 sh 自己去加载 glibc 的 `libc.so`（链接脚本）→ `bad ELF magic`。现在**绕开脚本**，直接用 loader 自带的 `--list` 模式，`--library-path` 同时带上前缀与 `~/.local/lib/kokona-glibc`，补充库照样解析，且**一个环境变量都不碰**（skill 里那条禁令就是这么来的）。同批还修了 `dns.servers` 逐行校验与多条 nameserver 拼成一行的老问题、无障碍未授权时改为打开系统设置页、开屏改成分阶段反馈：解压阶段有真实字节进度，显示连续百分比；启动引擎阶段没有可测进度，只保留转圈与「（没有卡死喵）」安抚文字，不再出现 0→88→100 那种跳数字。版本号 1.0.48（versionCode 148），本轮为本地试装包，未发 Release。

**1.0.43** **左侧抽屉**与**设置面板**加上毛玻璃（`backdrop-filter:blur(14px)` + 72% 底）。抽屉原本是实底色，压在主页上亮度几乎一致，两块会读成同一层，所以必须配一点模糊才有边界；设置面板的 `::before` 从 82% 降到 70%，让模糊透出来而不是被近不透明的白盖住。**终端改为全屏**：原先 `MATCH_PARENT` 仍留着 72dp 顶边距和 16dp 圆角，看着像半屏抽屉；现在边距与圆角归零，整个屏幕就是一层终端，点 × 或返回键回到仍然打开的设置界面。README 另补「自己编译要留意的四件事」（keystore、SDK 版本覆盖、zip、SHIM 门禁）。版本号 1.0.43（versionCode 143）。

**1.0.42** 找到 1.0.39 之后「怎么改都没反应」的真正原因：合并 CSS 规则时少了一个引号，注入脚本拼接出来是 `...{'background:...}`，整段 JS 语法错误。而 `evaluateJavascript()` **不上报语法错误**，于是那几版里样式与侧栏品牌一起静默失效，日志干干净净。修复拼接；`brand.svg` 内嵌的 82 KB PNG 用 ImageIO 重编码到 96px 高（SVG 115 KB → 20 KB），品牌图不再走 CSS 变量而是直接内联进 `::after`；`build.sh` 增加 `check-shim.cjs` 门禁。版本号 1.0.42（versionCode 142）。

**1.0.40** 插件页那两条**纯白**其实不是"浓度"问题，是**叠加**：设置面板自己有一层 `::before{background:var(--token);opacity:.82}`，顶栏再叠一层 82% 白，合成就是 1−0.18×0.18≈**96.8%**，肉眼等同纯白，所以怎么调 alpha 都"没有半透明效果"。现在把内置插件管理器的 `.pageBar:before` 与 dshmarket 的 `_stickyHead` / `_head` / `_cats` 一律设为 `transparent`，直接露出面板那一层——顶栏与周围**同一块表面**，边界自然消失。同时修掉 `pageBar::after` 被误加背景（compact 时它只有边框）造成的又一层叠加。侧栏品牌标识也加了两道保险：`--kn-brand` 改为每次加载只写一次（原来用 getPropertyValue 比对 150 KB 的 data URL 永远不相等，等于每轮同步都写 `documentElement.style`，会触发侧栏观察者），并在 `html[data-kn-sb="closed"]` 下把普通 toggle 也纳入品牌锚点，展开态仍然完全不动。版本号 1.0.40（versionCode 140，可覆盖安装）。

**1.0.39** 插件页顶部的**纯白块**（设置 →「插件」，管理页与市场页都有）。真因两条，都不是浓度问题：其一，`color-mix()` 是 Chrome 111+ 语法，设备 WebView 较旧时整条声明被丢弃，回退到 `background: var(--dsw-alias-bg-layer-2)` —— 纯色令牌，于是变成完全不透明的白；现改回 `rgba(255,255,255,.82)` / `rgba(26,26,29,.82)`，任何 WebView 都认。其二，设置面板本身已有 `::before{inset:0;background:var(--dsw-alias-bg-layer-2);opacity:.82}` 铺底，上一版又给面板加了一层背景，两层叠加把弹窗推向实心白；现改为 `background:transparent`，半透明交还上游的 `::before`。另外侧栏收起时的**品牌标识**改为脚本标记锚点（优先 `[data-sidebar-opener]`，否则取非弹窗内的收起态按钮），不再因为某个界面渲染了别的按钮就退回官方图标；展开态不受影响。版本号 1.0.39（versionCode 139，可覆盖安装）。

**1.0.38** 修掉插件化之后的四个真机问题。其一，**客户端插件没声明 `inject`**：`ctx.slots` 属于受声明保护的服务，不导出 `exports.inject = ['slots']` 就会被上下文守卫直接拒绝，异常又被注册守卫吞掉，结果插件市场里能看到这个包、日志里却什么都没有，设置页**始终不出现 KHA 选项卡**。其二，**运行时版本没升导致新旧插件重叠**：r33 的快照里已经带着旧的设置页与旧插件目录，升级后只覆盖了少量文件，旧的 `KHA-Setthings` 与新的 `kha-setthings` 叠在一起；运行时提到 **r34**，强制一次全量重解压 + 原子替换，启动时再对大写旧包名做四处清理（运行时目录、profile 软链、`dependencies` 与 `dsh.profile.bundles`、`cordis.patch.yml` 的 `insert` 行）。其三，`kha-setthings` 不再写进 `dependencies`：它是随运行时下发的自有包、没有 registry 条目，假版本号会让插件管理器一跑 `pnpm install` 就整盘解析失败；改由 `node_modules` 软链 + `bundles` 行挂载，并修好悬空软链不会被重建的问题。其四，**品牌标识固化**：原先的 `html:not([data-kn-modal="1"])` 条件会随任何可见的 `[role=dialog]` 翻转，切界面回来或弹出全屏提示时就退回官方图标；现改为无条件生效。另外半透明不再是写死的白色，顶栏与设置面板改用 `color-mix()` 跟随主题令牌，并显式补 `body[data-ds-dark-theme]` 分支。版本号 1.0.38（versionCode 138，可覆盖安装）。

**1.0.37** 设置页**退回官方原版**，不再打任何设置页补丁：竖屏文字强制横排、桌面端「打开配置文件」按钮隐藏，两处由 WebView shim 处理；原生终端覆盖界面（持久 bash、左滑 / 返回键退出、启动打印提示）保留。随后改为**插件化**：新增独立 bundle `kha-setthings`，注册唯一一个自有设置选项卡（运行模式、手机能力授权、关于，上下排列）并把终端按钮注册到官方 header 右上角的 `settings.action`，因此官方设置布局完全不受影响。侧栏收起时左上角品牌标识恢复：依据 `[data-sidebar-opener]` 元素本身判断，**展开态样式完全未改**。版本号 1.0.37（versionCode 137，保证可覆盖安装）。

**1.0.37** 修掉真机启动失败（Termux `libcrypto.so.3` 写死 `OPENSSLDIR`，改用自带 `openssl.cnf` + `OPENSSL_CONF`）；用 `kha-node-patch.cjs` 兜住 Node 26 原生 package.json reader 的 `ERR_INVALID_PACKAGE_CONFIG`；修 `.dsh` 目录不可写导致的插件装卸 EACCES、pnpm 执行位丢失，以及内置插件每次启动复活（现在可自由安装 / 卸载）；**彻底移除 manifest 增量更新，改为全量原子替换**并加完整性自检；设置页改为启动时幂等补丁（终端入口、禁用桌面端「打开配置文件」、去掉造成花屏的全屏半透明层与 blur）。首个 Release 同时提供 APK，跳过此前所有版本。

**1.0.37** 修掉设置页被侧栏抽屉遮挡的问题，品牌图标只在无对话框时生效；引擎启动时写入系统真实 DNS，为 `kokona-grun` 增加兜底；新增 `brand.svg`、runtime manifest 工具与 `glibc-runtime` 技能说明，并同步构建脚本。

**1.0.37** 运行时改为按 manifest 增量更新，比对 sha256 后只重写变化文件，失败保留旧版本下次重试；版本滚动到 r29。修掉插件市场侧栏图标重复，并预装 `dsh-better-sidebar@0.30.1`（右侧文件树 / 编辑器 / 变更 / 任务 / 侧聊工作台），补上窄屏 CSS 与手势避让。

**1.0.37** 修复早期版本的启动崩溃：丢失的 `node-addon-require-builtin` Android 补丁会让引擎在 host preparation 阶段退出；现以 `--expose-internals` 的 JS 回退替代，并由 `EngineService.ensureRuntimePatch()` 每次启动写入。同时加入侧栏左右滑开合、Kokona 签名展开按钮，修掉 shim 冲突让键盘 inset 真正生效。

**1.0.37** 安全加固：Java 侧 `/plus` 独立复核 `action → capability`，`deny` 最终生效，`ask` 需一次性 120 秒 grant；token 改 `Authorization: Bearer`、控制文件 0600；确认框按真实前后台可见性判定，最多 3 个待处理、间隔 1.2 秒；改授权、重置、切模式、请求 Root/Shizuku 均需原生确认。

**1.0.37** 设置页右上角加入终端入口；为 Android 交叉编译 `node-pty@1.2.0-beta.15` 的 N-API 模块；包体瘦身（移除 146 MB 办公 WASM、桌面原生二进制、`.d.ts` 与重复 `zod/src`），APK 约 90.3 MiB（94,684,584 字节）；软键盘遮挡适配（viewport meta、`visualViewport` inset、原生 IME 兜底）。

</details>

## 运行时定制

构建树中已应用的品牌 / 移动端定制（升级 DSH 时需重新移植）：

- `dsh-client-ui-settings-general`：**已退回官方原版**，不再打任何设置页补丁；竖屏文字横排与「打开配置文件」隐藏由 WebView shim 提供，运行模式 / 权限 / 终端入口改由 `kha-setthings` 插件提供。
- `dsh-client-ui-sidebar`：收起为小 K，展开为完整 Kokona 签名 + HARNESS；App 侧另将完全收起的展开按钮换成签名图，并加入左右滑手势。
- `dsh-client-ui-conversation`：居中布局、移除 Hero 小 K 标记。
- `dsh-client-ui-brand-official`：品牌签名与 HARNESS pill。
- `dsh-client-ui-settings-models`：移动端模型行布局。
- `dsh-client-ui-plugin-manager`：品牌文案。
- `dshmarket`：关闭自带侧栏市场行（`tools/patch-dshmarket-sidebar.mjs`），避免与插件页图标重复。
- `dsh-better-sidebar`：随包预装，bundle 自挂载；App 侧窄屏 CSS 与手势避让。
- `dsh-web-frontend`：标题、KokonaBridge shim、背景层。
- `dsh-fs-local`：Android 硬链接不可用时的独占复制回退。
- `node-addon-require-builtin`：Android 无预编译绑定，`lib/index.js` 换成基于 `--expose-internals` 的 JS 回退（`runtime-patches/node-addon-require-builtin-android.js`）。
- `dsh-node-addon-system`、`dsh-api-workspace-controller`、`dsh-attachment-local`、`dsh-session-persistence-jsonl` 等兼容补丁。

## 版本

- App：**1.1.0**（versionCode 149）
- DSH：**0.2.2-rc.1**
- KokonaRuntime：**dsh-0.2.2-rc.1-r33**
- 平台：Android 8.1+（API 27）/ arm64-v8a / `targetSdk 28`
- KokonaRuntime 快照 `runtime.tar.xz` 约 97 MiB、解压约 583 MiB（不入库，随 Release 的 APK 分发）
- 仓库：public · Release 以当前版本为准，跳过此前所有版本
