#!/usr/bin/env node
// The host's Plugins page already renders the plugin-market row in the sidebar.
// dshmarket's own optional row then shows a second, duplicate icon, so pin its
// visibility off. The market stays reachable from the Plugins page tab and the
// Settings section.
import { readFileSync, writeFileSync } from 'node:fs';

const runtime = process.argv[2];
if (runtime === undefined) {
  console.error('usage: patch-dshmarket-sidebar.mjs <runtime-dir>');
  process.exit(2);
}

const file = `${runtime}/lib/node_modules/dshmarket/client/client.js`;

const replacements = [
  // 1. The market's own sidebar row is a second entry beside the host's
  //    Plugins row, so pin it off. The market stays reachable from the
  //    Plugins page tab and the Settings section.
  ['gate.setVisible(enabled || !chosen && !otherSeat());', 'gate.setVisible(false);'],
  // 2. The settings nav icon takeover paints a mask icon on top of the row's
  //    real glyph. Next to dsh-better-sidebar's own row it renders as a
  //    duplicate icon, and its document-wide observer runs on every settings
  //    re-render. Ship the shell's default gear instead.
  ['installSettingsNavIcon(ctx, () => t("nav"));', '/* kokona: settings nav icon takeover disabled. */'],
];

const text = readFileSync(file, 'utf8');
let out = text;
for (const [before, after] of replacements) {
  const hits = out.split(before).length - 1;
  if (hits === 1) {
    out = out.replace(before, after);
    continue;
  }
  // 素材若已带此补丁则跳过，保证重复构建幂等。
  if (hits === 0 && out.includes(after)) {
    console.error(`dshmarket sidebar patch: already applied, skip "${before}"`);
    continue;
  }
  console.error(`dshmarket sidebar patch: expected 1 match for "${before}", found ${hits}`);
  process.exit(1);
}
writeFileSync(file, out);
