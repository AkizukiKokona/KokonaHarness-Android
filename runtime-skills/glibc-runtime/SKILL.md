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
- `No such file or directory`（文件明明存在）→ glibc 程序，缺 loader
- 报缺 `libstdc++.so.6`、`libgcc_s.so.1`、`libssl.so.3` 等 → glibc 程序缺库，见下文

## 用法

```bash
kokona-grun ./binary --version        # 基本运行
kokona-grun -- ./binary --flag        # 参数以 - 开头时用 -- 分隔
```

- `kokona-grun` 自动：清掉 bionic 的 `LD_LIBRARY_PATH`/`LD_PRELOAD`、设置 `C.UTF-8`、写好 DNS（App 写入的实时 DNS 列表 → `getprop net.dns1-4` → 公共 DNS 兜底，生成 `kokona-glibc/etc/resolv.conf`）、指向内置 CA。
- 会话内多次运行同一程序时环境已就绪，无额外成本。

## 缺库怎么办

```bash
kokona-grun ./binary 2>&1 | grep -o 'lib[^ ]*\.so[^ ]*'   # 看缺哪个
$BUILD_TMP/llvm-readelf -d ./binary 2>/dev/null || readelf -d ./binary | grep NEEDED
```

1. 先找现成库：很多发行版的 arm64 包可以直接解包，把 `.so` 放进 `~/.local/lib/kokona-glibc/`（此目录已被 grun 加入搜索路径）。
2. 放好后直接重跑 `kokona-grun ./binary`。
3. 不要把库放进系统目录，也不要改二进制的 DT_NEEDED。

## DNS / TLS

- DNS 走 `KokonaRuntime/kokona-glibc/etc/resolv.conf`。每次 `kokona-grun` 都会用 App 写入的 `etc/dns.servers`（当前网络的真实 DNS）或 `net.dns1-4` 重新生成；两者都拿不到时退回 `1.1.1.1`/`8.8.8.8`。手动改会被下次运行覆盖。
- 排查 DNS 时先 `cat KokonaRuntime/kokona-glibc/etc/resolv.conf`，确认里面有 `nameserver` 行；再 `kokona-grun getent hosts example.com`。
- TLS 用 `SSL_CERT_FILE`（已指向内置 CA）；`/etc` 不可写，任何依赖 `/etc/resolv.conf`、`/etc/ssl` 的程序（常见于 Go/Rust 静态二进制）无法修复，只能靠程序自身环境变量。
- `hosts` 文件在 `kokona-glibc/etc/hosts`，可编辑。

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
