#!/usr/bin/env node
// Kokona branding for the new-conversation hero in dsh-client-ui-conversation.
//
// dsh 0.2.2-rc.5 switched the locale table from double-quoted strings to
// template literals (`"hero.headline":`Into the Unknown``), so a hard-coded
// double-quoted needle no longer matches. Match the value with either quote
// style and write the replacement back as a template literal, which both
// versions accept.
//
// Scope is deliberate: only the English and zh-CN hero strings are touched.
// Other locales keep whatever the runtime ships.
import { readFileSync, writeFileSync } from 'node:fs';

const runtime = process.argv[2];
if (runtime === undefined) {
  console.error('usage: patch-kokona-branding.mjs <runtime-dir>');
  process.exit(2);
}

const file = `${runtime}/lib/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js`;
const text = readFileSync(file, 'utf8');

const replacements = [
  ['hero.headline', 'Into the Unknown', 'Bathe in the light and find redemption!'],
  ['hero.headline', '探索未至之境', '沐浴晨光，方得救赎！'],
  ['hero.preview', 'Preview', 'Kokona Preview'],
  ['hero.preview', '预览版', '心夏预览版喵'],
];

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

let out = text;
let changed = 0;
for (const [key, before, after] of replacements) {
  const pattern = new RegExp(
    `"${escapeRegExp(key)}":\\s*[\`"']${escapeRegExp(before)}[\`"']`, 'g');
  const hits = out.match(pattern);
  if (hits === null) {
    // Idempotent: an already-patched tree carries the new wording.
    if (out.includes(`"${key}":\`${after}\``)) {
      console.error(`kokona branding patch: already applied, skip ${key}=${before}`);
      continue;
    }
    console.error(`kokona branding patch: no match for ${key}=${before}`);
    process.exit(1);
  }
  if (hits.length > 1) {
    console.error(`kokona branding patch: ${hits.length} matches for ${key}=${before}, expected 1`);
    process.exit(1);
  }
  out = out.replace(pattern, `"${key}":\`${after}\``);
  changed++;
}

writeFileSync(file, out);
console.error(`kokona branding patch: ${changed} replacement(s), ${replacements.length - changed} already applied`);
