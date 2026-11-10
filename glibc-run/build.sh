#!/usr/bin/env bash
# Build KokonaGlibcRunner: glibc 2.44 for Android aarch64, own prefix.
# Run prepare.sh first. Produces build/ (object tree) and install via
# assemble.sh; this script only runs configure + make.
set -euo pipefail
cd "$(dirname "$0")"

NDK=/opt/android-sdk/ndk/27.2.12479018
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
API=24
BASE=/data/user/0/com.kokonaharness.android.akizuki/files/KokonaRuntime
PREFIX=$BASE/kokona-glibc
HEADERS=$PWD/api-headers/data/data/com.termux/files/usr/glibc/include

export PATH=$TC:$PATH
export CC=$TC/aarch64-linux-android${API}-clang
export CXX=$TC/aarch64-linux-android${API}-clang++
export AR=$TC/llvm-ar
export RANLIB=$TC/llvm-ranlib
export NM=$TC/llvm-nm
export STRIP=$TC/llvm-strip
export OBJCOPY=$TC/llvm-objcopy
export OBJDUMP=$TC/llvm-objdump
export READELF=$TC/llvm-readelf
export BUILD_CC=gcc
export MAKEINFO=true
# -fno-emulated-tls is mandatory: clang defaults Android triples to emulated
# TLS, which glibc cannot use (it implements ELF TLS itself; emulated TLS
# crashes clang on TLS aliases and would emit __emutls_* references).
# -mno-outline-atomics keeps 32-bit __atomic ops inline (LL/SC): outline
# atomics pull compiler-rt helpers whose Android init reads bionic's
# __system_property_get, which glibc must not depend on.
export CFLAGS="-O2 -fno-emulated-tls -mno-outline-atomics -Wno-error=implicit-function-declaration"

mkdir -p build
cd build
if [ ! -f config.status ]; then
  cat > configparms <<EOF
slibdir=$PREFIX/lib
rtlddir=$PREFIX/lib
sbindir=$PREFIX/bin
rootsbindir=$PREFIX/bin
libgcc-name=$TC/../lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a
EOF
  ../src/configure \
    --prefix=$PREFIX \
    --libdir=$PREFIX/lib \
    --libexecdir=$PREFIX/lib \
    --includedir=$PREFIX/include \
    --host=aarch64-linux-android \
    --build=x86_64-pc-linux-gnu \
    --with-headers=$HEADERS \
    --with-pkgversion="Kokona glibc 2.44 for Android" \
    --with-bugurl=https://github.com/owner/kokona/issues \
    --enable-bind-now \
    --enable-fortify-source \
    --disable-multi-arch \
    --enable-stack-protector=strong \
    --disable-nscd \
    --disable-profile \
    --disable-werror \
    --disable-default-pie \
    --enable-memory-tagging
fi

# CONFIGURE_ONLY=1 skips the make step (build-loop.sh drives make instead).
[ "${CONFIGURE_ONLY:-0}" = "1" ] || make -j3 O=$PWD
echo "build: OK"
