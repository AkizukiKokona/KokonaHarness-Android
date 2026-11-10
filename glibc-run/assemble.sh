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
cat > bin/kokona-grun <<'EOS'
#!/system/bin/sh
# kokona-grun: run a glibc-linked Linux/Android-arm64 program through this
# prefix. Everything else on the system (bionic) stays untouched.
#
# The prefix is derived from this script's own location first, so the runner
# keeps working no matter which package name or install path the app is
# unpacked into. KOKONA_GLIBC overrides it; the hard-coded fallback is last.
G=""
if [ -n "${KOKONA_GLIBC:-}" ]; then
  G="$KOKONA_GLIBC"
else
  case "$0" in
    */*)
      d=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
      # Two install positions are supported: prefix/bin/kokona-grun (dir up) and
      # runtime/bin/kokona-grun (dir up + kokona-glibc), so the launcher can be
      # copied next to the bionic tools without hard-coding a package name.
      if [ -d "$d/../kokona-glibc/lib" ]; then
        G=$(cd "$d/../kokona-glibc" && pwd)
      elif [ -d "$d/../lib" ]; then
        G=$(cd "$d/.." && pwd)
      fi
      ;;
  esac
fi
[ -n "$G" ] || G="/data/user/0/com.kokonaharness.android.akizuki/files/KokonaRuntime/kokona-glibc"

[ -x "$G/lib/ld-linux-aarch64.so.1" ] || {
  echo "kokona-grun: glibc runtime missing at $G" >&2
  exit 127
}

# NOTE: $G/bin is deliberately NOT added to PATH. The bionic runtime already
# owns /bin, and the two namespaces were only kept apart by PATH order plus the
# accident that no name collided. Prefixed launchers (kiconv, kgetent, ...) live
# in the runtime's own bin instead, so a future packaging mistake cannot shadow
# a bionic command with a glibc one. Anything that does want the bare names can
# still run "$G/bin/<tool>" or pass --library-path itself.

# A resolver line is only useful if it is well formed: a junk value is worse
# than no value because glibc then fails lookups with a vague error instead of
# falling through to the next server. Accept dotted-quad IPv4 (each octet
# 0-255) and IPv6 (hex groups, at least two colons, optional v4 tail).
kn_ip() {
  case "$1" in
    *:*:*)
      case "$1" in *[!0-9a-fA-F:.]*) return 1 ;; esac
      [ ${#1} -le 45 ] || return 1
      return 0
      ;;
  esac
  case "$1" in *[!0-9.]*) return 1 ;; esac
  case "$1" in *.*.*.*) ;; *) return 1 ;; esac
  r=${1#*.}; o1=${1%%.*}
  o2=${r%%.*}; r=${r#*.}
  o3=${r%%.*}; o4=${r#*.}
  case "$o4" in *.* | '') return 1 ;; esac
  for octet in "$o1" "$o2" "$o3" "$o4"; do
    [ -n "$octet" ] || return 1
    [ ${#octet} -le 3 ] || return 1
    [ "$octet" -ge 0 ] 2>/dev/null || return 1
    [ "$octet" -le 255 ] || return 1
  done
  return 0
}

# Refresh DNS for this run. glibc cannot resolve without a nameserver line, and
# Android 10+ no longer publishes net.dns*: the app writes the live resolver
# list to etc/dns.servers, then props, then a public fallback.
# Order matters: the app writes the *live* resolver list to etc/dns.servers on
# every network change, which is the only source that works on Android 10+ (the
# net.dns* props are not published there). getprop stays as a fallback for older
# devices that still have them.
# Every line has to be exactly "nameserver <ipv4|ipv6>". The app writes this
# file from the system resolver list, but a half-written line, a comment or a
# stray "garbage line" used to go into resolv.conf verbatim and could make the
# resolver refuse to start. Validate each line instead of copying the file.
ns=""
if [ -s "$G/etc/dns.servers" ]; then
  while IFS= read -r line; do
    line=$(printf '%s' "$line" | tr -d '\r')
    case "$line" in
      nameserver\ *) ip=${line#nameserver } ;;
      *) continue ;;
    esac
    # No extra tokens, no trailing junk.
    case "$ip" in *" "*) continue ;; esac
    kn_ip "$ip" || continue
    if [ -z "$ns" ]; then ns="nameserver $ip"; else ns="$ns
nameserver $ip"; fi
  done < "$G/etc/dns.servers"
fi
if [ -z "$ns" ]; then
  for prop in net.dns1 net.dns2 net.dns3 net.dns4; do
    v=$(getprop "$prop" 2>/dev/null)
    case "$v" in
      '' | *[!0-9a-fA-F.:]*) ;;
      *)
        if [ -z "$ns" ]; then ns="nameserver $v"; else ns="$ns
nameserver $v"; fi
        ;;
    esac
  done
fi
[ -n "$ns" ] || ns="nameserver 1.1.1.1
nameserver 8.8.8.8"
{
  echo "# generated by kokona-grun"
  printf '%s\n' "$ns"
} > "$G/etc/resolv.conf" 2>/dev/null || true

unset LD_PRELOAD LD_LIBRARY_PATH LD_DEBUG LD_DEBUG_OUTPUT LD_BIND_NOW

# Locale. This prefix ships no compiled locale data (the glibc 2.44 tree has no
# localedef definition for C.UTF-8, and locale data compiled by a *different*
# glibc version aborts with "stack smashing detected" instead of degrading), so
# C.UTF-8 is not available no matter what LANG says. C is: it is built into
# libc, it never prints a warning, and it is byte-transparent, so UTF-8 text
# still passes through unchanged. The app exports en_US.UTF-8, which is exactly
# what used to produce three "Cannot set LC_*" lines on every `locale` run.
#
# An explicit LANG/LC_ALL from the caller wins, so a user who compiles locale
# data into lib/locale can opt in without editing this script.
if [ -z "${LC_ALL:-}" ]; then
  case "${LANG:-}" in
    '' | C | POSIX | *[!A-Za-z0-9_.@-]*) LC_ALL=C ;;
    *)
      # Keep a locale the caller asked for only when data for it exists.
      if [ -d "$G/lib/locale/$LANG" ] || [ -f "$G/lib/locale/locale-archive" ]; then
        LC_ALL="$LANG"
      else
        LC_ALL=C
      fi
      ;;
  esac
fi
LANG=$LC_ALL
export LANG LC_ALL

RT="$(dirname "$G")"
[ -r "$RT/etc/tls/cert.pem" ] && export SSL_CERT_FILE="$RT/etc/tls/cert.pem"

# Extra glibc-only libraries (dropped by the user or downloaded later).
LOCAL_LIB="$HOME/.local/lib/kokona-glibc"
LP="$G/lib"
[ -d "$LOCAL_LIB" ] && LP="$LP:$LOCAL_LIB"

# Friendly entry points. Without these the loader answers with its own usage
# dump, which reads like a crash to anyone who typed `kokona-grun --version`.
case "${1:-}" in
  -h | --help)
    printf '%s\n' \
      "kokona-grun — run a glibc-linked arm64 program on the bundled glibc prefix" \
      "" \
      "usage: kokona-grun [--] <program> [args...]" \
      "       kokona-grun --version | --help" \
      "" \
      "<program> must be a path (./x or /abs/x); the loader does not search PATH." \
      "glibc tools are available as kiconv, kgetent, kgetconf, klocale, kldd." \
      "prefix: $G"
    exit 0
    ;;
  -V | --version)
    printf 'kokona-grun (KokonaRuntime) glibc prefix %s\n' "$G"
    exit 0
    ;;
esac

if [ "$1" = "--" ]; then shift; fi
exec "$G/lib/ld-linux-aarch64.so.1" --library-path "$LP" "$@"
EOS
chmod 755 bin/kokona-grun

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
