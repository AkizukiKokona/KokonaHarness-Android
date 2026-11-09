'use strict';
/*
 * KHA Node 26 startup shim.
 *
 * On some Android builds Node 26's native package.json reader
 * (modulesBinding.readPackageJSON / getPackageScopeConfig / ...) fails
 * non-deterministically with ERR_INVALID_PACKAGE_CONFIG even for perfectly
 * valid, readable package.json files. Node 26 exposes an official loader
 * override hook, so we keep the native fast path and only fall back to a
 * plain JS JSON.parse when the native reader throws that specific error.
 *
 * Loaded with:  node --expose-internals --require <this file> <main.js> ...
 */
const fs = require('fs');
const path = require('path');

let helpers = null;
try {
  helpers = require('internal/modules/helpers');
} catch (e) {
  // --expose-internals not available: leave node untouched.
}

if (helpers && helpers.loaderMethods && helpers.setLoaderOverrides) {
  const methods = helpers.loaderMethods;
  const noResult = helpers.kLoaderOverrideNoResult;

  // deserializePackageJSON() decides between "plain value" and "stringified
  // JSON" by inspecting the first character, so anything starting with '{' or
  // '[' must be handed over as a *string*. Passing an object here would make
  // it JSON.parse("[object Object]"-ish garbage) and blow up the loader.
  const plain = (value) => {
    if (value === undefined || value === null) return null;
    if (typeof value === 'string') return value;
    return JSON.stringify(value);
  };

  const toFile = (p) => {
    if (typeof p !== 'string') return p;
    if (p.startsWith('file://')) {
      try { return decodeURIComponent(new URL(p).pathname); } catch (e) { return p.slice(7); }
    }
    return p;
  };

  const nearest = (start) => {
    let dir = start;
    for (let i = 0; i < 64; i++) {
      const candidate = path.join(dir, 'package.json');
      try {
        if (fs.statSync(candidate).isFile()) return candidate;
      } catch (e) { /* keep walking up */ }
      const parent = path.dirname(dir);
      if (parent === dir) return null;
      dir = parent;
    }
    return null;
  };

  // Tuple consumed by deserializePackageJSON():
  // [name, main, type, plainImports, plainExports, optionalFilePath]
  const serialize = (pkgPath) => {
    const parsed = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
    const type = (parsed.type === 'module' || parsed.type === 'commonjs')
      ? parsed.type : 'none';
    return [
      parsed.name === undefined ? null : parsed.name,
      parsed.main === undefined ? null : parsed.main,
      type,
      plain(parsed.imports),
      plain(parsed.exports),
      pkgPath,
    ];
  };

  const typeOf = (pkgPath) => {
    if (!pkgPath) return 'none';
    try {
      const t = JSON.parse(fs.readFileSync(pkgPath, 'utf8')).type;
      return (t === 'module' || t === 'commonjs') ? t : 'none';
    } catch (e) { return 'none'; }
  };

  const overrides = {};
  const wrap = (key, fallback) => {
    const saved = methods[key];
    let fallingBack = false;
    overrides[key] = function (...args) {
      // Re-entrant call from the saved wrapper: returning undefined makes the
      // wrapper fall through to its pinned native implementation.
      if (fallingBack) return undefined;
      fallingBack = true;
      try {
        return saved(...args);
      } catch (err) {
        if (!err || err.code !== 'ERR_INVALID_PACKAGE_CONFIG') throw err;
        try {
          return fallback(...args);
        } catch (e2) {
          throw err;
        }
      } finally {
        fallingBack = false;
      }
    };
  };

  wrap('readPackageJSON', (jsonPath) => serialize(toFile(jsonPath)));

  wrap('getPackageScopeConfig', (resolved) => {
    const file = toFile(resolved);
    const pkg = nearest(path.dirname(file));
    return pkg ? serialize(pkg) : file;
  });

  wrap('getNearestParentPackageJSON', (checkPath) => {
    const pkg = nearest(toFile(checkPath));
    return pkg ? serialize(pkg) : noResult;
  });

  wrap('getNearestParentPackageJSONType', (checkPath) =>
      typeOf(nearest(toFile(checkPath))));

  wrap('getPackageType', (url) =>
      typeOf(nearest(path.dirname(toFile(url)))));

  helpers.setLoaderOverrides(overrides);
}
