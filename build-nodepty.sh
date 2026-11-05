#!/usr/bin/env bash
# Compiles node-pty for Android arm64 (bionic). The resulting N-API module is
# placed under prebuilds/android-arm64 so node-pty's own loader finds it
# without any JavaScript patch.
set -euo pipefail

BUILD="${BUILD_DIR:-/workspace/build}"
NDK="${ANDROID_NDK_HOME:-$BUILD/android-sdk/ndk/27.2.12479018}"
NODE_MODULES="${NODE_MODULES:-$BUILD/rt/node_modules}"
NODE_INC="${NODE_INC:-$BUILD/termux-debs/ext/nodejs/data/data/com.termux/files/usr/include/node}"
NAPI_INC="$NODE_MODULES/node-addon-api"
PTY_DIR="$NODE_MODULES/node-pty"
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android27-clang++"

die() { echo "error: $*" >&2; exit 1; }

[ -x "$CXX" ] || die "missing NDK clang: $CXX"
[ -f "$NODE_INC/node_api.h" ] || die "missing Node headers: $NODE_INC"
[ -f "$NAPI_INC/napi.h" ] || die "missing node-addon-api headers: $NAPI_INC"
[ -f "$PTY_DIR/src/unix/pty.cc" ] || die "missing node-pty sources: $PTY_DIR"

for arch in android-arm64 linux-arm64; do
  mkdir -p "$PTY_DIR/prebuilds/$arch"
  "$CXX" -shared -fPIC -O2 -std=c++17 \
    -I"$NODE_INC" -I"$NAPI_INC" -I"$PTY_DIR/src" \
    -o "$PTY_DIR/prebuilds/$arch/pty.node" \
    "$PTY_DIR/src/unix/pty.cc" \
    -static-libstdc++
done

echo "built:"
ls -l "$PTY_DIR"/prebuilds/*/pty.node
