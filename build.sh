#!/usr/bin/env bash
# Builds KokonaHarness.apk with the plain Android SDK toolchain (no Gradle).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BUILD="${BUILD_DIR:-/workspace/build}"
JAVA_HOME="${JAVA_HOME:-/root/.local/share/mise/installs/java/17.0.20+101}"

# Windows 原生工具(zip 的 Python shim、aapt2/javac/d8/apksigner) 不识别
# MSYS 的 /d/... 路径，统一转成混合风格 D:/...；Linux 原环境无 cygpath 时原样返回。
to_win() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }
ROOT="$(to_win "$ROOT")"
SDK="$(to_win "$SDK")"
BUILD="$(to_win "$BUILD")"

# Windows 盘符 "D:" 会与 Linux 的 classpath 分隔符 ":" 冲突，javac 的 -cp
# 在 Windows/MSYS 下必须用 ';'；Linux 原环境仍用 ':'。
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
  *)                    CP_SEP=':' ;;
esac

BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
OUT="$BUILD/apk"

[ -f "$PLATFORM" ] || { echo "missing $PLATFORM" >&2; exit 1; }
[ -f "$ROOT/app/assets/runtime.tar.xz" ] || { echo "run build-runtime.sh first" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] resources"
"$BT/aapt2" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"

echo "[2/6] link"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/app/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --auto-add-overlay

echo "[3/6] javac"
SHIZUKU="$ROOT/app/libs/shizuku"
LIBS="$SHIZUKU/api/classes.jar${CP_SEP}$SHIZUKU/aidl/classes.jar${CP_SEP}$SHIZUKU/shared/classes.jar${CP_SEP}$SHIZUKU/provider/classes.jar${CP_SEP}$ROOT/app/libs/xz.jar"
find "$ROOT/app/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# Windows JDK 默认按 GBK 解码源码，而仓库里的中文字符串是 UTF-8，必须显式指定。
"$JAVA_HOME/bin/javac" --release 8 -nowarn -encoding UTF-8 \
  -cp "$PLATFORM${CP_SEP}$LIBS" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] dex"
(cd "$OUT/classes" && "$JAVA_HOME/bin/jar" cf "$OUT/classes.jar" .)
"$BT/d8" --release --min-api 28 --lib "$PLATFORM" \
  --output "$OUT/dex" "$OUT/classes.jar" "$ROOT/app/libs/xz.jar" \
  "$SHIZUKU/api/classes.jar" "$SHIZUKU/aidl/classes.jar" \
  "$SHIZUKU/shared/classes.jar" "$SHIZUKU/provider/classes.jar"

echo "[5/6] package"
cp "$OUT/base.apk" "$OUT/app.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/app.apk" classes.dex)
(cd "$ROOT/app" && zip -q -X "$OUT/app.apk" assets/runtime.size assets/runtime.bytes assets/seed/cordis.patch.yml assets/seed/node-addon-require-builtin-android.js assets/brand.svg assets/kokona-bg.jpg assets/kha-node-patch.cjs assets/official-settings-client.js)
(cd "$ROOT/app" && zip -0 -q -X "$OUT/app.apk" assets/runtime.tar.xz)

echo "[6/6] align + sign"
"$BT/zipalign" -f -p 4 "$OUT/app.apk" "$OUT/aligned.apk"
KEYSTORE="$BUILD/kokona.keystore"
if [ ! -f "$KEYSTORE" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias kokona \
    -storepass kokona -keypass kokona -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=KokonaHarness, OU=Kokona, O=Kokona, L=Tokyo, S=Tokyo, C=JP" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:kokona --key-pass pass:kokona \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$ROOT/KokonaHarness.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify "$ROOT/KokonaHarness.apk"
ls -lh "$ROOT/KokonaHarness.apk"
