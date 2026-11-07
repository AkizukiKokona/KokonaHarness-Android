# KokonaHarness 本地计划（2026-11-06）

> 用于上下文压缩后恢复。当前版本：App 1.2.2 / versionCode 122 / runtime `dsh-0.2.2-rc.1-r29`。
> 已完成并按现状保留：1.2.1 启动修复、侧栏滑动开合、Kokona 签名按钮、键盘 inset、增量 runtime 更新（manifest 优先）。

## 优先级

### P0 立即调查（先只查问题，不实现）
1. **dsh-better-sidebar 长按不能当右键**
   - 现象：文件树/侧栏行的长按无响应，行菜单、重命名、删除等操作无法触发。
   - 方向：插件只绑定了 `onContextMenu`（`lib/client.js` 约 7108/7172/7215 行），移动端长按没有合成 `contextmenu` 事件；需要在 touch/pointer 层补长按 → 调用同一 `contextMenu(event, path, isDir)`（`handleContextMenu` 约 7843 行），并避免与滚动/拖拽冲突。
   - 可能还要覆盖 Git 面板、历史记录等其它 `onContextMenu` 入口。
2. **设置界面全废**
   - 现象：装 better-sidebar 后设置页基本不可用，难以描述但整体残废。
   - 已知线索：插件注册了 `settings.section.icon` + `settings.section`（`lib/client.js` 约 34161–34176），并全局挂载 `[data-dsh-panel-host]`（`position:fixed; inset:0; z-index:25; pointer-events:none`，约 22740 行）；需要确认是否遮挡宿主设置弹层、导致无法点击/滚动，或插件设置分区在移动端布局溢出。
   - 方向：用本地 8790 测试服务 + 浏览器实际打开设置页，检查 DOM 层级、z-index、pointer-events、滚动容器；再决定改插件还是加宿主 shim CSS。

### P1 壁纸与设置开关
3. **竞赛壁纸开关 + 默认纯白**
   - 现状：`dsh-web-frontend/dist/index.html` 的 `#kokona-bg` 固定层（`position:fixed; inset:0; z-index:1; pointer-events:none; opacity:.22; background: kokona-bg.jpg`）。
   - 需求：设置里加开关，可恢复默认纯白；白色模式要接近纯白，不破坏第三方“换壁纸”插件（本项目不预装这类插件）。
   - 方向：开关持久化建议走 `KokonaBridge`/本地设置（与“手机权限/终端”同页），白色模式直接隐藏 `#kokona-bg` 且不写死 `body` 背景，把背景交还给主题/插件；壁纸模式保留现有图。注意设置页由 Web 前端渲染，需找现有自定义设置入口的注入点。
   - 可行性待调查后评估，暂无阻塞。

### P2 glibc/grun（等 P0/P1 调查后立即做）
4. **自编译 glibc + 按需 grun（不用 proot）**
   - 结论：可行；不能直接搬 Termux 包（glibc 编译时写死 `/data/data/com.termux/files/usr/glibc`）。
   - 方案：自编译 glibc，`--prefix=KokonaRuntime/kokona-glibc`；只改安全外层（目录、`kokona-grun`、pkgversion），**不改标准 soname**（`libc.so.6`、`ld-linux-aarch64.so.1`、`libstdc++.so.6` 等），靠私有目录 + 子进程环境隔离冲突。
   - 运行：默认 bionic 直接跑；`file` 判定 `ld-linux` 才 `kokona-grun`；grun 内清空 bionic `LD_LIBRARY_PATH`/`LD_PRELOAD`，只设 glibc 路径。
   - 分发：核心 glibc 可内置（预计压缩后 8–12 MB），扩展库按需下载（需签名校验、版本化、断网降级）；skill 写入运行时 skills，教模型判断 ELF interpreter。
   - 已知限制：Go/Rust 硬编码 `/etc/resolv.conf` 无 proot 修不了；Android seccomp（clone3/faccessat2）需保留 Termux 补丁；16 KB page size 设备需单独验证；只做 arm64-v8a；glibc LGPL/grun GPL 许可。
   - 下一步：先把 P0/P1 调查完，再起 glibc 构建；未实现前不要动 runtime 版本。

## 关键文件

- 侧栏插件：`/workspace/build/runtime/lib/node_modules/dsh-better-sidebar/lib/client.js`
- 壁纸：`/workspace/build/runtime/lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html`
- 宿主注入/设置：`app/java/com/knh/akizukikokona/MainActivity.java`
- 运行时/技能：`build-runtime.sh`、`runtime-skills/`
- 本地测试：`/workspace/build` 下 8790 服务（DSH_HOME=/tmp/kohome2/.dsh，如 /tmp 被清需重建 profile）

## 状态

- [ ] P0-1 长按右键调查
- [ ] P0-2 设置界面调查
- [ ] P1 壁纸开关可行性/注入点调查
- [ ] P2 glibc 自编译方案落地
