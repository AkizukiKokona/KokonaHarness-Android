---
name: glibc-runtime
description: 内置 KokonaGlibcRunner（kokona-grun）。当需要运行以 GNU/glibc 链接的 Linux arm64 程序（报 ld-linux 或 libc.so.6 缺失）时使用；bionic 程序不要用它。
whenToUse: 运行 file 显示 interpreter 为 /lib/ld-linux-aarch64.so.1 的程序、报 "No such file or directory" 但文件存在、报 libc.so.6 缺失、或下载了官方 Linux arm64 二进制时。
---

# KokonaGlibcRunner（glibc 程序按需运行）

运行时自带一套独立 glibc（位于 `KokonaRuntime/kokona-glibc`）。**默认所有命令仍走 bionic，性能不变**；只有 glibc 程序才需要 `kokona-grun`。没有 proot、没有 ptrace，启动接近原生。

## 判断要不要用

先看 ELF 解释器：

```bash
file ./binary
# interpreter /system/bin/linker64 → bionic 程序，直接运行，不要用 grun
# interpreter /lib/ld-linux-aarch64.so.1 → glibc 程序，用 kokona-grun
# statically linked → 直接运行
```

快速症状对照：

- `cannot open shared object file: libc.so.6` → glibc 程序
- `No such file or directory`（文件明明存在、`ls` 看得见）→ 缺 loader 或缺某个 `.so`
- 报缺 `libstdc++.so.6`、`libgcc_s.so.1`、`libssl.so.3` 等 → glibc 程序缺库，见下文
- 早期版本在缺库/丢执行位时会把 `EACCES` 折叠成 `Permission denied`，**现已不会**：缺什么就报 `No such file or directory`，`Permission denied` 现在真的表示权限问题

## 用法

```bash
kokona-grun ./binary --version        # 基本运行
kokona-grun -- ./binary --flag        # 参数以 - 开头时用 -- 分隔
```

- `kokona-grun` 自动：清掉 bionic 的 `LD_LIBRARY_PATH`/`LD_PRELOAD`、写好 DNS（App 写入的实时 DNS 列表 `etc/dns.servers` → `getprop net.dns1-4` → 公共 DNS 兜底，生成 `kokona-glibc/etc/resolv.conf`）、指向内置 CA。
- 会话内多次运行同一程序时环境已就绪，无额外成本。

### 第一个参数必须是路径，不能是裸命令名

`kokona-grun` 把参数直接交给 loader，**loader 不做 `PATH` 查找**：

```bash
kokona-grun getconf LONG_BIT     # ✗ rc=127，getconf: No such file or directory
kokona-grun ./getconf LONG_BIT   # ✓ 但 glibc 的 bin 不在 PATH，仍可能找不到
kgetconf LONG_BIT                # ✓ 推荐：用 k 前缀启动器
```

### glibc 工具用 `k` 前缀名

`kokona-glibc/bin` **不会**加入 `PATH`（防止与 bionic 的同名命令串层）。需要 glibc 版工具时用前缀名：

| 前缀命令 | 对应 glibc 工具 |
|---|---|
| `kiconv` | iconv（支持 GBK / BIG5 / EUC-KR / EUC-JP / GB18030 等全套 CJK 编码） |
| `kgetent` | getent（hosts / services / protocols / networks 均可查） |
| `kgetconf` | getconf |
| `klocale` | locale |
| `kldd` | ldd（列出动态依赖；内部走 `/system/bin/sh`，并把 `~/.local/lib/kokona-glibc` 通过 `LD_LIBRARY_PATH` 传下去，所以补的库也能看到） |
| `kgencat` | gencat |
| `kpldd` | pldd（列出进程加载的共享库） |

这些启动器都在 `KokonaRuntime/bin`，内部自己找到 glibc 前缀，可直接在 `PATH` 里调用。

## 缺库怎么办

```bash
kokona-grun ./binary 2>&1 | grep -o 'lib[^ ]*\.so[^ ]*'   # 看缺哪个
$BUILD_TMP/llvm-readelf -d ./binary 2>/dev/null || readelf -d ./binary | grep NEEDED
```

1. 先找现成库：很多发行版的 arm64 包可以直接解包，把 `.so` 放进 `~/.local/lib/kokona-glibc/`（此目录已被 grun 加入搜索路径）。
2. 放好后直接重跑 `kokona-grun ./binary`。
3. 不要把库放进系统目录，也不要改二进制的 DT_NEEDED。

## DNS / TLS

- DNS 走 `KokonaRuntime/kokona-glibc/etc/resolv.conf`。每次 `kokona-grun` 都会先读 App 写入的 `etc/dns.servers`（当前网络的实时 DNS），读不到再退到 `getprop net.dns1-4`，都拿不到才用 `1.1.1.1`/`8.8.8.8`。手动改会被下次运行覆盖。
- 顺序是有原因的：Android 10+ 不再发布 `net.dns*` 属性，只有 App 写的 `dns.servers` 才反映当前网络。
- 排查 DNS 时先 `cat KokonaRuntime/kokona-glibc/etc/resolv.conf`，确认里面有 `nameserver` 行；再 `kgetent hosts example.com`。
- TLS 用 `SSL_CERT_FILE`（已指向内置 CA）；`/etc` 不可写，任何依赖 `/etc/resolv.conf`、`/etc/ssl` 的程序（常见于 Go/Rust 静态二进制）无法修复，只能靠程序自身环境变量。
- `hosts` 文件在 `kokona-glibc/etc/hosts`，可编辑。

## 编码与 locale

- `iconv` 的 CJK 编码可用：`printf '世界
' | kiconv -f UTF-8 -t EUC-KR`。前提是打包时把 `gconv-modules.d/gconv-modules-extra.conf` 的注册表并进了 `lib/gconv/gconv-modules`，且 `libKSC.so` / `libJIS.so` / `libCNS.so` 这些同目录表文件没被裁掉——两者缺一，CJK 就会报 `conversion to \`EUC-KR' is not supported`。
- 前缀**不带 locale 数据**，`C.UTF-8` 不可用，`kokona-grun` 默认 `LC_ALL=C`（内建、不报错、字节透明，UTF-8 文本原样透传）。自己编译了 locale 数据放进 `lib/locale` 后，显式 `LC_ALL=... kokona-grun ...` 即可生效。
- `klocale` 若仍打印三行 `Cannot set LC_CTYPE/MESSAGES/COLLATE to default locale`，**这是预期行为**：没有 `lib/locale/locale-archive` 时，逐 category 探测的 `locale` 工具一定会报；`hello`、`iconv`、UTF-8 透传都不受影响。不要为这三行去补整个 locale-archive。
- `kokona-grun --version` / `--help` 会打印前缀位置与用法，不再把 loader 自己的 usage dump 当成结果抛给用户。

## 限制

- 只支持 arm64-v8a。
- Android seccomp 屏蔽的 syscall（clone3、faccessat2、statx、epoll_pwait2、shm* 等）已内置 fake 实现（与 Termux glibc 同源）；遇到其它被屏蔽的 syscall 属于内核限制，绕不过。
- 需要 systemd/dbus 的程序无法运行（Android 没有）。
- 多线程程序若依赖 `rseq`/`set_robust_list` 已做适配，但极端调度场景可能与 bionic 行为有差异。
- `getpwuid` 对 Android app uid 返回合成的 passwd 条目（与 Termux 一致）。

## 禁止

- 不要对 bionic 程序（`/system/bin/linker64`）使用 kokona-grun。
- 不要设置全局 `LD_LIBRARY_PATH` 指向 `kokona-glibc/lib`——会把 glibc 库泄漏进 bionic 进程导致崩溃。
- 不要用 proot/chroot 方案；本运行时就是无 proot 的按需方案。
- 不要修改二进制 interpreter（除非用户明确要求，可用 `patchelf` 类工具但需说明后果）。
