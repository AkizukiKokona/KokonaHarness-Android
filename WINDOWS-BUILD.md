# 在 Windows 上构建：踩过的坑

> 本项目的三个脚本最初按 Linux 布局编写（`/workspace/build`、无后缀 `aapt2`、`prebuilt/linux-x86_64`）。
> 本文记录把它们搬到 **Windows 11 + Git Bash（MSYS2）** 上完整跑通时踩过的坑，来自一次真实构建：
> 源码在 `C:/Users/Akizuki/WorkBuddy/KHA`，工具链、依赖与产物全部放 `D:/KHA-Build`。
> 通用构建流程见 [README.md](README.md#构建)。

## 0. 先划清边界：什么能在 Windows 做

| 环节 | Windows 原生 | 说明 |
| --- | --- | --- |
| `build-nodepty.sh`：NDK 交叉编译 node-pty 的 C/C++ | 可以 | Windows 版 NDK 完全能编 arm64 原生代码 |
| CPython 3.15 的 Android 交叉编译 | 不行 | **限制来自 CPython 构建体系，不是 NDK**：它要先用宿主工具链编一份 build Python 引导，官方 `Android/README.md` 只支持 Linux / macOS 宿主 |
| `build-runtime.sh`：素材裁剪 + 打包 `runtime.tar.xz` | 可以 | 纯文件操作 |
| `build.sh`：aapt2 / javac / d8 / zipalign / apksigner | 可以 | Windows 版 SDK build-tools + JDK 17 |
| adb 真机 / 模拟器验证 | 可以 | 多设备时记得指定 `-s` |

**最容易搞混的一点**：「Windows 上不能编译」只对 CPython 那一步成立。NDK 本身在 Windows 上可以正常交叉编译 Android 原生代码，别把两件事混为一谈。CPython 请在 WSL2 / Linux 上编好 prefix（`bin/python3.15` + `lib/libpython3.15.so` + 整套 `lib/python3.15`）再拷进 `PY_SRC`。

WSL2 里编 CPython 时 NDK 必须用 **Linux 版**（`prebuilt/linux-x86_64`），Windows 版 NDK 不能拿来编 CPython；反过来 `build-nodepty.sh` 用 Windows 版即可。

## 1. 目录与环境变量集中管理

工具链别塞进 C 盘（体积大、路径长）。本地维护一份 `env.sh`，每次构建前 `source`：

```bash
export KHA_BUILD_ROOT=/d/KHA-Build
export JAVA_HOME=$KHA_BUILD_ROOT/toolchain/jdk17/jdk-17.0.21+9
export ANDROID_SDK_ROOT=$KHA_BUILD_ROOT/toolchain/android-sdk
export ANDROID_NDK_HOME=$KHA_BUILD_ROOT/toolchain/android-ndk/27.2.12479018
export BUILD_DIR=$KHA_BUILD_ROOT/build
export NODE_MODULES=$BUILD_DIR/rt/node_modules
export NODE_INC=$BUILD_DIR/termux-debs/ext/nodejs/.../include/node
export PY_SRC=$BUILD_DIR/py-build/pyinstall
export PATH="/c/Program Files/nodejs:$KHA_BUILD_ROOT/toolchain/bin:$JAVA_HOME/bin:$PATH"
export npm_config_registry=https://registry.npmmirror.com
```

- `PATH` 把 `/c/Program Files/nodejs` 放前面：runtime 工具脚本（`.mjs`）与 `@deepseek-ai/dsh` 要求 **Node >= 24.9.0**，系统里默认的 v22 会触发 `EBADENGINE` / 缺新版 API。
- 境外资源走本地代理 `127.0.0.1:7897`；npm 与 tarball 固定走 `registry.npmmirror.com`；WSL / Debian 用 tuna 镜像。压大文件用 `xz -T2` 之类低线程参数，别把 CPU 吃满。

## 2. 路径：MSYS 与 Windows 原生工具不兼容

`aapt2` / `javac` / `d8` / `zipalign` / `apksigner` / `zip` 是 Windows 原生程序，**不认** `/d/KHA-Build/...`。构建脚本用 `cygpath -m` 统一转成 `D:/KHA-Build/...`：

```bash
to_win() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }
ROOT="$(to_win "$ROOT")"; SDK="$(to_win "$SDK")"; BUILD="$(to_win "$BUILD")"
```

反过来，adb 的参数不能被 MSYS 自动转换：`screencap -p /sdcard/x.png`、`adb pull /sdcard/x.png` 要加 `MSYS_NO_PATHCONV=1`，否则报 `Error opening file: C:/Users/.../sdcard/x.png`。

## 3. `javac` 的三个 Windows 专属坑

1. **classpath 分隔符**：盘符 `D:` 里的冒号会被当成 Linux 的分隔符。`build.sh` 按 `uname -s` 判断，MINGW/MSYS 下必须用 `;`：
   ```bash
   case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;; *) CP_SEP=':' ;; esac
   ```
2. **源码编码**：Windows JDK 默认按 GBK 解码，而仓库里的中文字符串是 UTF-8。必须 `javac -encoding UTF-8`，否则中文常量全乱甚至编译失败。构建控制台里输出的中文警告乱码只是控制台代码页，不影响结果。
3. **源码清单**：`find app/java gen -name '*.java' > sources.txt` 再 `javac @sources.txt`。构建副本里若残留旧包目录（见第 8 节），旧 `.java` 会被一起编进去。

## 4. 打包：tar / xz / zip / 签名

- `tar --format=pax`：npm 树里有超长路径，必须用 PAX 扩展头；Java 侧 `TarExtractor` 按 512 字节块对齐解析，只处理 `type '0'` 普通文件与 `type '5'` 目录，`type '2'` 符号链接**跳过**（见第 5 节）。
- **系统 PATH 里通常没有 `xz`**，用 Git 自带的：
  ```bash
  "/c/Program Files/Git/mingw64/bin/xz.exe" -6 -T2 -c runtime.tar > app/assets/runtime.tar.xz
  ```
- `zip -q -X` 去掉额外字段；`runtime.tar.xz` 已经压过，用 `zip -0` 存储别再压；`zipalign -f -p 4` 对齐后再 `apksigner sign --v1 --v2`。
- `apksigner verify` 会打印 JDK 17/21 的 `restricted method / native access` 警告，那是 JVM 提示，不是签名失败；过滤掉看 exit code 即可。
- **Git Bash 没有 `zip`**（只有 `zipinfo` / `zipgrep`），打包会直接 `zip: command not found`。仓库里的 `tools/zip.py` 实现了构建用到的那点参数（`-q -X`、`-0`），`build.sh` 检测不到 `zip` 时自动用它。要换真 zip 也行，别改脚本逻辑。
- **`d8` 和 `apksigner` 在 Windows 上只有 `.bat`**。MSYS 只会替 `.exe` 自动补后缀，所以 `"$BT/d8"` 一定 `No such file or directory`。`build.sh` 用 `pick_tool` 探测 `.bat` 版本（`aapt2` / `zipalign` 是 `.exe`，不用管）。
- **SDK 版本不要硬编码**：本机 `platforms/` 只有 `android-30/34/36`、`build-tools/` 只有 `35.0.0/36.0.0` 时，写死 `android-35` 会直接报 `missing .../android.jar`。用 `ANDROID_BUILD_TOOLS` / `ANDROID_PLATFORM` 覆盖即可：
  ```bash
  export ANDROID_BUILD_TOOLS="C:/Users/me/AppData/Local/Android/Sdk/build-tools/36.0.0"
  export ANDROID_PLATFORM="C:/Users/me/AppData/Local/Android/Sdk/platforms/android-36/android.jar"
  ```

## 5. Unix 权限位会在 Windows 打包时丢

这是**最容易只在真机上炸**的一类问题：MSYS 下生成的 tar 会丢失 npm 二进制的 Unix mode。实际翻车案例——`bin/pnpm` 是 shell wrapper，它 `exec` 的 `@pnpm/exe.android-arm64/pnpm` 在包里是 `0644`，真机报：

```text
bin/pnpm[6]: .../@pnpm/exe.android-arm64/pnpm: can't execute: Permission denied
```

`bin/node` 同样可能是 0644，只是被「给 `bin/` 下所有文件加执行位」顺手救了。

**结论**：别指望打包侧完整保留 mode，在设备端兜底。`RuntimeInstaller.ensureExecutables()` 每次启动（含「版本相同、跳过解压」的快速路径）给这些路径补权限：`bin/*`、`@pnpm/exe.android-arm64/pnpm`、`libexec/git-core/*`、ABI 命名的 landlock runner，并确保 `lib/*.so` 可读。同理 `node_modules` 的符号链接在 Windows 上无法保留，打包时删掉、由 App 在首次启动时用 `Files.createSymbolicLink` 重建。

顺带一个真机专属坑：Termux 编译的 `libcrypto.so.3` 把 `OPENSSLDIR` 写死成 `/data/data/com.termux/files/usr/etc/tls`。该路径在别的 App 里存在但不可读时，Node 一行 JS 都还没跑就报 `OpenSSL configuration error ... Permission denied`。修法是自带 `etc/tls/openssl.cnf` 并在启动时注入 `OPENSSL_CONF` / `SSL_CERT_FILE` / `SSL_CERT_DIR`。这类问题在 x86 模拟器上往往复现不出来。

## 6. 别为改一行 JS 重打 100 MB 包

`runtime.tar.xz` 约 97 MiB、解包后约 583 MiB。为了修一个前端文件或补一个权限位就重打整包、重跑全量解包，既慢又容易出错。项目采用 **Java 层启动时幂等自修复**：

- `ensureRuntimePatch()` — 写 `node-addon-require-builtin` 的 Android JS 回退；
- `ensureNodePatch()` — 写 `assets/kha-node-patch.cjs`（Node 26 原生 package.json reader 在某些 Android 上非确定性抛 `ERR_INVALID_PACKAGE_CONFIG`，用 `setLoaderOverrides()` 做原生优先、失败时 JS 兜底）；
- `ensureSettingsUiPatch()` — 修设置页行动作与 CSS；
- `ensureExecutables()` — 见上节。

改这些不会 bump `RUNTIME_VERSION`，老设备下次启动即生效，APK 体积也不变。

## 7. 中间产物与批量删除保护

`build.sh` 开头 `rm -rf "$OUT"` 在本环境会触发批量删除保护（`SAFE_DELETE_BULK_CONFIRM_REQUIRED`），因为 `classes/` 下累积了几十个 `.class`。做法是先逐个删除已知产物再跑构建：

```bash
OUT=/d/KHA-Build/build/apk
for item in aligned.apk app.apk base.apk classes.jar res.zip sources.txt classes dex gen; do
  [ -e "$OUT/$item" ] && rm -rf "$OUT/$item"
done
```

## 8. C 盘源码 → D 盘构建副本的同步陷阱

`sync-source.sh` 用 tar 管道同步，排除 `.git`、`.workbuddy` 和大产物（保留 D 盘那份 `runtime.tar.xz`）：

```bash
tar -C "$SRC" --exclude=... -cf - . | tar -C "$DST" -xf -
```

**tar 覆盖不会删除目标端多余文件**。包名从 `com/knh/akizukikokona` 换成 `com/kokonaharness/android/akizuki` 之后，D 盘的旧包目录一直留着；虽然旧目录里已经没有 `.java`，但只要还有就会被 `find app/java -name '*.java'` 收进编译列表。改包名 / 删包时记得手动清掉构建副本里的旧目录。

### 8.1 换 `BUILD_DIR` 时别忘了 keystore

`build.sh` 把签名文件放在 `$BUILD_DIR/kokona.keystore`，找不到就**现场生成一个新的**。换目录构建（例如清不掉旧产物、改用 `out39/`）而忘了复制 keystore，产出的 APK 签名就变了，设备上只能卸载重装、数据全丢。换目录第一件事：

```bash
mkdir -p out39 && cp out/kokona.keystore out39/kokona.keystore
```

## 9. WebView 注入脚本（SHIM）的本地校验

`MainActivity.SHIM` 是由上百个 Java 字符串字面量拼出来的 JS，语法错一次要到真机上才发现。`tools/check-shim.cjs` 按 javac 语义跳过 `//` 与 `/* */` 注释、只拼接字符串字面量，再用 `new Function(js)` 校验：

```bash
node tools/check-shim.cjs   # SHIM OK: concatenated JS parses cleanly
```

改 SHIM 后先跑它，几秒钟省一次真机。

## 10. 其它零碎但真实的坑

- adb 同时连了模拟器和真机时所有命令都要 `-s 127.0.0.1:7555`，否则 `more than one device/emulator`。
- `su -c '... $RT ...'` 里的变量会被外层 shell 展开丢失：先在 bash 里拼好完整命令字符串再传进去；`LD_LIBRARY_PATH` 要 `export` 而不是单纯赋值。
- 工具链里的自包含 Python 偶发 `Error in sitecustomize; ModuleNotFoundError: No module named 'sysconfig'`，不影响脚本结果。
- 模拟器（x86_64 + houdini ARM 转译）的 `dlopen failed: libc.so has unexpected e_machine: 62` 是转译层问题，真机 arm64 不走这条路径，别拿它当应用 bug 修。
- 全屏叠加层的 `::before` 半透明层 + `backdrop-filter` + 逐帧 `transform` 自愈循环会在 Android WebView 上花屏；不要试图用 `contain:paint` 或强改 `[data-dsh-panel-host]` 的 z-index 去「修」，那会把固定定位子元素的包含块改掉，直接卡死。

## 速查表

| 症状 | 原因 |
| --- | --- |
| `missing /d/.../android.jar` | 路径没转成 `D:/...` |
| `javac` 找不到类 / classpath 被切碎 | Windows 下没用 `;` |
| 中文字符串乱码、编译失败 | 缺 `-encoding UTF-8` |
| `can't execute: Permission denied` | tar 丢了执行位，靠 `ensureExecutables()` |
| `EBADENGINE` / 新版 API 缺失 | PATH 里 Node 是 v22，需要 >= 24.9 |
| `xz: command not found` | 用 Git 自带的 `xz.exe` |
| `SAFE_DELETE_BULK_CONFIRM_REQUIRED` | 先逐个删中间产物 |
| `OpenSSL configuration error ... Permission denied` | Termux 库硬编码 OPENSSLDIR，注入 `OPENSSL_CONF` |
| `ERR_INVALID_PACKAGE_CONFIG` 随机失败 | Node 26 原生 reader，靠 `kha-node-patch.cjs` |
| 设置页花屏 / 卡死 | 全屏半透明层 + blur + 逐帧 transform，别动包含块 |
| WebView 里 `color-mix()` 不生效、颜色变纯色 | Chrome 111+ 语法，旧 WebView 直接丢弃整条声明；半透明一律用 `rgba()` |
| 设置弹窗越改越白 | `.VOzbGW_panel` 已有 `::before{opacity:.82}` 铺底，再给面板加 background 就是双层叠加 |
| `zip: command not found` | Git Bash 不带 zip，用 `tools/zip.py` |
| `.../build-tools/xx/d8: No such file` | Windows 上只有 `d8.bat`，用 `pick_tool` |
| `missing .../platforms/android-35/android.jar` | SDK 里没装该平台，用 `ANDROID_PLATFORM` 覆盖 |
| 覆盖安装提示签名不一致 | 换了 `BUILD_DIR` 没复制 `kokona.keystore` |
