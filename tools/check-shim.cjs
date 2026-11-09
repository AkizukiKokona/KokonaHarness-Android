const fs = require('fs');
const file = 'app/java/com/kokonaharness/android/akizuki/MainActivity.java';
const src = fs.readFileSync(file, 'utf8');
const start = src.indexOf('private static final String SHIM');
const marker = '})();";';
const end = src.indexOf(marker, start) + marker.length;
if (start < 0 || end < marker.length) throw new Error('SHIM not found');
const body = src.slice(start, end);

// Walk the declaration like javac does: string literals concatenated with '+',
// Java comments in between are skipped entirely (they are not part of the value).
const lits = [];
let i = 0;
while (i < body.length) {
  const ch = body[i];
  if (ch === '/' && body[i + 1] === '/') {
    const nl = body.indexOf('\n', i);
    i = nl < 0 ? body.length : nl + 1;
    continue;
  }
  if (ch === '/' && body[i + 1] === '*') {
    const close = body.indexOf('*/', i + 2);
    i = close < 0 ? body.length : close + 2;
    continue;
  }
  if (ch === '"') {
    let j = i + 1;
    let out = '';
    while (j < body.length) {
      const c = body[j];
      if (c === '\\') {
        const n = body[j + 1];
        if (n === '"') { out += '"'; j += 2; continue; }
        if (n === '\\') { out += '\\'; j += 2; continue; }
        if (n === 'n') { out += '\n'; j += 2; continue; }
        if (n === 't') { out += '\t'; j += 2; continue; }
        out += n; j += 2; continue;
      }
      if (c === '"') break;
      out += c;
      j += 1;
    }
    lits.push(out);
    i = j + 1;
    continue;
  }
  i += 1;
}
const js = lits.join('');
console.log('literals =', lits.length, 'chars =', js.length);
try {
  new Function(js);
  console.log('SHIM OK: concatenated JS parses cleanly');
} catch (e) {
  console.log('SHIM BROKEN:', e.message);
  const m = /position (\d+)/.exec(e.message);
  if (m) console.log('context:', JSON.stringify(js.slice(Math.max(0, m[1] - 120), Number(m[1]) + 120)));
  process.exitCode = 1;
}
