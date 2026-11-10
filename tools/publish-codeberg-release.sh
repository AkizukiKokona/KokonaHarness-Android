#!/usr/bin/env bash
# Publish (or refresh) a KHA release on Codeberg and upload the APK asset.
#
# The GitHub side is handled by `gh release ...`. This script is the Codeberg
# half: it is idempotent, so re-running it after a same-version APK rebuild
# updates the notes and replaces the asset instead of creating a duplicate.
#
# The API token is read from git's credential manager (git credential fill),
# never stored in this repository.
#
# Usage:
#   tools/publish-codeberg-release.sh <tag> <title> <notes.md> <apk>
# Example:
#   tools/publish-codeberg-release.sh v1.0.37 1.0.37 docs/notes.md KokonaHarness.apk

set -euo pipefail

TAG="${1:?usage: publish-codeberg-release.sh <tag> <title> <notes.md> <apk>}"
TITLE="${2:?missing title}"
NOTES="${3:?missing notes file}"
APK="${4:?missing apk file}"

OWNER=AkizukiKokona
REPO=KokonaHarness-Android
API="https://codeberg.org/api/v1/repos/$OWNER/$REPO"
WEB="https://codeberg.org/$OWNER/$REPO/releases/tag/$TAG"
PROXY="${HTTPS_PROXY:-${HTTP_PROXY:-http://127.0.0.1:7897}}"
ASSET_NAME="$(basename "$APK")"
NODE_BIN="${NODE_BIN:-node}"

command -v "$NODE_BIN" >/dev/null 2>&1 || \
  NODE_BIN="C:/Users/Akizuki/.workbuddy/binaries/node/versions/22.22.2-6/node.exe"

# Normalise to mixed Windows paths: node and curl are Windows binaries, while
# callers may pass either /c/... or C:/... style paths.
if command -v cygpath >/dev/null 2>&1; then
  NOTES="$(cygpath -m "$NOTES")"
  APK="$(cygpath -m "$APK")"
fi

[ -f "$NOTES" ] || { echo "notes not found: $NOTES" >&2; exit 1; }
[ -f "$APK" ]   || { echo "apk not found: $APK" >&2; exit 1; }

TMP_DIR="$(mktemp -d)"
# curl here is a Windows binary while node resolves /tmp as C:\tmp; use a
# mixed (C:/...) path for both so they read exactly the same files.
TMP="$(cygpath -m "$TMP_DIR" 2>/dev/null || printf '%s' "$TMP_DIR")"
trap 'rm -rf "$TMP_DIR"' EXIT

TOKEN="$(printf 'protocol=https\nhost=codeberg.org\n\n' | \
  GIT_TERMINAL_PROMPT=0 GCM_INTERACTIVE=never git credential fill 2>/dev/null | \
  sed -n 's/^password=//p' | head -1)"
[ -n "$TOKEN" ] || { echo "no codeberg credential; run: git credential fill" >&2; exit 1; }

api() {
  curl -sS -x "$PROXY" --max-time 900 \
    -H "Authorization: token $TOKEN" "$@"
}

# Build the JSON payload with node so markdown/newlines are escaped correctly.
"$NODE_BIN" -e '
const fs = require("fs");
const [tag, title, notes] = process.argv.slice(1);
process.stdout.write(JSON.stringify({
  tag_name: tag,
  target_commitish: "master",
  name: title,
  body: fs.readFileSync(notes, "utf8"),
  draft: false,
  prerelease: false
}));
' "$TAG" "$TITLE" "$NOTES" > "$TMP/payload.json"

echo "== looking for an existing release for $TAG =="
CODE="$(api -o "$TMP/existing.json" -w '%{http_code}' \
  "$API/releases/tags/$TAG" || true)"
RELEASE_ID=""
if [ "$CODE" = "200" ]; then
  RELEASE_ID="$("$NODE_BIN" -e '
const fs=require("fs");const r=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
process.stdout.write(String(r.id||""));' "$TMP/existing.json")"
fi
# Older Forgejo builds lack the /tags/{tag} endpoint: fall back to the list.
if [ -z "$RELEASE_ID" ]; then
  api -o "$TMP/list.json" "$API/releases?per_page=50" || true
  RELEASE_ID="$("$NODE_BIN" -e '
const fs=require("fs");let a=[];try{a=JSON.parse(fs.readFileSync(process.argv[1],"utf8"))}catch(e){}
const r=a.find(x=>x.tag_name===process.argv[2]);process.stdout.write(r?String(r.id):"");' \
    "$TMP/list.json" "$TAG" 2>/dev/null || true)"
fi

if [ -n "$RELEASE_ID" ]; then
  echo "== updating existing release id=$RELEASE_ID =="
  CODE="$(api -X PATCH -H 'Content-Type: application/json' \
    --data-binary "@$TMP/payload.json" \
    -o "$TMP/release.json" -w '%{http_code}' "$API/releases/$RELEASE_ID")"
else
  echo "== creating new release =="
  CODE="$(api -X POST -H 'Content-Type: application/json' \
    --data-binary "@$TMP/payload.json" \
    -o "$TMP/release.json" -w '%{http_code}' "$API/releases")"
fi
if [ "$CODE" != "200" ] && [ "$CODE" != "201" ]; then
  echo "release API returned HTTP $CODE" >&2
  head -c 400 "$TMP/release.json" >&2; echo >&2
  exit 1
fi
RELEASE_ID="$("$NODE_BIN" -e '
const fs=require("fs");const r=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
process.stdout.write(String(r.id));' "$TMP/release.json")"

echo "== removing any old asset named $ASSET_NAME =="
"$NODE_BIN" -e '
const fs=require("fs");const r=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
for(const a of (r.assets||[])) if(a.name===process.argv[2]) console.log(a.id);' \
  "$TMP/release.json" "$ASSET_NAME" | while read -r ASSET_ID; do
  [ -n "$ASSET_ID" ] || continue
  DC="$(api -X DELETE -o "$TMP/delete.json" -w '%{http_code}' \
    "$API/releases/$RELEASE_ID/assets/$ASSET_ID")"
  echo "   deleted old asset $ASSET_ID (HTTP $DC)"
done

echo "== uploading $ASSET_NAME ($(stat -c %s "$APK") bytes) =="
UC="$(api -X POST \
  -F "attachment=@$APK;filename=$ASSET_NAME;type=application/vnd.android.package-archive" \
  -o "$TMP/asset.json" -w '%{http_code}' \
  "$API/releases/$RELEASE_ID/assets?name=$ASSET_NAME")"
if [ "$UC" != "200" ] && [ "$UC" != "201" ]; then
  echo "asset upload returned HTTP $UC" >&2
  head -c 400 "$TMP/asset.json" >&2; echo >&2
  exit 1
fi

echo "== verifying =="
api -o "$TMP/final.json" "$API/releases/$RELEASE_ID"
"$NODE_BIN" -e '
const fs=require("fs");const r=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
console.log("tag=" + r.tag_name + "  name=" + r.name + "  draft=" + r.draft);
for(const a of (r.assets||[])) console.log("asset=" + a.name + "  size=" + a.size + "  downloads=" + a.download_count);' \
  "$TMP/final.json"
echo "URL: $WEB"
