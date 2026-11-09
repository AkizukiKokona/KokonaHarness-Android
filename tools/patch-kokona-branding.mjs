#!/usr/bin/env node
// Kokona branding for the new-conversation hero in dsh-client-ui-conversation:
// the English headline and the blue Preview badge get Kokona wording. Other
// locales are left untouched on purpose.
import { readFileSync, writeFileSync } from 'node:fs';

const runtime = process.argv[2];
if (runtime === undefined) {
  console.error('usage: patch-kokona-branding.mjs <runtime-dir>');
  process.exit(2);
}

const file = `${runtime}/lib/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js`;
const text = readFileSync(file, 'utf8');

const replacements = [
  ['"hero.headline": "Into the Unknown",', '"hero.headline": "Bathe in the light and find redemption!",'],
  ['"hero.preview": "Preview",', '"hero.preview": "Kokona Preview",'],
];

let out = text;
for (const [before, after] of replacements) {
  const hits = out.split(before).length - 1;
  if (hits === 1) {
    out = out.replace(before, after);
    continue;
  }
  // 原环境送来的 node_modules 可能已打过定制补丁：目标文案已存在就跳过，
  // 保证同一份素材可重复构建（幂等），而不是因找不到官方原版串而失败。
  if (hits === 0 && out.includes(after)) {
    console.error(`kokona branding patch: already applied, skip ${before}`);
    continue;
  }
  console.error(`kokona branding patch: expected 1 match for ${before}, found ${hits}`);
  process.exit(1);
}
writeFileSync(file, out);
