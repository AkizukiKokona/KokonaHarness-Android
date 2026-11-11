#!/usr/bin/env bash
# Assemble KokonaGlibcRunner staging tree from the glibc build.
# Produces staging/kokona-glibc (lib/bin/etc), trimmed for embedding into the
# KokonaRuntime tarball as $RT/kokona-glibc.
set -euo pipefail
cd "$(dirname "$0")"

BASE=/data/user/0/com.kokonaharness.android.akizuki/files/KokonaRuntime
PREFIX=$BASE/kokona-glibc
STG=$PWD/staging
DEST=$STG/$PREFIX

rm -rf "$STG"
mkdir -p "$STG"
make -C build install DESTDIR="$STG" O=$PWD/build

cd "$DEST"

# --- trim: runtime-only, no headers/statics/locales/docs --------------------
rm -rf include share doc var sbin
find lib -name '*.a' -delete
find lib -name '*.o' -delete
rm -f lib/libnss_test* lib/libnss_testpkg*
# helper tools that need share/i18n or are of no use on device
rm -f bin/localedef bin/makedb bin/iconvconfig bin/pcprofiledump \
      bin/sprof bin/sln
# locales: keep C.UTF-8 + en_US.UTF-8 only
if [ -d lib/locale ]; then
  find lib/locale -mindepth 2 -maxdepth 2 \
    ! -name 'C.utf8' ! -name 'en_US' -exec rm -rf {} + 2>/dev/null || true
fi
# gconv: keep EVERYTHING. Two traps live here and both cost a release:
#   1. the CJK modules dlopen their per-charset tables from the same directory
#      (EUC-KR.so -> libKSC.so, EUC-JP.so -> libJIS.so, BIG5.so -> libCNS.so),
#      so a name whitelist silently breaks exactly the encodings people want;
#   2. glibc installs the iconvdata registry as a *fragment* under
#      gconv-modules.d/, and the single gconv-modules file only carries the 26
#      Latin/UTF entries. Loaders that do not read the fragment directory end up
#      with "conversion to `EUC-KR' is not supported" for every CJK charset
#      even though all the .so files are sitting right there.
# The whole directory is under 1 MB, so there is nothing to gain by trimming.
if [ -d lib/gconv ]; then
  rm -f lib/gconv/gconv-modules.cache
  extra=$OLDPWD/build/iconvdata/gconv-modules.d/gconv-modules-extra.conf
  if [ -f "$extra" ] && ! grep -q 'iconvdata modules' lib/gconv/gconv-modules; then
    printf '\n# --- iconvdata modules (CJK, Cyrillic, legacy code pages) ---\n' \
      >> lib/gconv/gconv-modules
    grep -v '^#' "$extra" | sed '/^[[:space:]]*$/d' >> lib/gconv/gconv-modules
  fi
fi

# --- launcher ----------------------------------------------------------------
rm -f bin/tzselect bin/zdump bin/zic bin/ldconfig bin/mtrace bin/xtrace \
      bin/sotruss bin/lddlibc4 2>/dev/null || true
# 1.1.0: kokona-grun is a native ELF launcher (app/native/kokona-run.c), not
# a shell script. The old 170-line /system/bin/sh wrapper dominated per-call
# latency with a shell start plus a fork+exec for every helper command.
# Supply its path via KOKONA_RUN_BIN. When absent, keep whatever is already in
# staging; both build-runtime.sh and wsl-repack.sh overwrite this file with the
# shipped ELF before the runtime tar is created.
if [ -n "${KOKONA_RUN_BIN:-}" ] && [ -f "$KOKONA_RUN_BIN" ]; then
  cp -f "$KOKONA_RUN_BIN" bin/kokona-grun
  chmod 755 bin/kokona-grun
fi

# --- etc ---------------------------------------------------------------------
cat > etc/nsswitch.conf <<EOF
passwd: files
group: files
shadow: files
hosts: files dns
networks: files
protocols: files
services: files
ethers: files
rpc: files
EOF
[ -f etc/gai.conf ] || touch etc/gai.conf
# getent services / protocols / networks read these tables and return rc=2
# without them. They are small text files; ship the vendored copies so the
# image does not depend on the build host's /etc.
ETC_SRC=$(cd "$OLDPWD" && pwd)/etc
for f in services protocols networks; do
  if [ -f "$ETC_SRC/$f" ]; then
    cp -f "$ETC_SRC/$f" "etc/$f"
  elif [ -f "/etc/$f" ]; then
    cp -f "/etc/$f" "etc/$f"
  fi
done
: > etc/resolv.conf
[ -f etc/hosts ] || printf '127.0.0.1 localhost\n::1 localhost\n' > etc/hosts

# ld.so symlink for tools that exec it directly
rm -f bin/ld.so
cp -f lib/ld-linux-aarch64.so.1 bin/ld.so
chmod 755 bin/ld.so

# ldd is a shell script and Android has no /bin/bash.
if [ -f bin/ldd ]; then
  sed -i '1s|^#!/bin/bash|#!/system/bin/sh|' bin/ldd
  chmod 755 bin/ldd
fi

# ---- permissions -----------------------------------------------------------
# The prefix is packed on Linux but extracted by the Java tar reader on device;
# every executable bit has to be set here or the loader refuses to start
# (Android folds EACCES into ENOENT, which looks like "missing library").
find . -type d -exec chmod 755 {} + 2>/dev/null || true
find . -type f -perm -u+x -exec chmod 755 {} + 2>/dev/null || true
for f in bin/*; do [ -f "$f" ] && chmod 755 "$f"; done
for f in lib/ld-linux-aarch64.so.1 lib/libc.so.6 lib/libc-*.so; do
  [ -f "$f" ] && chmod 755 "$f"
done

# ---- no symlinks or hard links may survive --------------------------------
# Dereference everything in place: tar would store them as type 1/2 entries and
# the device-side extractor skips those outright.
find . -type l 2>/dev/null | while read -r l; do
  t=$(readlink -f "$l") || continue
  [ -f "$t" ] || continue
  cp -f "$t" "$l.tmp" && mv -f "$l.tmp" "$l"
done

echo "assemble: OK"
du -sh "$DEST"
