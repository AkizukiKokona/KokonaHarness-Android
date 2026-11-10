/**
 * kha-setthings — the single KHA-owned tab in the official settings dialog.
 *
 * Registers exactly one `settings.section` (all of our old custom sections are
 * folded into it, stacked top-to-bottom) and one small `settings.action` in the
 * header's top-right corner. No file of the official settings UI is patched.
 */
window.__ModuleLoader__.load({
  id: 'kha-setthings',
  factory: (require) => {
    var module = { exports: {} }
    var exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })

    const react = require('react')
    const h = react.createElement

    const ID = '[kha-setthings]'
    const bridge = () => {
      try {
        return typeof KokonaBridge !== 'undefined' ? KokonaBridge : null
      } catch (error) {
        return null
      }
    }
    const runInfo = () => {
      const b = bridge()
      try { return b === null ? null : JSON.parse(b.runModeInfo() || '{}') } catch (error) { return null }
    }
    const permInfo = () => {
      const b = bridge()
      try { return b === null ? null : JSON.parse(b.androidPermissions() || 'null') } catch (error) { return null }
    }
    const call = (name, ...args) => {
      const b = bridge()
      try { if (b !== null && typeof b[name] === 'function') b[name](...args) } catch (error) { console.warn(ID, name, error) }
    }

    const CSS = [
      '.khaSet{box-sizing:border-box;max-width:760px;padding:16px 18px 28px;overflow-y:auto;color:var(--dsw-alias-label-primary,#222);font-family:inherit}',
      '.khaSet_block{border:1px solid var(--dsw-alias-border-l4,#e3e3e8);border-radius:12px;padding:14px 16px;margin:0 0 14px}',
      '.khaSet_title{font-size:15px;font-weight:600;line-height:22px;margin:0 0 4px}',
      '.khaSet_desc{font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary,#888);margin:0 0 12px}',
      '.khaSet_row{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 10px}',
      '.khaSet_btn{box-sizing:border-box;border:1px solid var(--dsw-alias-border-l4,#e3e3e8);border-radius:8px;background:transparent;color:inherit;font:inherit;font-size:13px;line-height:20px;padding:6px 12px;cursor:pointer}',
      '.khaSet_btn:hover{background:var(--dsw-alias-interactive-bg-hover,rgba(0,0,0,.05))}',
      '.khaSet_on{background:var(--dsw-alias-bg-module-platform,#eef2ff);border-color:#7c9cff}',
      '.khaSet_state{font-size:12px;line-height:18px;color:var(--dsw-alias-label-secondary,#666)}',
      '.khaSet_badge{display:inline-block;border-radius:999px;padding:1px 8px;font-size:11px;line-height:18px;background:rgba(127,127,127,.14);margin:0 6px 4px 0}',
      '.khaSet_perm{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:9px 0;border-bottom:1px solid rgba(127,127,127,.14)}',
      '.khaSet_perm:last-child{border-bottom:none}',
      '.khaSet_pname{flex:1;min-width:150px;font-size:13px;line-height:20px}',
      '.khaSet_pdesc{font-size:11px;line-height:17px;color:var(--dsw-alias-label-tertiary,#888)}',
      '.khaSet_modes{display:flex;gap:4px}',
      '.khaSet_mode{box-sizing:border-box;border:1px solid var(--dsw-alias-border-l4,#e3e3e8);border-radius:6px;background:transparent;color:inherit;font:inherit;font-size:12px;line-height:18px;padding:3px 10px;cursor:pointer}',
      '.khaSet_mode_on{background:var(--dsw-alias-bg-module-platform,#eef2ff);border-color:#7c9cff}',
      '.khaSet_termbtn{box-sizing:border-box;width:28px;height:28px;border:1px solid var(--dsw-alias-border-l3,#d8d8de);border-radius:6px;background:transparent;color:inherit;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;cursor:pointer;padding:0}',
      '.khaSet_termbtn:hover{background:var(--dsw-alias-interactive-bg-hover,rgba(0,0,0,.05))}'
    ].join('')

    const CAPS = [
      { id: 'screen', label: '读取屏幕', desc: '界面文字、节点树、前台应用与设备状态' },
      { id: 'screenshot', label: '截图', desc: '读取当前屏幕画面，可能包含隐私内容' },
      { id: 'input', label: '点击与输入', desc: '点击、长按、输入文字、滑动与手势' },
      { id: 'system', label: '打开应用与系统操作', desc: '打开应用或设置、返回主页、锁屏等' },
      { id: 'notifications_read', label: '读取通知', desc: '读取通知内容，可能包含验证码' },
      { id: 'notifications_act', label: '通知操作', desc: '回复、忽略或点击通知按钮' },
      { id: 'media', label: '媒体与音量亮度', desc: '播放控制、音量与亮度' },
      { id: 'clipboard', label: '剪贴板', desc: '读取或写入剪贴板' },
      { id: 'feedback', label: '语音与振动', desc: 'TTS 朗读、振动与 Toast' }
    ]
    const PERM_MODES = [['allow', '允许'], ['ask', '询问'], ['deny', '禁止']]
    const RUN_MODES = [
      ['normal', '普通模式', '无需 Root / Shizuku'],
      ['accessibility', '无障碍模式', '界面自动化、手势与截图'],
      ['advanced', '高级模式', 'Shizuku 与 Root']
    ]

    const usePoll = (read, ms) => {
      const [value, setValue] = react.useState(read)
      react.useEffect(() => {
        const timer = setInterval(() => setValue(read()), ms)
        return () => clearInterval(timer)
      }, [])
      return value
    }

    const badge = (text, key) => h('span', { className: 'khaSet_badge', key: key }, text)

    function RunModeBlock() {
      const data = usePoll(runInfo, 1500)
      if (data === null) {
        return h('div', { className: 'khaSet_block' },
          h('div', { className: 'khaSet_title' }, '运行模式'),
          h('div', { className: 'khaSet_desc' }, '此项仅在 KHA App 内可用。'))
      }
      const mode = data.mode || 'normal'
      const channel = data.effective || 'normal'
      let summary
      if (mode === 'advanced') summary = channel === 'root' ? '高级模式 · Root' : channel === 'shizuku' ? '高级模式 · Shizuku' : '高级模式不可用，当前按普通模式运行'
      else if (mode === 'accessibility') summary = data.accessibility ? '无障碍模式 · 界面自动化已就绪' : '无障碍模式 · 服务尚未开启'
      else summary = '普通模式 · 应用沙箱内以 bionic 原生运行'
      const badges = [
        [data.rootGranted ? 'Root 已授权' : data.suFound ? 'Root 未授权' : '无 Root'],
        [data.shizukuGranted ? 'Shizuku 已授权' : data.shizukuInstalled ? 'Shizuku 未授权' : '未安装 Shizuku'],
        [data.accessibility ? '无障碍已开启' : '无障碍未开启']
      ]
      return h('div', { className: 'khaSet_block' },
        h('div', { className: 'khaSet_title' }, '运行模式'),
        h('div', { className: 'khaSet_desc' }, '默认所有命令直接跑在 Android bionic 上，不开容器、不用 PRoot。'),
        h('div', { className: 'khaSet_row' }, RUN_MODES.map((m) =>
          h('button', {
            type: 'button', key: m[0], title: m[2],
            className: 'khaSet_btn' + (mode === m[0] ? ' khaSet_on' : ''),
            onClick: () => call('setRunMode', m[0])
          }, m[1]))),
        h('div', { className: 'khaSet_state' }, summary),
        h('div', { style: { marginTop: '8px' } }, badges.map((b, i) => badge(b[0], i))),
        mode === 'advanced' ? h('div', { className: 'khaSet_row', style: { marginTop: '10px' } }, [
          h('button', { type: 'button', key: 'root', className: 'khaSet_btn', onClick: () => call('requestRoot') }, '授权 Root'),
          h('button', { type: 'button', key: 'shizuku', className: 'khaSet_btn', onClick: () => call(data.shizukuInstalled ? 'requestShizuku' : 'openShizuku') }, 'Shizuku'),
          h('button', { type: 'button', key: 'a11y', className: 'khaSet_btn', onClick: () => call('openAccessibility') }, '无障碍设置')
        ]) : null,
        mode === 'accessibility' ? h('div', { className: 'khaSet_row', style: { marginTop: '10px' } },
          h('button', { type: 'button', className: 'khaSet_btn', onClick: () => call('openAccessibility') }, '去开启无障碍服务')) : null,
        h('div', { className: 'khaSet_row', style: { marginTop: '10px' } }, [
          h('button', { type: 'button', key: 'notif', className: 'khaSet_btn', onClick: () => call('openNotificationAccess') }, '通知使用权'),
          h('button', { type: 'button', key: 'write', className: 'khaSet_btn', onClick: () => call('openWriteSettings') }, '修改系统设置')
        ])
      )
    }

    function PermissionsBlock() {
      const data = usePoll(permInfo, 2000)
      const permissions = (data && data.permissions) || {}
      return h('div', { className: 'khaSet_block' },
        h('div', { className: 'khaSet_title' }, '手机能力授权'),
        h('div', { className: 'khaSet_desc' }, '允许=直接执行，询问=每次弹窗确认，禁止=模型调用会被拒绝。模型只能读取、不能自行修改。'),
        CAPS.map((cap) => h('div', { className: 'khaSet_perm', key: cap.id },
          h('div', { className: 'khaSet_pname' }, cap.label, h('div', { className: 'khaSet_pdesc' }, cap.desc)),
          h('div', { className: 'khaSet_modes' }, PERM_MODES.map((m) =>
            h('button', {
              type: 'button', key: m[0],
              className: 'khaSet_mode' + ((permissions[cap.id] || 'ask') === m[0] ? ' khaSet_mode_on' : ''),
              onClick: () => call('setAndroidPermission', cap.id, m[0])
            }, m[1])))
        )),
        h('div', { className: 'khaSet_row', style: { marginTop: '12px' } },
          h('button', { type: 'button', className: 'khaSet_btn', onClick: () => call('resetAndroidPermissions') }, '恢复默认'))
      )
    }

    function AboutBlock() {
      const b = bridge()
      let version = '1.0.42'
      try { if (b !== null && typeof b.version === 'function') version = b.version() } catch (error) { /* keep fallback */ }
      return h('div', { className: 'khaSet_block' },
        h('div', { className: 'khaSet_title' }, '关于 KHA'),
        h('div', { className: 'khaSet_desc' }, 'KHA ' + version + ' · 本页由 kha-setthings 插件提供，不影响官方设置布局。'),
        h('div', { className: 'khaSet_state' }, '工具链直接跑在 Android bionic 上；以 glibc 链接的程序由 kokona-grun 按需接住。终端入口在本页右上角。')
      )
    }

    function KhaSetthingsSection() {
      react.useEffect(() => {
        if (document.querySelector('style[data-kha-setthings]') !== null) return
        const tag = document.createElement('style')
        tag.dataset.khaSetthings = ''
        tag.textContent = CSS
        document.head.appendChild(tag)
      }, [])
      return h('div', { className: 'khaSet' },
        h(RunModeBlock, { key: 'run' }),
        h(PermissionsBlock, { key: 'perm' }),
        h(AboutBlock, { key: 'about' }))
    }

    function KhaTerminalButton() {
      return h('button', {
        type: 'button',
        className: 'khaSet_termbtn',
        title: 'KHA 终端',
        'aria-label': 'KHA 终端',
        onClick: () => call('openTerminal')
      }, '>_')
    }

    function apply(ctx) {
      // Each slot is registered behind its own guard: an unknown or unavailable
      // slot must never take the settings tab down with it. The terminal action
      // is a convenience, the section is the point.
      try {
        ctx.slots.inject('settings.section', () => ctx.slots.register({
          name: 'settings.section',
          id: 'kha-setthings',
          order: 50,
          label: () => 'KHA 设置'
        }, KhaSetthingsSection))
      } catch (error) {
        console.warn(ID, 'settings.section registration failed', error)
      }
      try {
        ctx.slots.inject('settings.action', () => ctx.slots.register({
          name: 'settings.action',
          id: 'kha-terminal',
          order: 10,
          inject: () => ({})
        }, KhaTerminalButton))
      } catch (error) {
        // No header action slot in this build: the section still works.
        console.warn(ID, 'settings.action registration failed', error)
      }
    }

    // A client plugin only receives the services it declares here. Without
    // `slots` the guarded context rejects `ctx.slots` outright, the throw is
    // swallowed by the registration guards below, and the tab simply never
    // appears — with nothing in the log to explain why.
    exports.inject = ['slots']
    exports.apply = apply
    return module.exports
  }
})
