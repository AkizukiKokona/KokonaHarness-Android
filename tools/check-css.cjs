// Extract the WebView shim's CSS text and validate it the way a CSS parser
// would: unbalanced braces/parens swallow every rule that follows, so a single
// typo silently disables the whole sheet. check-shim.cjs only proves the *JS*
// parses — the CSS is just a string to V8.
//
// Usage: node tools/check-css.cjs [--print]
const fs = require('fs');
const vm = require('vm');

const FILE = 'app/java/com/kokonaharness/android/akizuki/MainActivity.java';
const src = fs.readFileSync(FILE, 'utf8');

// --- 1. pull the JS out of the Java string concatenation, exactly like javac --
const start = src.indexOf('private static final String SHIM');
const marker = '})();";';
const end = src.indexOf(marker, start) + marker.length;
if (start < 0 || end < marker.length) throw new Error('SHIM block not found');

const body = src.slice(start, end);
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
        const map = { n: '\n', t: '\t', r: '\r', '"': '"', '\\': '\\' };
        out += Object.prototype.hasOwnProperty.call(map, n) ? map[n] : n;
        j += 2;
        continue;
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

// --- 2. run it against a fake DOM and capture what lands in <style> ----------
const captured = [];
function makeEl(tag) {
  const attrs = new Map();
  const el = {
    tagName: (tag || 'div').toUpperCase(),
    id: '',
    className: '',
    _css: '',
    children: [],
    style: { setProperty() {}, getPropertyValue: () => '', removeProperty() {} },
    dataset: {},
    classList: { add() {}, remove() {}, contains: () => false, toggle() {} },
    getAttribute: (k) => (attrs.has(k) ? attrs.get(k) : null),
    setAttribute: (k, v) => attrs.set(k, String(v)),
    removeAttribute: (k) => attrs.delete(k),
    hasAttribute: (k) => attrs.has(k),
    appendChild(n) { this.children.push(n); return n; },
    removeChild(n) { return n; },
    insertBefore(n) { this.children.push(n); return n; },
    addEventListener() {},
    removeEventListener() {},
    querySelector: () => null,
    querySelectorAll: () => [],
    getBoundingClientRect: () => ({ width: 0, height: 0, top: 0, left: 0, right: 0, bottom: 0 }),
    closest: () => null,
    contains: () => false,
    get firstChild() { return this.children[0] || null; },
  };
  Object.defineProperty(el, 'textContent', {
    get() { return this._css; },
    set(v) { this._css = v; captured.push(v); },
  });
  return el;
}
const styleEl = makeEl('style');
const head = makeEl('head');
head.appendChild = (node) => { head.children.push(node); return node; };
const sandbox = {
  window: { __KOKONA_BRAND_URL__: 'data:image/svg+xml;base64,AAAA' },
  document: {
    head,
    documentElement: makeEl('html'),
    body: makeEl('body'),
    getElementById: () => null,
    createElement: (tag) => makeEl(tag),
    querySelector: () => null,
    querySelectorAll: () => [],
    addEventListener: () => {},
    removeEventListener: () => {},
  },
  console,
  setTimeout: () => 0,
  clearTimeout: () => {},
  setInterval: () => 0,
  clearInterval: () => {},
  MutationObserver: class { observe() {} disconnect() {} },
  addEventListener: () => {},
  removeEventListener: () => {},
  localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
  navigator: { userAgent: 'check-css' },
  location: { href: 'http://localhost/', search: '' },
  getComputedStyle: () => ({ getPropertyValue: () => '' }),
  matchMedia: () => ({ matches: false, addEventListener: () => {} }),
};
sandbox.window.document = sandbox.document;
sandbox.window.MutationObserver = sandbox.MutationObserver;
sandbox.window.requestAnimationFrame = () => 0;
sandbox.window.cancelAnimationFrame = () => {};
sandbox.window.setTimeout = () => 0;
sandbox.window.setInterval = () => 0;
sandbox.window.clearInterval = () => {};
sandbox.window.clearTimeout = () => {};
sandbox.window.addEventListener = () => {};
sandbox.window.removeEventListener = () => {};
sandbox.window.matchMedia = sandbox.matchMedia;
sandbox.window.localStorage = sandbox.localStorage;
sandbox.window.navigator = sandbox.navigator;
sandbox.window.location = sandbox.location;
sandbox.window.getComputedStyle = sandbox.getComputedStyle;

let css = '';
try {
  vm.runInNewContext(js, sandbox, { timeout: 5000 });
  // The CSS is produced by APPLY_BRAND / the style bootstrap.
  css = captured.join('\n');
} catch (err) {
  console.error('shim threw before producing CSS:', err.message);
  process.exit(1);
}
if (!css.trim()) {
  console.error('no CSS captured — the shim never created its <style> element');
  process.exit(1);
}

if (process.argv.includes('--print')) {
  console.log(css);
  process.exit(0);
}

// --- 3. structural checks ----------------------------------------------------
const problems = [];
const count = (s, re) => (s.match(re) || []).length;

const open = count(css, /\{/g);
const close = count(css, /\}/g);
if (open !== close) problems.push(`unbalanced braces: ${open} "{" vs ${close} "}"`);
if (count(css, /\(/g) !== count(css, /\)/g)) {
  problems.push(`unbalanced parens: ${count(css, /\(/g)} "(" vs ${count(css, /\)/g)} ")"`);
}
// Full-width punctuation is a classic IME slip and invalidates the declaration.
const fullWidth = css.match(/[：；，（）【】“”‘’]/g);
if (fullWidth) problems.push(`full-width punctuation: ${[...new Set(fullWidth)].join(' ')}`);
// `//` is not a CSS comment; it comments out the rest of the *line*, which in a
// minified sheet is the rest of the sheet.
if (/[^:]\/\/[^/]/.test(css)) problems.push('found "//" — not a CSS comment');
// A declaration block must not contain a nested block opener.
let depth = 0;
let line = 1;
for (const ch of css) {
  if (ch === '\n') line += 1;
  if (ch === '{') depth += 1;
  if (ch === '}') depth -= 1;
  if (depth < 0) { problems.push(`stray "}" at line ${line}`); depth = 0; }
}

// Group rules and report any with an empty body (a sign of a swallowed name).
const rules = css.split('}').filter((r) => r.includes('{'));
const empty = rules.filter((r) => /[^{]*\{\s*$/.test(r));
if (empty.length) problems.push(`${empty.length} rule(s) with an empty body`);

const selectors = rules.length;
console.log(`CSS: ${css.length} chars, ${selectors} rules, ${open} blocks`);
for (const key of ['sidebarCol', 'VOzbGW_panel', 'backdrop-filter', 'transparent!important']) {
  console.log(`  contains ${key}: ${css.includes(key)}`);
}

if (problems.length) {
  console.error('\nCSS PROBLEMS:');
  for (const p of problems) console.error(' - ' + p);
  process.exit(1);
}
console.log('CSS OK: structure parses');
