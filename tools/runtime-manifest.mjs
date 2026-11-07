#!/usr/bin/env node
// Builds the runtime update manifest: "<sha256> <bytes> <path>" per regular
// file. RuntimeInstaller compares it with the installed copy and rewrites only
// the entries whose hash changed.
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.argv[2];
const out = process.argv[3];
if (root === undefined || out === undefined) {
  console.error('usage: runtime-manifest.mjs <runtime-dir> <manifest-out>');
  process.exit(2);
}

const lines = [];
let total = 0;
const walk = (dir, prefix) => {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    const rel = prefix === '' ? name : `${prefix}/${name}`;
    if (st.isDirectory()) {
      walk(full, rel);
    } else if (st.isFile()) {
      const hash = createHash('sha256').update(readFileSync(full)).digest('hex');
      lines.push(`${hash} ${st.size} ${rel}`);
      total += st.size;
    }
  }
};
walk(root, '');

writeFileSync(out, `${lines.join('\n')}\n`);
process.stdout.write(String(total));
