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

7. **locale**
   前缀不打包编译好的 locale 数据（体积大且需要 `localedef` 跑在目标架构上）。
   `kokona-grun` 因此把 `LANG`/`LC_ALL` 兜底成内置的 `C.UTF-8`，否则每个程序启动都会
   刷 `setlocale: LC_ALL: cannot change locale`。
