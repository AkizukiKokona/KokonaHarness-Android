// Host half of KHA-Setthings. Everything lives in the browser bundle; the
// Host only needs this module to exist so dsh loads ./client (declared in
// package.json under dsh.client).
export const name = 'KHA-Setthings'

export const inject = []

export function apply() {}
