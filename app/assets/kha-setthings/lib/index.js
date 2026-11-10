// Host half of kha-setthings. Everything lives in the browser bundle; the
// Host only needs this module to exist so dsh loads ./client (declared in
// package.json under dsh.client).
export const name = 'kha-setthings'

export const inject = []

export function apply() {}
