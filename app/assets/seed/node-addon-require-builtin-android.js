"use strict";
// Android has no published prebuilt binding for node-addon-require-builtin
// (0.1.9 ships only darwin/linux/win32 optional packages). The engine launches
// node with --expose-internals, so require() can reach internal modules
// directly; replace the native entry with that JS path instead of failing to
// boot the whole host.
const { createRequire } = require("node:module");

const nativeRequire = createRequire(__filename);

function requireBuiltin(moduleId) {
    return nativeRequire(moduleId);
}

function isAllowedInternalId(moduleId) {
    return typeof moduleId === "string" && moduleId.startsWith("internal/");
}

function getBindingInfo() {
    return {
        backend: "js",
        abi: "napi-v9",
        product: "require-builtin",
        mode: "unrestricted",
        bindingPath: null,
        bindingSource: "android-js-fallback",
    };
}

exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
exports.default = { requireBuiltin, isAllowedInternalId, getBindingInfo };
