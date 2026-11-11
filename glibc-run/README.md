# kokona-glibc：自建 glibc 2.44 前缀

KokonaHarness 只在需要跑 glibc 程序时才用这套前缀，bionic 仍是默认。前缀里的
`--prefix` 是**编译期写死**的，因此它必须与 APK 的 `applicationId` 完全一致：

```
/data/user/0/com.kokonaharness.android.akizuki/files/KokonaRuntime/kokona-glibc
```

换包名就得重新编译，改配置或改字符串都不行 —— `libc.so.6` 里的 `SYSCONFDIR`
（`/etc/resolv.conf`、`hosts`、`nsswitch.conf`、`services`）是常量，不是可替换的字符串。

## 依赖

| 素材 | 说明 |
|---|---|
| Android NDK 27.2 | 默认路径 `/opt/android-sdk/ndk/27.2.12479018`，可用 `NDK` 覆盖 |
| glibc 2.44 源码 | `https://ftp.gnu.org/gnu/glibc/glibc-2.44.tar.xz` |
| termux patch 集 | `git clone --depth 1 https://github.com/termux-pacman/glibc-packages`，用 `gpkg/glibc/*.patch` |
| `api-headers.pkg.tar.xz` | termux glibc 的内核/系统头文件包，解压后应在 `api-headers/data/data/com.termux/files/usr/glibc/include` |
| `jq`、`gawk`、`bison`、`make`、`gcc` | 构建机上的工具（`jq` 用来生成禁用 syscall 表，缺了会静默少生成） |

## 步骤

```bash
./prepare.sh     # 打 29 个 termux patch，生成 android_ids.h 与 disabled-syscall.h
./build.sh       # configure + make -j3
./assemble.sh    # make install 到 staging，裁剪、生成 kokona-grun、修权限
```

产物：`staging/data/user/0/<pkg>/files/KokonaRuntime/kokona-glibc`，约 5.8 MB。
`build-runtime.sh` 会把它复制进 `KokonaRuntime` 并打进 `runtime.tar.xz`。

## 踩过的坑

1. **`libgcc-name` 多一层 `bin/`**
   `$TC` 已经指向 `.../prebuilt/linux-x86_64/bin`，所以运行库在 `$TC/../lib/clang/...`；
   写成 `$TC/lib/...` 会在链接 `librtld.map` 时报找不到
   `libclang_rt.builtins-aarch64-android.a`。

2. **NDK 没有 `crtbegin.o` / `crtend.o`**
   它只提供 `crtbegin_so.o` / `crtbegin_dynamic.o` / `crtbegin_static.o`，而 glibc 用
   `$(CC) --print-file-name=crtbegin.o` 定位。把 Debian 交叉 gcc 的
   `/usr/lib/gcc-cross/aarch64-linux-gnu/*/crt*.o` 复制到
   `.../lib/clang/18/lib/linux/` 即可（`crtbeginT.o`、`crtbeginS.o`、`crtendS.o` 也要）。

3. **内核头文件路径**
   `api-headers.pkg.tar.xz` 解压出的是 `data/...`，而脚本期望 `api-headers/data/...`，
   需要先 `mv data api-headers/data`，否则 configure 报
   "kernel header files missing or too old"。

4. **`support/links-dso-program` 编不过**
   它是 C++ 测试辅助，NDK 的 libc++ 头与 glibc 目标头互斥（`<cctype>` 找不到 libc++ 版）。
   用 `make -k` 跳过即可，`make install` 不需要它。

5. **符号链接会被设备端丢掉**
   设备上的解包器（`TarExtractor`）只处理普通文件与目录，tar 里的 `type 1`（硬链接）和
   `type 2`（符号链接）**直接跳过**。这就是 `lib/ld-linux-aarch64.so.1` 在手机上不存在、
   `kokona-grun` 第 5 行判定失败退出 127 的原因。所以：`assemble.sh` 结尾把前缀内所有链接
   实体化，`build-runtime.sh` 用 `cp -rLp` 复制，二者缺一不可。

6. **执行位**
   `bin/` 下的工具（含 loader）必须是 0755。丢执行位时 Android 会把 `EACCES` 折叠成
   `ENOENT`，报错看起来像"缺库"，其实是没权限 —— 用 `chmod` 修，不要重编。

7. **locale：C.UTF-8 其实不存在**
   前缀不打包编译好的 locale 数据。`kokona-grun` 早期把 `LANG`/`LC_ALL` 兜底成 `C.UTF-8`，
   但这份 glibc 里根本没有 `C.UTF-8` 的数据（2.44 的源码树里没有 `localedata/locales/C.UTF-8`），
   于是 `locale` 每次都会刷三行 `Cannot set LC_* to default locale`。
   两条弯路都试过，都失败：
   - 用宿主（x86_64，glibc 2.41）的 `localedef` 生成 `locale-archive`：格式魔数对得上，但数据
     结构不兼容，aarch64 glibc 读到时直接 `stack smashing detected` 中止 —— 比三行警告更糟；
   - 用 `build/locale/localedef`（2.44，注意 2.44 把 localedef 从顶层 `localedef/` 挪进了
     `locale/programs/`）在本机跑：需要 `share/i18n/{locales,charmaps}`，生成的 archive 仍然
     让 `locale` 崩。
   结论：**默认 `LC_ALL=C`**。C 是内建的、零警告、字节透明（UTF-8 文本原样透传），
   调用方显式给 `LC_ALL` 时优先用调用方的值。

8. **gconv：注册表和模块表是两个坑**
   - 注册表：glibc 把 `iconvdata` 的模块清单装成 `gconv-modules.d/gconv-modules-extra.conf`
     （524 条），主 `gconv-modules` 只有 26 条 Latin/UTF。不把 fragment 并进主文件，
     `iconv -t EUC-KR` 一律 `not supported`，而 `.so` 明明都在。
   - 模块表：`EUC-KR.so` 会 dlopen 同目录的 `libKSC.so`（`EUC-JP.so` → `libJIS.so`，
     `BIG5.so` → `libCNS.so`）。按名字白名单裁剪 gconv 目录，等于精确地把 CJK 弄坏。
     整个目录不到 1 MB，别裁。

9. **etc/services 等表** `getent services/protocols/networks` 读的是纯文本表，缺文件直接
   `rc=2`。仓库里 `glibc-run/etc/` 放了一份随包发，`assemble.sh` 优先用仓库副本。

10. **PATH 隔离：别让前缀的 bin 进 PATH**
   `kokona-glibc/bin` 与 bionic 的 `KokonaRuntime/bin` 目前零撞名，靠的是 PATH 顺序 ——
   哪天某个 bionic 同名文件被塞进来，`curl`/`git`/`node` 就会静默变成 glibc 版。
   现在改成**前缀别名**：`kiconv / kgetent / kgetconf / klocale / kldd / kgencat / kpldd`
   放在 `KokonaRuntime/bin`（外加 `kokona-grun` 本体，它按自身位置推导前缀），
   `kokona-glibc/bin` 不再进 PATH，两个命名空间结构上不可能重叠。

11. **`ldd` 是脚本，三轮才走对**
    `kokona-glibc/bin/ldd` 是 `#!/system/bin/sh` 的纯脚本（Android 没有 `/bin/bash`），
    它的 `RTLDLIST` 只写了裸 loader 路径，**不带** `--library-path`。三条路里前两条都是坑：

    | 版本 | 做法 | 结果 |
    |---|---|---|
    | r36 | 把脚本交给 loader exec | `invalid ELF header`（loader 只认 ELF） |
    | r37 | `/system/bin/sh` 跑脚本，同时导出 `LD_LIBRARY_PATH="$G/lib:..."` | `CANNOT LINK EXECUTABLE "/system/bin/sh": ... libc.so has bad ELF magic` |
    | r38 | **绕开脚本**，`ld.so --library-path "$LIBPATH" --list "$@"` | 正常 |

    r37 的错在于：被 exec 的是 **bionic** 的 `/system/bin/sh`，它启动时就去
    `LD_LIBRARY_PATH` 找 `libc.so`，撞上 glibc 的 `libc.so`（链接脚本、非 ELF）直接起不来。
    这正是 skill 里明令禁止的那条「不要把前缀泄漏进 bionic 进程」。
    `--list` 是 loader 自带的依赖分析模式（ldd 内部用的就是它），不需要 shell；
    `--library-path` 同时带上 `$G/lib` 与 `~/.local/lib/kokona-glibc`，补充库照样解析，
    而且**一个环境变量都不碰**。只有 `--help` / `--version` / 无参数这类纯输出分支才回落到
    `/system/bin/sh "$G/bin/ldd"`（同样不导出任何变量）。

12. **resolv.conf：一行一条，且逐行校验**
    glibc 的 resolver 按行解析，`nameserver 1.1.1.1 nameserver 8.8.8.8` 只会取第一个——
    之前多个 DNS 被拼成一行，等于只有第一个服务器生效。现在每条一行。
    `etc/dns.servers` 是 App 写的，不能整段照抄：加注释、半截行、`garbage line` 都会进
    resolver。逐行校验 `nameserver` + 合法 IPv4（四段 0-255）/IPv6（≥2 个冒号），
    全部无效时回落 `getprop net.dns1-4`，再回落公共 DNS。
