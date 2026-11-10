#!/usr/bin/env bash
# Prepare glibc-2.44 source for KokonaGlibcRunner (Android aarch64).
# Replicates termux-pacman/glibc-packages gpkg/glibc build steps with our own
# paths. Idempotent from a pristine tarball: always start from src-clean.
set -euo pipefail
cd "$(dirname "$0")"

BASE=/data/user/0/com.kokonaharness.android.akizuki/files/KokonaRuntime
PREFIX=$BASE/kokona-glibc
B=gpkg-repo/gpkg/glibc
S=src

[ -d "$S" ] || { echo "extract src first"; exit 1; }
cd "$S"

# --- termux_step_patch_package: apply all builder patches (-p1) -------------
for p in ../$B/*.patch; do
  echo "patch: $(basename "$p")"
  patch --silent -p1 -N -r /dev/null < "$p" || { echo "FAILED: $p"; exit 1; }
done

# --- termux_step_pre_configure ----------------------------------------------
rm -f sysdeps/unix/sysv/linux/aarch64/clone3.S
rm -f sysdeps/unix/sysv/linux/x86_64/configure*

cp ../$B/shmat.c ../$B/shmctl.c ../$B/shmdt.c ../$B/shmget.c \
   ../$B/mprotect.c ../$B/syscall.c \
   ../$B/fakesyscall-base.h ../$B/fakesyscall.h ../$B/fake_epoll_pwait2.c \
   ../$B/setfsgid.c ../$B/setfsuid.c \
   sysdeps/unix/sysv/linux/

cp ../$B/android_passwd_group.c ../$B/android_passwd_group.h \
   ../$B/android_system_user_ids.h nss/
bash ../$B/gen-android-ids.sh "$BASE" nss/android_ids.h \
     ../$B/android_system_user_ids.h

cp ../$B/syslog.c misc/
cp ../$B/shmem-android.c ../$B/shmem-android.h sysvipc/

# fakesyscall.json -> disabled-syscall.h (aarch64 only; we build arm64)
# Move the #define __NR_xxx lines of every faked syscall out of arch-syscall.h
# into disabled-syscall.h (termux does the same), then emit the dispatcher's
# case block from what actually landed in disabled-syscall.h.
A=sysdeps/unix/sysv/linux/aarch64
mv "$A/syscall.S" "$A/syscallS.S"
: > "$A/disabled-syscall.h"
{
  while read -r sc; do
    grep "#define __NR_${sc} " "$A/arch-syscall.h" || true
    sed -i "/#define __NR_${sc} /d" "$A/arch-syscall.h"
  done < <(jq -r '.[] | .[]' ../$B/fakesyscall.json)
} >> "$A/disabled-syscall.h"
{
  printf '\n#define DISABLED_SYSCALL_WITH_FAKESYSCALL \\\n'
  IFS=$'\n'
  for fn in $(jq -r '. | keys | .[]' ../$B/fakesyscall.json); do
    need_return=false
    while read -r sc; do
      if grep -q "^#define __NR_${sc} " "$A/disabled-syscall.h"; then
        printf '\tcase __NR_%s: \\\n' "$sc"; need_return=true
      elif [[ ${sc} =~ ^[0-9]+$ ]]; then
        printf '\tcase %s: \\\n' "$sc"; need_return=true
      fi
    done < <(jq -r '."'$fn'" | .[]' ../$B/fakesyscall.json)
    [ "$need_return" = true ] && printf '\t\treturn %s; \\\n' "$fn"
  done
  unset IFS
} >> "$A/disabled-syscall.h"
sed -i '$ s| \\||' "$A/disabled-syscall.h"
echo "--- disabled-syscall.h head ---"
head -20 "$A/disabled-syscall.h"
echo "--- disabled-syscall.h tail ---"
tail -5 "$A/disabled-syscall.h"

# /dev/std{in,out,err} -> /proc/self/fd/N across the tree
grep -s -r -l -e '/dev/stderr' -e '/dev/stdin' -e '/dev/stdout' . \
  | while read -r f; do
      sed -i -e 's|/dev/stderr|/proc/self/fd/2|g' \
             -e 's|/dev/stdin|/proc/self/fd/0|g' \
             -e 's|/dev/stdout|/proc/self/fd/1|g' "$f"
    done

# version string
sed -i 's/"2\.44"/"2.44-kokona.1"/' version.h
grep '^#define VERSION' version.h

# --- path placeholders -------------------------------------------------------
grep -rl '@TERMUX_PREFIX' . | while read -r f; do
  sed -i "s|@TERMUX_PREFIX_CLASSICAL@|$PREFIX|g; s|@TERMUX_PREFIX@|$PREFIX|g" "$f"
done
[ -z "$(grep -rl '@TERMUX_PREFIX' . 2>/dev/null || true)" ] || { echo "placeholders left"; exit 1; }

echo "prepare: OK"
