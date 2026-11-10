#!/usr/bin/env bash
# Iterate make -j1; when NDK clang 18 crashes (SIGSEGV) on a file, recompile
# that single file at -O1 and continue. Returns when the build finishes or
# iterations run out.
set -uo pipefail
cd "$(dirname "$0")"
TC=/opt/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin
LOG=$PWD/make-loop.log

for i in $(seq 1 20); do
  (cd build && make -j3 O=$PWD) > "$LOG" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then echo "BUILD OK (iteration $i)"; exit 0; fi
  # find the crashing TU: "clang: note: diagnostic msg: /tmp/<name>-<id>.c"
  tu=$(grep -m1 -oE 'diagnostic msg: /tmp/[A-Za-z0-9_.-]+\.c' "$LOG" | sed 's|diagnostic msg: /tmp/||')
  if [ -z "$tu" ]; then
    echo "make failed without a clang crash; aborting"; tail -20 "$LOG"; exit 1
  fi
  # /tmp/res_libc-806607.c -> original TU res_libc.c
  base="${tu%-*.c}"; file="${base}.c"
  CMD=$(grep -m1 -- "${file} -c" "$LOG")
  if [ -z "$CMD" ]; then echo "no compile line for $file"; exit 1; fi
  # make runs compiles from the subdir of src containing the TU
  d=$(find src -name "$file" | head -1 | xargs dirname)
  echo "iteration $i: $file crashed -> recompiling at -O1 in $d"
  (cd "$d" && eval "${CMD/-O2/-O1}") || { echo "manual -O1 compile failed"; exit 1; }
done
echo "too many iterations"; exit 1
