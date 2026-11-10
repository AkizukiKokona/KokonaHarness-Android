#!/usr/bin/env bash
# Assembles the arm64 runtime snapshot (Node + dsh + trimmed node_modules)
# from the Termux packages and the npm tree under /workspace/build.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="${BUILD_DIR:-/workspace/build}"

# Windows 原生 node.exe 不识别 MSYS 的 /d/... 路径（会解析成 D:\d\...）；
# 脚本里 require('$RT/...') 把路径嵌在字符串内，MSYS 也无法自动转换参数。
# 统一转成混合风格 D:/...：bash 与 Windows 程序都能解析。
# Linux 原环境无 cygpath 时原样返回，保持跨平台。
to_win() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }
# GNU tar 会把 "D:/..." 里的 "D:" 当成远程主机名，文件参数需转回 /d/... 风格。
to_msys() { cygpath -u "$1" 2>/dev/null || printf '%s' "$1"; }
ROOT="$(to_win "$ROOT")"
BUILD="$(to_win "$BUILD")"

EXT="$BUILD/termux-debs/ext"
RT="$BUILD/runtime"
NODE_MODULES="$BUILD/rt/node_modules"
OUT_ASSETS="$ROOT/app/assets"

NODE_BIN="$EXT/nodejs/data/data/com.termux/files/usr/bin/node"
BASH_BIN="$EXT/bash/data/data/com.termux/files/usr/bin/bash"
RG_BIN="$EXT/ripgrep/data/data/com.termux/files/usr/bin/rg"

die() { echo "error: $*" >&2; exit 1; }

for f in "$NODE_BIN" "$BASH_BIN" "$RG_BIN"; do
  [ -f "$f" ] || die "missing $f"
done
[ -d "$NODE_MODULES/@deepseek-ai/dsh" ] || die "missing dsh install tree"

rm -rf "$RT"
mkdir -p "$RT/bin" "$RT/lib" "$RT/lib/node_modules" "$OUT_ASSETS"

copy() { cp -L "$1" "$2"; }

copy "$NODE_BIN" "$RT/bin/node"
copy "$BASH_BIN" "$RT/bin/bash"
copy "$RG_BIN" "$RT/bin/rg"

L() { echo "$EXT/$1/data/data/com.termux/files/usr/lib/$2"; }

copy "$(L libicu libicudata.so.78.3)" "$RT/lib/libicudata.so.78"
copy "$(L libicu libicui18n.so.78.3)" "$RT/lib/libicui18n.so.78"
copy "$(L libicu libicuuc.so.78.3)"   "$RT/lib/libicuuc.so.78"
copy "$(L openssl libcrypto.so.3)"     "$RT/lib/libcrypto.so.3"
copy "$(L openssl libssl.so.3)"        "$RT/lib/libssl.so.3"
copy "$(L libsqlite libsqlite3.so.3.54.1)" "$RT/lib/libsqlite3.so"
copy "$(L zlib libz.so.1.3.2)"         "$RT/lib/libz.so.1"
copy "$(L libffi libffi.so)"           "$RT/lib/libffi.so"
copy "$(L c-ares libcares.so)"         "$RT/lib/libcares.so"
copy "$(L libc++ libc++_shared.so)"    "$RT/lib/libc++_shared.so"
copy "$(L libandroid-support libandroid-support.so)" "$RT/lib/libandroid-support.so"
copy "$(L readline libreadline.so.8.3)" "$RT/lib/libreadline.so.8"
copy "$(L libiconv libiconv.so)"       "$RT/lib/libiconv.so"
copy "$(L pcre2 libpcre2-8.so)"        "$RT/lib/libpcre2-8.so"
copy "$(L ncurses libncursesw.so.6.5)" "$RT/lib/libncursesw.so.6"

mkdir -p "$RT/etc/tls"
copy "$EXT/ca-certificates/data/data/com.termux/files/usr/etc/tls/cert.pem" "$RT/etc/tls/cert.pem"

# The Termux build of libcrypto.so.3 has its OPENSSLDIR compiled in as
# /data/data/com.termux/files/usr/etc/tls. OpenSSL reads openssl.cnf from there
# at library init and treats a failure as fatal, so under any other install path
# every node start died with "OpenSSL configuration error ... Permission denied"
# before a single line of JavaScript ran. Ship our own minimal config and point
# OPENSSL_CONF at it (see EngineService) so the compiled-in path is never used.
cat > "$RT/etc/tls/openssl.cnf" <<'OPENSSL_CNF'
# Minimal OpenSSL configuration for the bundled runtime.
# INTENTIONALLY has no providers/engines/oid_section directives: anything that
# makes OpenSSL go looking for modules outside this tree fails the same way the
# compiled-in OPENSSLDIR did.
openssl_conf = kha_init

[kha_init]
# Empty on purpose — we only need a readable file so initialisation succeeds.
OPENSSL_CNF

# Small CLI tools (bionic arm64 builds from the Termux packages).
T() { echo "$EXT/$1/data/data/com.termux/files/usr/$2"; }

for tool in jq less xxd tree entr curl git nano unzip; do
  copy "$(T "$tool" "bin/$tool")" "$RT/bin/$tool"
done
copy "$(T openssl-tool bin/openssl)" "$RT/bin/openssl"

# Shared libraries these tools link against; everything else (libssl,
# libcrypto, pcre2, zlib, ncursesw, libiconv, libandroid-support) is already
# copied above from the same repository snapshot.
for pkg in jq oniguruma libbz2 libmagic libcurl libnghttp2 libnghttp3 libngtcp2 libssh2; do
  for lib in "$(T "$pkg" lib)"/*.so*; do
    [ -f "$lib" ] || continue
    [ -L "$lib" ] && continue
    base="${lib##*/}"
    [ -e "$RT/lib/$base" ] || copy "$lib" "$RT/lib/$base"
  done
done
# unzip links the SONAME, which the package ships only under its full version.
for lib in "$RT/lib"/libbz2.so.1.0.*; do
  [ -f "$lib" ] || continue
  [ -e "$RT/lib/libbz2.so.1.0" ] || copy "$lib" "$RT/lib/libbz2.so.1.0"
  break
done

# Extra small utilities: archive/compression, text conversion, inspection.
copy "$(T zip bin/zip)" "$RT/bin/zip"
copy "$(T xz-utils bin/xz)" "$RT/bin/xz"
copy "$(T zstd bin/zstd)" "$RT/bin/zstd"
copy "$(T gzip bin/gzip)" "$RT/bin/gzip"
copy "$(T bzip2 bin/bzip2)" "$RT/bin/bzip2"
copy "$(T bc bin/bc)" "$RT/bin/bc"
copy "$(T bc bin/dc)" "$RT/bin/dc"
copy "$(T dos2unix bin/dos2unix)" "$RT/bin/dos2unix"
copy "$(T dos2unix bin/unix2dos)" "$RT/bin/unix2dos"
copy "$(T file bin/file)" "$RT/bin/file"
copy "$(T ncdu bin/ncdu)" "$RT/bin/ncdu"
copy "$(T pv bin/pv)" "$RT/bin/pv"
copy "$(T sqlite bin/sqlite3)" "$RT/bin/sqlite3"
for tool in ps free uptime pgrep pkill pmap pidof pwdx watch sysctl; do
  copy "$(T procps "bin/$tool")" "$RT/bin/$tool"
done

# SONAMEs that the packages only ship under their full version.
copy "$(T liblzma lib/liblzma.so.5.8.4)" "$RT/lib/liblzma.so.5"
copy "$(T libexpat lib/libexpat.so.1.13.1)" "$RT/lib/libexpat.so.1"
copy "$(T zstd lib/libzstd.so.1.5.7)" "$RT/lib/libzstd.so.1"
copy "$(T procps lib/libproc2.so)" "$RT/lib/libproc2.so"
copy "$(T libandroid-glob lib/libandroid-glob.so)" "$RT/lib/libandroid-glob.so"

# Decompress aliases are symlinks upstream (not supported by the in-app
# extractor), so generate small shims that resolve the runtime at run time.
shim() {
  bin="$1"; flags="$2"; name="$3"
  cat > "$RT/bin/$name" <<SH
#!/system/bin/sh
rt="\${KOKONA_RUNTIME:-}"
if [ -z "\$rt" ]; then
  if [ "\${0%/*}" != "\$0" ]; then rt="\${0%/*}/.."; else rt="\${PATH%%:*}/.."; fi
fi
exec "\$rt/bin/$bin" $flags "\$@"
SH
  chmod 0755 "$RT/bin/$name"
}
shim gzip  -d  gunzip
shim gzip  -dc zcat
shim bzip2 -d  bunzip2
shim bzip2 -dc bzcat
shim zstd  -d  unzstd
shim zstd  -dc zstdcat
shim xz    -d  unxz
shim xz    -dc xzcat

# Terminal capability database for less, nano and interactive bash.
mkdir -p "$RT/share" "$RT/share/misc"
cp -a "$(T ncurses share/terminfo)" "$RT/share/terminfo"
copy "$(T libmagic share/misc/magic.mgc)" "$RT/share/misc/magic.mgc"

# Trimmed git: the main binary, the real libexec helpers that are not builtins
# (HTTPS transport), tiny wrappers for builtin helpers, and init templates.
# Upstream ships 181 libexec entries as full ~2-4 MB copies; only these are
# needed, which keeps the runtime ~13 MB smaller.
GIT_CORE="$RT/libexec/git-core"
mkdir -p "$GIT_CORE" "$RT/share/git-core"
cp -a "$(T git share/git-core/templates)" "$RT/share/git-core/templates"
copy "$(T git libexec/git-core/git-remote-http)" "$GIT_CORE/git-remote-http"
copy "$(T git libexec/git-core/git-remote-http)" "$GIT_CORE/git-remote-https"
for helper in credential-store credential-cache credential-cache--daemon \
              upload-pack receive-pack upload-archive; do
  printf '#!/system/bin/sh\nexec "${KOKONA_RUNTIME:-${PATH%%%%:*}/..}/bin/git" %s "$@"\n' "$helper" \
    > "$GIT_CORE/git-$helper"
done
# git-submodule additionally needs the 2 MB sh-i18n--envsubst helper, so that
# script (and its shell-script family) is omitted to keep the runtime small.
chmod 0755 "$GIT_CORE"/*

# Node module tree: strip symlinks and non-runtime artefacts.
cp -a "$NODE_MODULES/." "$RT/lib/node_modules/"
rm -rf "$RT/lib/node_modules/.bin"
find "$RT/lib/node_modules" -type l -delete

# node-addon-require-builtin publishes no android-arm64 optional package, so its
# native entry throws on Android and dsh-app-boot cannot prepare the host
# ("No usable native binding"). The engine already runs node with
# --expose-internals, so ship the JS entry that require()s internal modules
# directly. See runtime-patches/node-addon-require-builtin-android.js.
cp "$ROOT/runtime-patches/node-addon-require-builtin-android.js" \
   "$RT/lib/node_modules/node-addon-require-builtin/lib/index.js"

# ripgrep is selected by process.platform/arch at require time. Node on
# Android reports platform "android", so only the android-arm64 package is
# resolvable there; the glibc linux packages never load.
rm -rf "$RT/lib/node_modules/@vscode/ripgrep-linux-x64" \
       "$RT/lib/node_modules/@vscode/ripgrep-linux-arm64"
RG_PKG="$RT/lib/node_modules/@vscode/ripgrep-android-arm64"
mkdir -p "$RG_PKG/bin"
cp "$RG_BIN" "$RG_PKG/bin/rg"
RG_VERSION=$(node -p "require('$RT/lib/node_modules/@vscode/ripgrep/package.json').version")
cat > "$RG_PKG/package.json" <<JSON
{
  "name": "@vscode/ripgrep-android-arm64",
  "version": "$RG_VERSION",
  "description": "ripgrep binary for Android arm64 (Termux build)",
  "bin": { "rg": "bin/rg" }
}
JSON

# Desktop native payloads are never loadable on Android (Node reports
# platform "android", arch "arm64"): the dsh 0.2.2 tree ships linux-x64
# builds of sherpa-onnx, sharp/libvips and koffi, which only bloat the APK.
rm -rf "$RT/lib/node_modules/sherpa-onnx-linux-x64" \
       "$RT/lib/node_modules/@img/sharp-linux-x64" \
       "$RT/lib/node_modules/@img/sharp-libvips-linux-x64" \
       "$RT/lib/node_modules/@koromix/koffi-linux-x64"

# Office conversion is unavailable on Android: libreoffice-kit rejects
# platform "android" before any engine loads, so the 146 MB WASM payload is
# dead weight.
rm -rf "$RT/lib/node_modules/@deepseek-ai/libreoffice-kit-wasm"

# node-pty ships glibc/desktop prebuilds; Android uses the N-API build in
# prebuilds/android-arm64 produced by build-nodepty.sh.
rm -rf "$RT/lib/node_modules/node-pty/prebuilds/darwin-arm64" \
       "$RT/lib/node_modules/node-pty/prebuilds/darwin-x64" \
       "$RT/lib/node_modules/node-pty/prebuilds/linux-x64" \
       "$RT/lib/node_modules/node-pty/prebuilds/win32-arm64" \
       "$RT/lib/node_modules/node-pty/prebuilds/win32-x64"

# Runtime never executes TypeScript declarations or the duplicated zod
# sources npm leaves in nested installs.
find "$RT/lib/node_modules" -type d -path "*/node_modules/zod/src" -prune -exec rm -rf {} +
find "$RT/lib/node_modules" -name "*.d.ts" -delete

# pnpm bootstrapper: `dsh plugin add/remove` forwards to pnpm on PATH. pnpm 12
# ships a self-contained bionic arm64 binary for Android, so the runtime carries
# the native build instead of the JS package (which still forwarded `view` to
# npm and broke the plugin market).
PNPM_VERSION="${PNPM_VERSION:-12.16.1}"
PNPM_DIR="$BUILD/pnpm-native"
PNPM_TARBALL="$PNPM_DIR/pnpm-android-arm64.tgz"
PNPM_INSTALLED=$(node -p "require('$PNPM_DIR/package/package.json').version" 2>/dev/null || true)
if [ "$PNPM_INSTALLED" != "$PNPM_VERSION" ]; then
  rm -rf "$PNPM_DIR"
  mkdir -p "$PNPM_DIR"
  curl -sSL -o "$PNPM_TARBALL" \
    "https://registry.npmmirror.com/@pnpm/exe.android-arm64/-/exe.android-arm64-$PNPM_VERSION.tgz"
  tar -xzf "$(to_msys "$PNPM_TARBALL")" -C "$(to_msys "$PNPM_DIR")"
fi
mkdir -p "$RT/lib/node_modules/@pnpm/exe.android-arm64"
cp -a "$PNPM_DIR/package/." "$RT/lib/node_modules/@pnpm/exe.android-arm64/"

# dshmarket: built-in plugin market. It is a regular dsh bundle; the profile
# seed selects it, and its two runtime dependencies are vendored so the
# plugin works offline right after installation.
MARKET_VERSION="${MARKET_VERSION:-1.73.10}"
MARKET_DIR="$BUILD/dshmarket"
MARKET_INSTALLED=$(node -p "require('$MARKET_DIR/package/package.json').version" 2>/dev/null || true)
mkdir -p "$MARKET_DIR"
if [ "$MARKET_INSTALLED" != "$MARKET_VERSION" ]; then
  ( cd "$MARKET_DIR" && rm -rf package && curl -sSL -o dshmarket.tgz \
      "https://registry.npmmirror.com/dshmarket/-/dshmarket-$MARKET_VERSION.tgz" && tar -xzf dshmarket.tgz )
fi
mkdir -p "$RT/lib/node_modules/dshmarket"
cp -a "$MARKET_DIR/package/." "$RT/lib/node_modules/dshmarket/"
find "$RT/lib/node_modules/dshmarket" -type l -delete

UNDICI_VERSION="${UNDICI_VERSION:-7.30.1}"
UNDICI_DIR="$BUILD/dshmarket/vendor/undici"
UNDICI_INSTALLED=$(node -p "require('$UNDICI_DIR/package/package.json').version" 2>/dev/null || true)
mkdir -p "$UNDICI_DIR"
if [ "$UNDICI_INSTALLED" != "$UNDICI_VERSION" ]; then
  ( cd "$UNDICI_DIR" && rm -rf package && curl -sSL -o undici.tgz \
      "https://registry.npmmirror.com/undici/-/undici-$UNDICI_VERSION.tgz" && tar -xzf undici.tgz )
fi
mkdir -p "$RT/lib/node_modules/dshmarket/node_modules/undici"
cp -a "$UNDICI_DIR/package/." "$RT/lib/node_modules/dshmarket/node_modules/undici/"
find "$RT/lib/node_modules/dshmarket/node_modules/undici" -type l -delete

# CLI shims: resolve the runtime from $KOKONA_RUNTIME (set by the engine), or
# from $0/PATH so a shell session inside the app works too.
cat > "$RT/bin/pnpm" <<'SH'
#!/system/bin/sh
rt="${KOKONA_RUNTIME:-}"
if [ -z "$rt" ]; then
  if [ "${0%/*}" != "$0" ]; then rt="${0%/*}/.."; else rt="${PATH%%:*}/.."; fi
fi
exec "$rt/lib/node_modules/@pnpm/exe.android-arm64/pnpm" "$@"
SH
cat > "$RT/bin/dsh" <<'SH'
#!/system/bin/sh
rt="${KOKONA_RUNTIME:-}"
if [ -z "$rt" ]; then
  if [ "${0%/*}" != "$0" ]; then rt="${0%/*}/.."; else rt="${PATH%%:*}/.."; fi
fi
exec "$rt/bin/node" "$rt/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" "$@"
SH

# Python 3.15.2, cross-compiled with the Android NDK against bionic arm64.
# CPython blocks sem_open/shm_open on Android, so _multiprocessing and
# _posixshmem are intentionally absent; tkinter is absent because Android has
# no Tk. Everything else (ssl, sqlite3, ctypes/ffi, curses, readline, zlib,
# bz2, lzma, expat, hash pools) is built in.
PY_SRC="${PY_SRC:-$BUILD/py-build/pyinstall}"
# D 盘 drvfs 不保留 Unix 执行位，CPython prefix 从 WSL 复制过来后是 0644；
# 只校验文件存在即可，下面 chmod 0755 会补回执行位。
[ -f "$PY_SRC/bin/python3.15" ] || die "missing python build in $PY_SRC"
[ -f "$PY_SRC/lib/libpython3.15.so" ] || die "missing libpython3.15.so"

mkdir -p "$RT/python/bin" "$RT/python/lib"
copy "$PY_SRC/bin/python3.15" "$RT/python/bin/python3.15"
# libpython lives in $RT/lib so the engine's global LD_LIBRARY_PATH resolves it
# even when a venv symlink execs $RT/python/bin/python3.15 directly.
copy "$PY_SRC/lib/libpython3.15.so" "$RT/lib/libpython3.15.so"
cp -a "$PY_SRC/lib/python3.15" "$RT/python/lib/"
# 裁剪：测试套件、IDLE、turtle 示例、pydoc 数据，以及仅供测试用的扩展模块
rm -rf "$RT/python/lib/python3.15/test" "$RT/python/lib/python3.15/idlelib" \
       "$RT/python/lib/python3.15/turtledemo" "$RT/python/lib/python3.15/pydoc_data"
find "$RT/python/lib/python3.15/lib-dynload" -maxdepth 1 \
     \( -name "_test*" -o -name "_ctypes_test*" \) -delete
chmod 0755 "$RT/python/bin/python3.15"

# Python entry points. The interpreter locates its prefix from its own path
# ($RT/python/bin/python3.15 -> $RT/python/lib/python3.15), so PYTHONHOME must
# stay unset: exporting it makes venv ignore pyvenv.cfg and breaks the venv.
cat > "$RT/bin/python3" <<'SH'
#!/system/bin/sh
rt="${KOKONA_RUNTIME:-}"
if [ -z "$rt" ]; then
  if [ "${0%/*}" != "$0" ]; then rt="${0%/*}/.."; else rt="${PATH%%:*}/.."; fi
fi
export LD_LIBRARY_PATH="$rt/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
if [ -z "${SSL_CERT_FILE:-}" ]; then export SSL_CERT_FILE="$rt/etc/tls/cert.pem"; fi
exec "$rt/python/bin/python3.15" "$@"
SH
for alias in python python3.15; do
  sed "s|exec \"\$rt/python/bin/python3.15\" \"\$@\"|exec \"\$rt/bin/python3\" \"\$@\"|" \
    "$RT/bin/python3" > "$RT/bin/$alias"
done
for alias in pip pip3; do
  sed "s|exec \"\$rt/python/bin/python3.15\" \"\$@\"|exec \"\$rt/bin/python3\" -m pip \"\$@\"|" \
    "$RT/bin/python3" > "$RT/bin/$alias"
done

# System screenshot bridge: screencap is unusable from an app UID, so route the
# call through the accessibility service via the app's loopback endpoint.
cat > "$RT/bin/screencap" <<'SH'
#!/system/bin/sh
rt="${KOKONA_RUNTIME:-}"
run="${KOKONA_APP_RUN:-}"
if [ -z "$rt" ]; then
  if [ "${0%/*}" != "$0" ]; then rt="${0%/*}/.."; else rt="${PATH%%:*}/.."; fi
fi
if [ -z "$run" ] || [ ! -f "$run/screenshot.port" ]; then
  echo "screencap: 截图服务未就绪" >&2
  exit 1
fi
port=$(cat "$run/screenshot.port")
token=$(cat "$run/screenshot.token")
out=""
for a in "$@"; do
  case "$a" in -p|-h|-d|-t|-w|-g) ;; *) [ -z "$out" ] && out="$a" ;; esac
done
url="http://127.0.0.1:$port/screenshot?token=$token"
if [ -z "$out" ]; then
  exec "$rt/bin/curl" -sS "$url"
fi
"$rt/bin/curl" -sS -o "$out" "$url"
SH
chmod 0755 "$RT/bin/screencap"

# Privileged shell through Shizuku (advanced mode). Runs as the shell user.
cat > "$RT/bin/knhsh" <<'SH'
#!/system/bin/sh
rt="${KOKONA_RUNTIME:-}"
run="${KOKONA_APP_RUN:-}"
if [ -z "$rt" ]; then
  if [ "${0%/*}" != "$0" ]; then rt="${0%/*}/.."; else rt="${PATH%%:*}/.."; fi
fi
if [ -z "$run" ] || [ ! -f "$run/screenshot.port" ]; then
  echo "knhsh: 服务未就绪" >&2
  exit 1
fi
port=$(cat "$run/screenshot.port")
token=$(cat "$run/screenshot.token")
exec "$rt/bin/curl" -sS --get --data-urlencode "cmd=$*" \
  -H "Authorization: Bearer $token" \
  "http://127.0.0.1:$port/exec"
SH
chmod 0755 "$RT/bin/knhsh"

chmod 0755 "$RT/bin/"*
chmod 0755 "$RT/lib"/*.so
chmod 0755 "$RG_PKG/bin/rg"
chmod 0755 "$RT/lib/node_modules/@pnpm/exe.android-arm64/pnpm"

# dsh-thinking-slider: built-in bundle that adds the reasoning-effort slider.
SLIDER_VERSION="${SLIDER_VERSION:-0.3.4}"
SLIDER_DIR="$BUILD/thinking-slider"
SLIDER_INSTALLED=$(node -p "require('$SLIDER_DIR/package/package.json').version" 2>/dev/null || true)
mkdir -p "$SLIDER_DIR"
if [ "$SLIDER_INSTALLED" != "$SLIDER_VERSION" ]; then
  ( cd "$SLIDER_DIR" && rm -rf package && curl -sSL -o slider.tgz \
      "https://registry.npmmirror.com/dsh-thinking-slider/-/dsh-thinking-slider-$SLIDER_VERSION.tgz" && tar -xzf slider.tgz )
fi
mkdir -p "$RT/lib/node_modules/dsh-thinking-slider"
cp -a "$SLIDER_DIR/package/." "$RT/lib/node_modules/dsh-thinking-slider/"

# dsh-better-sidebar: right-hand workbench (file tree, editor, changes, tasks,
# side chat) that can also host native right-sidebar tabs. Bundled so the
# feature is available offline on first boot.
BSB_VERSION="${BSB_VERSION:-0.30.1}"
BSB_DIR="$BUILD/better-sidebar"
BSB_INSTALLED=$(node -p "require('$BSB_DIR/package/package.json').version" 2>/dev/null || true)
mkdir -p "$BSB_DIR"
if [ "$BSB_INSTALLED" != "$BSB_VERSION" ]; then
  ( cd "$BSB_DIR" && rm -rf package && curl -sSL -o bsb.tgz \
      "https://registry.npmmirror.com/dsh-better-sidebar/-/dsh-better-sidebar-$BSB_VERSION.tgz" \
      && tar -xzf bsb.tgz )
fi
mkdir -p "$RT/lib/node_modules/dsh-better-sidebar"
cp -a "$BSB_DIR/package/." "$RT/lib/node_modules/dsh-better-sidebar/"
find "$RT/lib/node_modules/dsh-better-sidebar" -type l -delete
# Type declarations, TypeScript sources and build scripts never run.
rm -rf "$RT/lib/node_modules/dsh-better-sidebar/lib/types" \
       "$RT/lib/node_modules/dsh-better-sidebar/src" \
       "$RT/lib/node_modules/dsh-better-sidebar/scripts"

# The host's Plugins page already owns the sidebar's plugin row, so the market
# must not add a second icon beside it. Its own row is optional by design; pin
# it off and keep the market reachable from the Plugins page tab and Settings.
node "$ROOT/tools/patch-dshmarket-sidebar.mjs" "$RT"

# Kokona wording for the new-conversation hero (English headline + Preview).
node "$ROOT/tools/patch-kokona-branding.mjs" "$RT"

# KNHPlus: Android-only MCP server (accessibility UI automation, notifications,
# media/volume/brightness, TTS) plus the bundled skill that documents it.
mkdir -p "$RT/lib/node_modules/knhplus-mcp" "$RT/skills"
cp -a "$ROOT/knhplus-mcp/." "$RT/lib/node_modules/knhplus-mcp/"
cp -a "$ROOT/runtime-skills/." "$RT/skills/"

# Trim build-only artifacts (tests, type declarations and TypeScript sources)
# from vendored packages. Node never reads them at runtime, and they are the
# largest compressible part of the bundle.
find "$RT/lib/node_modules" -type d \
  \( -name test -o -name tests -o -name __tests__ -o -name .github \) \
  -prune -exec rm -rf {} + 2>/dev/null || true
find "$RT/lib/node_modules" \( -name "*.d.ts" -o -name "*.d.mts" -o -name "*.d.cts" \) \
  -delete 2>/dev/null || true
rm -rf "$RT/lib/node_modules/zod/src" "$RT/lib/node_modules/openai/src"

# Pre-seeded profile overrides: telemetry rows need packages we trimmed.
mkdir -p "$ROOT/app/assets/seed"
cat > "$ROOT/app/assets/seed/cordis.patch.yml" <<'YAML'
- id: otel
  disabled: true
- id: session-telemetry-otel
  disabled: true
YAML

# Kokona glibc runtime: self-built glibc 2.44 (termux patchset, native ELF
# TLS, no outline atomics) + kokona-grun launcher. Bionic stays the default
# for everything; only glibc binaries opt in via kokona-grun
# (see skills/glibc-runtime). Optional: build via build/glibc-run/build.sh.
GLIBC_STAGING="$BUILD/glibc-run/staging/data/user/0"
GLIBC_SRC="$GLIBC_STAGING/com.kokonaharness.android.akizuki/files/KokonaRuntime/kokona-glibc"
if [ ! -d "$GLIBC_SRC" ]; then
  # 构建机上的 staging 目录名沿用素材打包时的包名，与 APK 的 applicationId 无关；
  # 兼容原环境按旧包名打包的素材，避免误判为缺失而跳过 kokona-glibc。
  GLIBC_SRC="$GLIBC_STAGING/com.knh.akizukikokona/files/KokonaRuntime/kokona-glibc"
fi
if [ -d "$GLIBC_SRC" ]; then
  rm -rf "$RT/kokona-glibc"
  mkdir -p "$RT/kokona-glibc"
  # -L dereferences symlinks, and NOT --preserve=links: the device-side tar
  # reader (TarExtractor) only materialises regular files and directories, so a
  # symlink or hard link in the archive simply never appears on the phone.
  # That is how lib/ld-linux-aarch64.so.1 went missing while bin/ld.so survived.
  cp -rLp "$GLIBC_SRC/." "$RT/kokona-glibc/"

  # Prefixed launchers, so the glibc prefix never has to join PATH.
  #
  # The bionic runtime owns bin/. Putting the prefix's bin on PATH only stayed
  # safe because none of its nine tools happened to share a name with a bionic
  # binary -- PATH order was the only thing keeping curl/git/node from being
  # shadowed. One packaging mistake and a bionic command silently turns into a
  # glibc one. Prefixing the names removes the possibility instead of managing
  # the risk: kiconv, kgetent, kgetconf, klocale, kldd, kgencat, kpldd.
  for tool in getconf getent iconv locale ldd gencat pldd; do
    cat > "$RT/bin/k$tool" <<EOS
#!/system/bin/sh
# k$tool - glibc $tool from the bundled kokona-glibc prefix.
# Prefixed on purpose: the bare name belongs to the bionic runtime.
d=\$(dirname "\$0")
if [ -n "\${KOKONA_GLIBC:-}" ]; then G="\$KOKONA_GLIBC"; else G="\$d/../kokona-glibc"; fi
[ -x "\$G/lib/ld-linux-aarch64.so.1" ] || { echo "k$tool: glibc runtime missing at \$G" >&2; exit 127; }
LIBPATH="\$G/lib:\$HOME/.local/lib/kokona-glibc"
# ldd is a shell script, not an ELF: exec'ing it through the loader fails with
# "invalid ELF header". Run it with the shell (its own shebang cannot be used on
# Android) and hand the extra library directory down through the environment,
# because the --library-path above only reaches the process we start ourselves.
if [ "$tool" = ldd ]; then
  LD_LIBRARY_PATH="\$LIBPATH" exec /system/bin/sh "\$G/bin/ldd" "\$@"
fi
exec "\$G/lib/ld-linux-aarch64.so.1" --library-path "\$LIBPATH" "\$G/bin/$tool" "\$@"
EOS
    chmod 755 "$RT/bin/k$tool"
  done
  # kokona-grun resolves the prefix from its own location, so a copy next to
  # the bionic tools finds ../kokona-glibc without any package name baked in.
  cp -f "$RT/kokona-glibc/bin/kokona-grun" "$RT/bin/kokona-grun"
  chmod 755 "$RT/bin/kokona-grun"
else
  echo "WARNING: glibc staging missing ($GLIBC_SRC); runtime ships without kokona-glibc" >&2
fi

echo "packing $RT ..."
TAR="$BUILD/runtime.tar"
rm -f "$TAR"
# Total payload bytes, used only to drive the install progress bar.
DATA=$(node "$ROOT/tools/runtime-manifest.mjs" "$RT")
echo "$DATA" > "$OUT_ASSETS/runtime.bytes"
tar --format=pax -cf "$(to_msys "$TAR")" -C "$(to_msys "$RT")" .
SIZE=$(stat -c %s "$TAR")
echo "$SIZE" > "$OUT_ASSETS/runtime.size"

rm -f "$OUT_ASSETS/runtime.tar.xz"
# 默认 2 线程，避免 xz -T0 占满所有核心；游戏结束后可用 KHA_XZ_THREADS=0 全速。
xz -6 -T"${KHA_XZ_THREADS:-2}" -c "$TAR" > "$OUT_ASSETS/runtime.tar.xz"
rm -f "$TAR"

echo "runtime.tar.xz: $(du -h "$OUT_ASSETS/runtime.tar.xz" | cut -f1) (raw $(numfmt --to=iec "$SIZE"))"
