---
name: glibc-runtime
description: 内置 KokonaGlibcRunner（kokona-grun）。当需要运行以 GNU/glibc 链接的 Linux arm64 程序（报 ld-linux 或 libc.so.6 缺失）时使用；bionic 程序不要用它。
whenToUse: 运行 file 显示 interpreter 为 /lib/ld-linux-aarch64.so.1 的程序、报 "No such file or directory" 但文件存在、报 libc.so.6 缺失、或下载了官方 Linux arm64 二进制时。
---

# KokonaGlibcRunner（glibc 程序按需运行）

运行时自带一套独立 glibc（位于 `KokonaRuntime/kokona-glibc`）。**默认所有命令仍走 bionic，性能不变**；只有 glibc 程序才需要 `kokona-grun`。没有 proot、没有 ptrace。1.1.0 起 `kokona-grun` 是**原生 ELF launcher**，不再经过 shell，启动接近裸 loader。

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

- `kokona-grun` 自动：清掉 bionic 的 `LD_LIBRARY_PATH`/`LD_PRELOAD`、按需重写 DNS（只读 App 写入的 `etc/dns.servers`，逐行校验，结果与现有 `resolv.conf` 相同就**不写**）、强制 `C` locale、指向内置 CA。**不调用任何外部命令，也不调用 `getprop`**。
- `--exec` 跳过全部环境准备；`--shell` 一次准备后从 stdin 逐行读取命令连续执行。
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
| `kldd` | ldd（列出动态依赖；**绕开 ldd 脚本**，直接用 loader 的 `--list` + `--library-path`，不设任何环境变量，所以 `~/.local/lib/kokona-glibc` 里补的库也能看到，bionic 进程又不会被污染） |
| `kgencat` | gencat |
| `kpldd` | pldd（列出进程加载的共享库） |

这些启动器都在 `KokonaRuntime/bin`，**它们是同一个 ELF 的实体副本**（`app/native/kokona-run.c`），由 `argv[0]` 决定跑哪个工具。设备端 tar 解包器会跳过符号链接/硬链接，所以不能用链接省空间。可直接在 `PATH` 里调用。

## 性能特征

- `kokona-grun` 是**原生 ELF launcher**（`app/native/kokona-run.c`），不是 sh 脚本：进程内准备 DNS / locale / 前缀 / 环境变量，最后一次 `execve()` 直达 glibc loader。零 toybox sh 启动、零 fork、零外部命令、零管道。
- 旧版 170 行脚本在低端机上单次 175–192ms，其中约 65% 花在 DNS 段的 `dirname` / `tr` / `getprop` / `cd+pwd`；同一程序直接 exec loader 约 6ms，bionic 程序基线约 26ms。**慢的一直是脚本层，不是 glibc**。
- 现在每次调用的开销是**固定的启动税**，与程序运行时长、IO 次数无关；这和 proot 那种按 syscall 逐次 ptrace 拦截、随 IO 增长的开销性质完全不同。
- `--exec` 连环境准备都跳过；`--shell` 在一个进程里连续跑多个 glibc 程序，只付每个程序的 loader 成本。

## 缺库怎么办

```bash
kokona-grun ./binary 2>&1 | grep -o 'lib[^ ]*\.so[^ ]*'   # 看缺哪个
$BUILD_TMP/llvm-readelf -d ./binary 2>/dev/null || readelf -d ./binary | grep NEEDED
```

1. 先找现成库：很多发行版的 arm64 包可以直接解包，把 `.so` 放进 `~/.local/lib/kokona-glibc/`（此目录已被 grun 加入搜索路径）。
2. 放好后直接重跑 `kokona-grun ./binary`。
3. 不要把库放进系统目录，也不要改二进制的 DT_NEEDED。

## DNS / TLS

- DNS 走 `KokonaRuntime/kokona-glibc/etc/resolv.conf`。launcher 只读 App 写入的 `etc/dns.servers`，逐行校验 `nameserver <合法 IPv4/IPv6>`，垃圾行/注释/半截行一律丢弃；文件不存在或全部无效才用 `1.1.1.1`/`8.8.8.8`。**结果相同就不写文件**，所以内容没变时 mtime 保持不变。
- Android 10+ 不再发布 `net.dns*` 属性，而且 `getprop` 是外部命令（每次 20ms 级），热路径已彻底移除；当前网络的实时 DNS 只由 App 写进 `dns.servers`。
- 排查 DNS 时先 `cat KokonaRuntime/kokona-glibc/etc/resolv.conf`，确认里面有 `nameserver` 行；再 `kgetent hosts example.com`。
- TLS 用 `SSL_CERT_FILE`（已指向内置 CA）；`/etc` 不可写，任何依赖 `/etc/resolv.conf`、`/etc/ssl` 的程序（常见于 Go/Rust 静态二进制）无法修复，只能靠程序自身环境变量。
- `hosts` 文件在 `kokona-glibc/etc/hosts`，可编辑。

## 编码与 locale

- `iconv` 的 CJK 编码可用：`printf '世界
' | kiconv -f UTF-8 -t EUC-KR`。前提是打包时把 `gconv-modules.d/gconv-modules-extra.conf` 的注册表并进了 `lib/gconv/gconv-modules`，且 `libKSC.so` / `libJIS.so` / `libCNS.so` 这些同目录表文件没被裁掉——两者缺一，CJK 就会报 `conversion to \`EUC-KR' is not supported`。
- 前缀**不带 locale 数据**，只支持 `C`/`POSIX`。launcher 在检测到没有 `lib/locale/locale-archive` 时把 **`LANG` 与 `LC_ALL` 一起强制为 `C`**（只改 `LC_ALL`、留着 `LANG=en_US.UTF-8` 会让 `locale` 继续刷警告）。C 是内建、零警告、字节透明，UTF-8 文本原样透传。
- 因此 `klocale` 不再输出 `Cannot set LC_*` 三行警告；普通程序与 `iconv` 的 CJK 转换都不受影响。
- 自己编译了 locale 数据放进 `lib/locale` 后，launcher 会保留调用方显式给的 `LANG`/`LC_ALL`。
- `kokona-grun --version` / `--help` 会打印友好用法，无参数给明确提示并 `exit 127`。

## 限制

- 只支持 arm64-v8a。
- Android seccomp 屏蔽的 syscall（clone3、faccessat2、statx、epoll_pwait2、shm* 等）已内置 fake 实现（与 Termux glibc 同源）；遇到其它被屏蔽的 syscall 属于内核限制，绕不过。
- 需要 systemd/dbus 的程序无法运行（Android 没有）。
- 多线程程序若依赖 `rseq`/`set_robust_list` 已做适配，但极端调度场景可能与 bionic 行为有差异。
- `getpwuid` 对 Android app uid 返回合成的 passwd 条目（与 Termux 一致）。
  ### getconf 变量限制

  `kgetconf` / `kokona-grun .../getconf` 并非所有变量都可用。

  - **已知缺失**：`PATH_SEPARATOR`，执行会返回 `Unrecognized variable`。这是上游变量表本身的范围（glibc 2.44 的 `posix/posix-conf-vars.list` 未含该名称），**不是打包丢文件**，不要指望它出现；补它需要改 glibc 的 `posix/getconf.c` 并重新裁剪 glibc 主体，属核心库改动，已明确不做。
  - **已确认可用（实测输出见提交说明）**：`GNU_LIBC_VERSION`、`LONG_BIT`、`WORD_BIT`、`_POSIX_VERSION`、`ARG_MAX`、`CHAR_BIT` 均可直接返回；`PATH_MAX` 需要路径参数（`getconf PATH_MAX /` -> `4096`），裸变量名会打印用法并以 rc=2 退出。
  - 需要路径分隔符时直接用字面 `:`（或读 `$PATH_SEPARATOR` 环境变量，POSIX 约定它就是 `:`），不要用 `getconf` 去查。
  - 需要其它 glibc 变量时请先用 `kgetconf -a` 试跑确认。
- **Android 没有 `/tmp`**：系统不提供该目录，标准做法是使用应用缓存目录 `$TMPDIR`（由 App 注入且可写）。硬编码 `/tmp` 的程序会因路径不存在而失败，需要临时目录时一律读 `$TMPDIR`。

## 禁止

- 不要对 bionic 程序（`/system/bin/linker64`）使用 kokona-grun——实测会报 `libc.so invalid ELF header` / `No such file or directory`。
- 不要设置全局 `LD_LIBRARY_PATH` 指向 `kokona-glibc/lib`——会把 glibc 库泄漏进 bionic 进程导致崩溃。最典型的现场是：用 `/system/bin/sh` 跑 `ldd` 脚本时顺手导出了它，`sh` 自己就会去加载 `libc.so`（链接脚本、不是 ELF）→ `CANNOT LINK EXECUTABLE "/system/bin/sh": ... has bad ELF magic`。`kldd` 之所以用 loader 的 `--list`，就是为了完全不碰这个变量。
- 不要用 proot/chroot 方案；本运行时就是无 proot 的按需方案。
- 不要修改二进制 interpreter（除非用户明确要求，可用 `patchelf` 类工具但需说明后果）。
