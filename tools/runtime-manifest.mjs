#!/usr/bin/env node
// Prints the total regular-file byte count of a runtime tree. The installer
// uses it purely to drive the progress bar; installs are always full unpack.
import { readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = process.argv[2];
if (root === undefined) {
  console.error('usage: runtime-manifest.mjs <runtime-dir>');
  process.exit(2);
}

let total = 0;
const walk = (dir) => {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) walk(full);
    else if (st.isFile()) total += st.size;
  }
};
walk(root);

process.stdout.write(String(total));
