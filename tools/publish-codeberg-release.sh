#!/usr/bin/env bash
# Publish (or refresh) the KHA release on Codeberg.
#
# The GitHub side is handled by `gh release ...`. This script is the Codeberg
# half: idempotent, so re-running it after a same-version APK rebuild updates
# the notes and replaces the asset instead of creating a duplicate.
#
# Codeberg enforces a per-user storage quota (1.5 GiB for release assets). If
# the remaining quota cannot hold the APK, the release is still published with
# the full notes plus a link to the identical asset on GitHub — never an empty
# or half-finished release. Cleaning up someone else's old assets is never done
# automatically; request a quota raise at Codeberg-e.V./requests instead.
#
# The API token is read from git's credential manager (git credential fill),
# never stored in this repository.
#
# Usage:
#   tools/publish-codeberg-release.sh <tag> <title> <notes.md> <apk>
# Example:
#   tools/publish-codeberg-release.sh v1.0.37 1.0.37 notes.md KokonaHarness.apk

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
make_payload() {
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
' "$TAG" "$TITLE" "$1" > "$2"
}

# Notes with a GitHub download pointer, used when the Codeberg asset quota is
# already full. The hash lets users verify it is the very same APK.
fallback_notes() {
  local sha="$1" out="$2"
  "$NODE_BIN" -e '
const fs = require("fs");
const [notes, tag, sha, out] = process.argv.slice(1);
const url = "https://github.com/AkizukiKokona/KokonaHarness-Android/releases/tag/" + tag;
const extra = "\n\n---\n\n> **下载 APK**\n"
  + "> Codeberg 账号的发行版附件配额（1.5 GiB）已用满，本页不重复上传安装包。\n"
  + "> 请到 GitHub 下载同一版本、同一哈希的 `KokonaHarness.apk`：" + url + "\n>\n"
  + "> SHA256：`" + sha + "`\n>\n"
  + "> 两边代码、标签与发行说明保持同步，APK 附件以 GitHub Releases 为准。\n";
fs.writeFileSync(out, fs.readFileSync(notes, "utf8").trimEnd() + extra);
' "$NOTES" "$TAG" "$sha" "$out"
}

apply_body_patch() {
  local notes="$1" code
  make_payload "$notes" "$TMP/body.json"
  code="$(api -X PATCH -H 'Content-Type: application/json' \
    --data-binary "@$TMP/body.json" \
    -o "$TMP/release.json" -w '%{http_code}' "$API/releases/$RELEASE_ID")"
  [ "$code" = "200" ] || { echo "body update failed (HTTP $code)" >&2; exit 1; }
}

# ---- quota precheck -------------------------------------------------------
echo "== checking Codeberg release-asset quota =="
api -o "$TMP/quota.json" https://codeberg.org/api/v1/user/quota || true
Q="$("$NODE_BIN" -e '
const fs=require("fs");let q={};try{q=JSON.parse(fs.readFileSync(process.argv[1],"utf8"))}catch(e){}
const used=(((q.used||{}).size||{}).assets||{}).attachments
  ? (((q.used.size.assets.attachments||{}).releases)||0) : 0;
let limit=0;
for(const g of (q.groups||[])) for(const r of (g.rules||[]))
  if((r.subjects||[]).some(s=>String(s).indexOf("assets")>=0))
    limit=Math.max(limit, Number(r.limit)||0);
process.stdout.write(used+" "+limit+" "+(limit?limit-used:-1));' "$TMP/quota.json" 2>/dev/null || echo '0 0 -1')"
read -r Q_USED Q_LIMIT Q_FREE <<< "$Q"
APK_SIZE="$(stat -c %s "$APK")"
APK_SHA="$(sha256sum "$APK" | awk '{print $1}')"
SKIP_UPLOAD=0
if [ "$Q_LIMIT" -gt 0 ] && [ "$Q_FREE" -lt "$APK_SIZE" ]; then
  echo "   used=$Q_USED limit=$Q_LIMIT free=$Q_FREE apk=$APK_SIZE -> quota full"
  SKIP_UPLOAD=1
else
  echo "   used=$Q_USED limit=$Q_LIMIT free=$Q_FREE apk=$APK_SIZE -> ok"
fi

CB_NOTES="$NOTES"
if [ "$SKIP_UPLOAD" = "1" ]; then
  CB_NOTES="$TMP/notes-codeberg.md"
  fallback_notes "$APK_SHA" "$CB_NOTES"
  echo "   release will link to the identical asset on GitHub"
fi
make_payload "$CB_NOTES" "$TMP/payload.json"

# ---- Releases unit --------------------------------------------------------
# Migrated repositories often have the Releases unit disabled, which makes
# every /releases endpoint return 404. Enable it once if needed (admin only).
echo "== ensuring the Releases unit is enabled =="
api -o "$TMP/repo.json" "$API"
RELEASES_ON="$("$NODE_BIN" -e '
const fs=require("fs");const r=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
process.stdout.write(String(r.has_releases===true));' "$TMP/repo.json")"
if [ "$RELEASES_ON" != "true" ]; then
  EC="$(api -X PATCH -H 'Content-Type: application/json' \
    -d '{"has_releases":true}' \
    -o "$TMP/repo2.json" -w '%{http_code}' "$API")"
  [ "$EC" = "200" ] || { echo "cannot enable Releases unit (HTTP $EC)" >&2; exit 1; }
  echo "   Releases unit enabled (HTTP $EC)"
else
  echo "   already enabled"
fi

# ---- create or update the release ----------------------------------------
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
  apply_body_patch "$CB_NOTES"
else
  echo "== creating new release =="
  CODE="$(api -X POST -H 'Content-Type: application/json' \
    --data-binary "@$TMP/payload.json" \
    -o "$TMP/release.json" -w '%{http_code}' "$API/releases")"
  if [ "$CODE" != "200" ] && [ "$CODE" != "201" ]; then
    echo "release API returned HTTP $CODE" >&2
    head -c 400 "$TMP/release.json" >&2; echo >&2
    exit 1
  fi
  RELEASE_ID="$("$NODE_BIN" -e '
const fs=require("fs");const r=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
process.stdout.write(String(r.id));' "$TMP/release.json")"
fi
echo "   release id=$RELEASE_ID"

# ---- asset upload ---------------------------------------------------------
if [ "$SKIP_UPLOAD" = "0" ]; then
  echo "== removing any old asset named $ASSET_NAME =="
  api -o "$TMP/before.json" "$API/releases/$RELEASE_ID" || true
  "$NODE_BIN" -e '
const fs=require("fs");let r={};try{r=JSON.parse(fs.readFileSync(process.argv[1],"utf8"))}catch(e){}
for(const a of (r.assets||[])) if(a.name===process.argv[2]) console.log(a.id);' \
    "$TMP/before.json" "$ASSET_NAME" 2>/dev/null | while read -r ASSET_ID; do
    [ -n "$ASSET_ID" ] || continue
    DC="$(api -X DELETE -o "$TMP/delete.json" -w '%{http_code}' \
      "$API/releases/$RELEASE_ID/assets/$ASSET_ID")"
    echo "   deleted old asset $ASSET_ID (HTTP $DC)"
  done

  echo "== uploading $ASSET_NAME ($APK_SIZE bytes) =="
  UC="$(api -X POST \
    -F "attachment=@$APK;filename=$ASSET_NAME;type=application/vnd.android.package-archive" \
    -o "$TMP/asset.json" -w '%{http_code}' \
    "$API/releases/$RELEASE_ID/assets?name=$ASSET_NAME")"
  if [ "$UC" = "413" ]; then
    echo "   HTTP 413 quota exceeded -> falling back to GitHub download link"
    CB_NOTES="$TMP/notes-codeberg.md"
    fallback_notes "$APK_SHA" "$CB_NOTES"
    apply_body_patch "$CB_NOTES"
  elif [ "$UC" != "200" ] && [ "$UC" != "201" ]; then
    echo "asset upload returned HTTP $UC" >&2
    head -c 400 "$TMP/asset.json" >&2; echo >&2
    exit 1
  fi
else
  echo "== skipping asset upload (quota); notes carry the GitHub link =="
fi

# ---- verify ---------------------------------------------------------------
echo "== verifying =="
api -o "$TMP/final.json" "$API/releases/$RELEASE_ID"
"$NODE_BIN" -e '
const fs=require("fs");const r=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
console.log("tag=" + r.tag_name + "  name=" + r.name + "  draft=" + r.draft);
const a=(r.assets||[]);
console.log("assets=" + a.length);
for(const x of a) console.log("  " + x.name + "  size=" + x.size);
console.log("notes_link_github=" + (r.body||"").includes("releases/tag/" + r.tag_name));' \
  "$TMP/final.json"
echo "URL: $WEB"
