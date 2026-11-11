#!/usr/bin/env python3
"""Static runtime patch: unify "open a directory" across platforms.

DSH's dsh-native-command dispatches directory opens with explorer.exe
(Windows / WSL) or xdg-open / gio (desktop Linux). Node on Android reports
platform "linux" but has none of those, so sidebar, previews and any other
caller requesting a directory ended up with a Windows path or a failed
command. Patch BOTH bundled copies of the opener (the aggregate index.js and
types/path-opener.js) at their single dispatch points so every caller gets
the same behaviour.

Idempotent: re-running on an already patched tree is a no-op.

Usage: python3 patch-native-opener.py <runtime-root>
"""
import io
import os
import re
import sys

MARK = 'KHA_ANDROID_OPEN_V1'

TARGETS = (
    'lib/node_modules/@deepseek-ai/dsh-native-command/lib/index.js',
    'lib/node_modules/@deepseek-ai/dsh-native-command/lib/types/path-opener.js',
)

HELPER = '''
/* %s -- Android directory opener. Windows/WSL/macOS paths are untouched. */
async function khaAndroidOpen(targetPath, signal, run, reveal, internals) {
\tif (internals && internals.platform && internals.platform !== "linux") return false;
\tif (process.env.KOKONA_ANDROID !== "1") return false;
\tconst nodeFs = await import("node:fs");
\tif (!nodeFs.existsSync("/system/bin/am")) return false;
\tlet target = targetPath;
\tif (reveal) {
\t\tconst cut = target.lastIndexOf("/");
\t\ttarget = cut > 0 ? target.slice(0, cut) : target;
\t}
\tlet docId = null;
\tif (target === "/storage/emulated/0" || target === "/sdcard") {
\t\tdocId = "primary:";
\t} else if (target.startsWith("/storage/emulated/0/") || target.startsWith("/sdcard/")) {
\t\tconst prefix = target.startsWith("/sdcard/") ? "/sdcard/" : "/storage/emulated/0/";
\t\tdocId = "primary:" + target.slice(prefix.length);
\t} else {
\t\tconst m = /^\\/storage\\/([0-9A-Za-z]{4}-[0-9A-Za-z]{4})\\/(.*)$/.exec(target);
\t\tif (m) docId = m[1] + ":" + m[2];
\t}
\tif (docId === null) {
\t\t// App-private paths are not exposed by externalstorage's DocumentsProvider.
\t\t// Fall back to the system directory picker rather than failing silently.
\t\tawait run("/system/bin/am", ["start", "-a", "android.intent.action.OPEN_DOCUMENT_TREE"], signal, "hidden");
\t\treturn true;
\t}
\tconst uri = "content://com.android.externalstorage.documents/document/" + encodeURIComponent(docId);
\tawait run("/system/bin/am", [
\t\t"start", "-a", "android.intent.action.VIEW", "-d", uri,
\t\t"-t", "vnd.android.document/directory", "-f", "0x00000001"
\t], signal, "hidden");
\treturn true;
}
/* %s end */
''' % (MARK, MARK)

HOOKS = (
    ('async function openNativePathWithIntent(',
     '\tif (await khaAndroidOpen(path, signal, (internals.run ?? runNativeCommand), false, internals)) return;\n'),
    ('async function revealNativePath(',
     '\tif (await khaAndroidOpen(path, signal, (internals.run ?? runNativeCommand), true, internals)) return;\n'),
)


def patch_file(path):
    if not os.path.isfile(path):
        print('skip (missing):', path)
        return
    src = io.open(path, encoding='utf-8', newline='').read()
    if MARK in src:
        print('already patched:', path)
        return

    matches = list(re.finditer(r'^import[\s\S]*?;\s*$', src, re.MULTILINE))
    if not matches:
        print('skip (no imports):', path)
        return
    last = matches[-1]
    at = last.end()
    src = src[:at] + '\n' + HELPER + src[at:]

    changed = 0
    for needle, injection in HOOKS:
        i = src.find(needle)
        if i < 0:
            continue
        # Skip the argument list before looking for the body brace: both hooks
        # have `internals = {}` as a default value, and its `{` is NOT the body.
        close = src.find(')', i + len(needle))
        brace = src.find('{', close) if close >= 0 else -1
        if brace < 0:
            continue
        src = src[:brace + 1] + '\n' + injection + src[brace + 1:]
        changed += 1
    if changed != 2:
        print('WARN: patched %d of 2 hooks in %s' % (changed, path))
    io.open(path, 'w', encoding='utf-8', newline='').write(src)
    print('patched:', path, '(%d/2 hooks)' % changed)


def main():
    if len(sys.argv) < 2:
        print('usage: patch-native-opener.py <runtime-root>', file=sys.stderr)
        return 1
    root = sys.argv[1]
    for rel in TARGETS:
        patch_file(os.path.join(root, rel))
    return 0


if __name__ == '__main__':
    sys.exit(main())
