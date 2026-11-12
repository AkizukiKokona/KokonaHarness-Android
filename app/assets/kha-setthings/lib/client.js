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
    const updateInfo = () => {
      const b = bridge()
      try { return b === null ? null : JSON.parse(b.updateInfo() || '{}') } catch (error) { return null }
    }
    const text = (name, fallback) => {
      const b = bridge()
      try { return b === null ? fallback : String(b[name]() || fallback) } catch (error) { return fallback }
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
      '.khaSet_termbtn:hover{background:var(--dsw-alias-interactive-bg-hover,rgba(0,0,0,.05))}',
      '.khaSet_notes{white-space:pre-wrap;font-size:12px;line-height:18px;color:var(--dsw-alias-label-secondary,#666);max-height:180px;overflow-y:auto;background:rgba(127,127,127,.06);border-radius:8px;padding:8px 10px;margin:8px 0 0}',
      '.khaSet_kv{font-size:12px;line-height:18px;color:var(--dsw-alias-label-secondary,#666);margin:0 0 6px}'
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
        h('div', { className: 'khaSet_desc' }, '默认所有命令直接跑在 Android bionic 上，不开容器、不用 PRoot。未开启无障碍时点「无障碍模式」会打开系统设置；授权返回后自动切换。无障碍授权在重启 App 后会丢失，需要重新开启。'),
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
        !data.accessibility ? h('div', { className: 'khaSet_row', style: { marginTop: '10px' } },
          h('button', { type: 'button', className: 'khaSet_btn', onClick: () => call('openAccessibility') }, '去开启无障碍服务')) : null,
        h('div', { className: 'khaSet_row', style: { marginTop: '10px' } }, [
          h('button', { type: 'button', key: 'notif', className: 'khaSet_btn', onClick: () => call('openNotificationAccess') }, '通知使用权'),
          h('button', { type: 'button', key: 'write', className: 'khaSet_btn', onClick: () => call('openWriteSettings') }, '修改系统设置')
        ])
      )
    }

    /**
     * 简洁模式：关掉自定义背景层与磨砂（backdrop-filter）。它们本来就是同一套
     * 观感，只关一半会得到"半透明但没有背景"的怪异中间态，所以只提供两个互斥
     * 选项（默认样式 / 简洁模式），和本页其它选择项保持一致，不做成开关。
     * 状态由 App 持久化，页面只按 data-kha-simple 渲染，切换立即生效。
     */
    function SimpleModeBlock() {
      const simple = usePoll(() => {
        const b = bridge()
        try { return b !== null && typeof b.simpleMode === 'function' ? !!b.simpleMode() : false } catch (error) { return false }
      }, 1200)
      const STYLES = [
        ['default', '默认样式', '自定义背景 + 磨砂模糊（当前外观）'],
        ['simple', '简洁模式', '纯色背景、无模糊']
      ]
      return h('div', { className: 'khaSet_block' },
        h('div', { className: 'khaSet_title' }, '界面样式'),
        h('div', { className: 'khaSet_desc' }, '简洁模式会同时关闭自定义背景与磨砂（模糊）效果，界面回到纯白；两项一起生效，不能只关一半。默认样式即现在的外观。'),
        h('div', { className: 'khaSet_row' }, STYLES.map((m) =>
          h('button', {
            type: 'button', key: m[0], title: m[2],
            className: 'khaSet_btn' + (simple === (m[0] === 'simple') ? ' khaSet_on' : ''),
            onClick: () => call('setSimpleMode', m[0] === 'simple')
          }, m[1]))),
        h('div', { className: 'khaSet_state' },
          simple ? '当前 · 简洁模式（纯色背景、无模糊）' : '当前 · 默认样式（自定义背景 + 模糊）')
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

    // 检查更新：源可切换，默认 Codeberg；启动时 App 会自己查一次，这里用于手动
    // 触发和查看结果。跳过某个版本由 App 持久化，重启后仍然有效。
    function UpdateBlock() {
      const info = usePoll(updateInfo, 2000)
      const data = info || {}
      const source = data.source === 'github' ? 'github' : 'codeberg'
      const khaVersion = text('version', '?')
      const dsh = text('dshVersion', '?')
      let state
      if (data.error) state = '检查失败：' + data.error + '（可切换更新源后重试）'
      else if (!data.tag) state = '尚未检查'
      else if (data.newer && data.skipped) state = '已跳过 ' + data.tag + '，出现更高版本时会再次提醒'
      else if (data.newer) state = '发现新版本 ' + data.tag
      else state = '已是最新版本（' + data.tag + '）'
      return h('div', { className: 'khaSet_block' },
        h('div', { className: 'khaSet_title' }, '检查更新'),
        h('div', { className: 'khaSet_desc' }, '默认从 Codeberg 拉取版本信息，连不上时可以换成 GitHub。启动时会自动检查一次。'),
        h('div', { className: 'khaSet_kv' }, '当前 KHA ' + khaVersion + ' · DSH ' + dsh),
        h('div', { className: 'khaSet_row' },
          h('button', {
            type: 'button', key: 'cb',
            className: 'khaSet_btn' + (source === 'codeberg' ? ' khaSet_on' : ''),
            onClick: () => call('setUpdateSource', 'codeberg')
          }, 'Codeberg'),
          h('button', {
            type: 'button', key: 'gh',
            className: 'khaSet_btn' + (source === 'github' ? ' khaSet_on' : ''),
            onClick: () => call('setUpdateSource', 'github')
          }, 'GitHub')),
        h('div', { className: 'khaSet_row' },
          h('button', { type: 'button', className: 'khaSet_btn', onClick: () => call('checkUpdate') }, '立即检查'),
          data.newer ? h('button', { type: 'button', key: 'show', className: 'khaSet_btn', onClick: () => call('showUpdate') }, '查看更新') : null,
          data.newer ? h('button', { type: 'button', key: 'skip', className: 'khaSet_btn', onClick: () => call('skipUpdate', data.tag) }, '跳过 ' + data.tag) : null),
        h('div', { className: 'khaSet_state' }, state),
        data.newer && data.notes ? h('div', { className: 'khaSet_notes' }, data.notes) : null
      )
    }

    function AboutBlock() {
      const info = usePoll(() => {
        const b = bridge()
        try { return JSON.parse(b.diagnosticsUi() || '{}') } catch (error) { return {} }
      }, 3000)
      return h('div', { className: 'khaSet_block' },
        h('div', { className: 'khaSet_title' }, '关于 KHA'),
        h('div', { className: 'khaSet_desc' }, 'KHA ' + text('version', '?') + ' · DSH ' + text('dshVersion', '?')
          + ' · 本页由 kha-setthings 插件提供，不影响官方设置布局。'),
        h('div', { className: 'khaSet_state' }, '工具链直接跑在 Android bionic 上；以 glibc 链接的程序由 kokona-grun 按需接住。终端入口在本页右上角。'),
        h('div', { className: 'khaSet_kv' }, '界面透明度自检：' + (info.sheet
          ? '样式表已注入 · 侧栏 ' + (info.drawerBg || '未渲染') + ' / 模糊 ' + (info.drawerFilter || '无')
            + ' · 设置 ' + (info.panelBg || '未渲染') + ' / ' + (info.panelFilter || '无')
            + ' · 支持 backdrop-filter：' + (info.blur ? '是' : '否')
          : '样式表未注入')),
        h('div', { className: 'khaSet_row', style: { marginTop: '10px' } },
          h('button', { type: 'button', className: 'khaSet_btn', onClick: () => call('toast', '已请求一次界面自检，稍后回来看这一行') }, '刷新自检'),
          h('button', { type: 'button', className: 'khaSet_btn', onClick: () => call('setWebDebug', true) }, '开启界面调试'))
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
        h(SimpleModeBlock, { key: 'simple' }),
        h(PermissionsBlock, { key: 'perm' }),
        h(AboutBlock, { key: 'about' }),
        h(UpdateBlock, { key: 'update' }))
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
