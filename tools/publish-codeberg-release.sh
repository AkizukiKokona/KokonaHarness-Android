#!/usr/bin/env bash
# Publish (or refresh) a release on Codeberg and keep the asset policy tidy.
#
# Policy (Codeberg only — GitHub has no account quota and is NEVER touched):
#   * only the newest release keeps the installer asset;
#   * older releases keep their release page + notes, but lose their assets and
#     get a pointer to the identical files on the matching GitHub release.
# GitHub releases are managed separately with `gh release ...`.
#
# Why: Codeberg enforces a per-user 1.5 GiB quota on release assets. Pruning old
# assets before checking the quota frees room for the current build. If the
# remaining quota still cannot hold the asset, the release is published with
# the full notes plus a GitHub download link and SHA256 — never an empty page.
#
# The API token comes from `git credential fill`; nothing secret is stored here.
#
# Usage:
#   tools/publish-codeberg-release.sh <tag> <title> <notes.md> <apk>
# Override the target Codeberg repo (same name on GitHub):
#   CB_REPO=KokonaHarness tools/publish-codeberg-release.sh v1.1.1 1.1.1 notes.md setup.exe
#
# Idempotent: re-running updates the same release, replaces the same-named
# asset and leaves older pages alone.

set -euo pipefail

TAG="${1:?usage: publish-codeberg-release.sh <tag> <title> <notes.md> <apk>}"
TITLE="${2:?missing title}"
NOTES="${3:?missing notes file}"
APK="${4:?missing apk file}"

OWNER="${CB_OWNER:-AkizukiKokona}"
REPO="${CB_REPO:-KokonaHarness-Android}"
API="https://codeberg.org/api/v1/repos/$OWNER/$REPO"
WEB="https://codeberg.org/$OWNER/$REPO/releases/tag/$TAG"
GH_URL="https://github.com/$OWNER/$REPO/releases/tag"
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
  // CB_PRERELEASE=1 publishes into the pre-release channel: visible on the
  // releases page but never picked as "latest", which is what every rc build
  // should use until it has been verified on a device.
  prerelease: process.env.CB_PRERELEASE === "1"
}));
' "$TAG" "$TITLE" "$1" > "$2"
}

# Notes with a GitHub download pointer, used when the Codeberg asset quota is
# full. The hash lets users verify it is the very same file.
fallback_notes() {
  local sha="$1" out="$2"
  "$NODE_BIN" -e '
const fs = require("fs");
const [notes, tag, sha, gh, asset, out] = process.argv.slice(1);
const url = gh + "/" + tag;
const extra = "\n\n---\n\n> **下载安装包**\n"
  + "> Codeberg 账号的发行版附件配额（1.5 GiB）已用满，本页不重复上传安装包。\n"
  + "> 请到 GitHub 下载同一版本、同一哈希的 `" + asset + "`：" + url + "\n>\n"
  + "> SHA256：`" + sha + "`\n>\n"
  + "> 两边代码、标签与发行说明保持同步，安装包以 GitHub Releases 为准。\n";
fs.writeFileSync(out, fs.readFileSync(notes, "utf8").trimEnd() + extra);
' "$NOTES" "$TAG" "$sha" "$GH_URL" "$ASSET_NAME" "$out"
}

apply_body_patch() {
  local notes="$1" code
  make_payload "$notes" "$TMP/body.json"
  code="$(api -X PATCH -H 'Content-Type: application/json' \
    --data-binary "@$TMP/body.json" \
    -o "$TMP/release.json" -w '%{http_code}' "$API/releases/$RELEASE_ID")"
  [ "$code" = "200" ] || { echo "body update failed (HTTP $code)" >&2; exit 1; }
}

# ---- Releases unit --------------------------------------------------------
# Migrated repositories often have the Releases unit disabled, which makes
# every /releases endpoint return 404. Enable it once if needed (admin only).
echo "== target: codeberg.org/$OWNER/$REPO (GitHub is never modified) =="
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
  echo "   Releases unit already enabled"
fi

# ---- prune older releases: keep the page, drop the assets -----------------
# Only touches Codeberg releases of THIS repository whose tag differs from the
# one being published. Their notes get a GitHub pointer for the same tag.
echo "== pruning assets from older Codeberg releases (pages kept) =="
api -o "$TMP/all.json" "$API/releases?per_page=50" || true
"$NODE_BIN" -e '
const fs=require("fs");let a=[];try{a=JSON.parse(fs.readFileSync(process.argv[1],"utf8"))}catch(e){}
if(!Array.isArray(a))process.exit(0);
for(const r of a){
  if(r.tag_name===process.argv[2])continue;
  const ids=(r.assets||[]).map(x=>x.id);
  if(!ids.length)continue;
  console.log([r.id,r.tag_name,ids.join(",")].join("\t"));
}' "$TMP/all.json" "$TAG" > "$TMP/old.tsv" 2>/dev/null || true

if [ ! -s "$TMP/old.tsv" ]; then
  echo "   nothing to prune"
else
  while IFS=$'\t' read -r OID OTAG IDS; do
    [ -n "$OID" ] || continue
    echo "   older release $OTAG (id=$OID): dropping assets $IDS"
    api -o "$TMP/one.json" "$API/releases/$OID" || true
    "$NODE_BIN" -e '
const fs=require("fs");
const [file,url,out]=process.argv.slice(1);
let r={};try{r=JSON.parse(fs.readFileSync(file,"utf8"))}catch(e){process.exit(0)}
let b=r.body||"";
if(!b.includes(url)){
  b=b.trimEnd()+"\n\n---\n\n> **安装包已迁移**\n"
   +"> 为节省 Codeberg 的发行版附件配额，本页不再附带安装包。请到 GitHub 下载同一版本的安装包："+url+"\n>\n"
   +"> 本仓库仅最新版本保留安装包附件，更老版本保留发行说明并指向 GitHub。\n";
}
fs.writeFileSync(out,JSON.stringify({
  tag_name:r.tag_name,target_commitish:"master",name:r.name,body:b,
  draft:r.draft,prerelease:r.prerelease}));' \
      "$TMP/one.json" "$GH_URL/$OTAG" "$TMP/old-patch.json"
    PC="$(api -X PATCH -H 'Content-Type: application/json' \
      --data-binary "@$TMP/old-patch.json" \
      -o "$TMP/old-patched.json" -w '%{http_code}' "$API/releases/$OID")"
    echo "     notes -> GitHub (HTTP $PC)"
    for AID in ${IDS//,/ }; do
      [ -n "$AID" ] || continue
      DC="$(api -X DELETE -o "$TMP/old-del.json" -w '%{http_code}' \
        "$API/releases/$OID/assets/$AID")"
      echo "     asset $AID deleted (HTTP $DC)"
    done
  done < "$TMP/old.tsv"
fi

# ---- quota precheck (after pruning, so freed space counts) ----------------
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

# ---- create or update the newest release ----------------------------------
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
  RELEASE_ID="$("$NODE_BIN" -e '
const fs=require("fs");let a=[];try{a=JSON.parse(fs.readFileSync(process.argv[1],"utf8"))}catch(e){}
const r=a.find(x=>x.tag_name===process.argv[2]);process.stdout.write(r?String(r.id):"");' \
    "$TMP/all.json" "$TAG" 2>/dev/null || true)"
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
  echo "== removing any old asset named $ASSET_NAME on the newest release =="
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
    -F "attachment=@$APK;filename=$ASSET_NAME;type=application/octet-stream" \
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
