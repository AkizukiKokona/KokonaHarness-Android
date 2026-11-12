---
name: android-capabilities
description: 通过 KNHPlus MCP 使用 Android 专属能力（无障碍 UI 自动化、手势、系统截图、窗口/前台应用、通知栏按钮、媒体会话、全局导航、设备状态、TTS）。当任务需要在手机上操作其他 App、读取手机状态或做截图时使用。
whenToUse: 需要跨 App 点击/输入/滚动手势、查找并等待界面元素、读取通知或媒体会话、控制音量亮度、让手机朗读/振动、截图或读设备状态时。
---

# KNHPlus 安卓专属能力

KNHPlus 是内置 MCP 服务（`knhplus`），工具以 `android_*` 命名。全部能力不需要 Root；个别能力需要额外系统授权。

## 工具速查

| 工具 | 作用 | 前置条件 |
|---|---|---|
| `android_screenshot` | 返回当前屏幕 PNG | Android 11+ 用无障碍；低于 11 走 MediaProjection 授权截图 |
| `android_ui` | 界面节点树（文字/id/bounds/状态），`limit` 控制数量 | 无障碍 |
| `android_find` | 按文字/id/desc/class/状态查找元素，返回索引与坐标 | 无障碍 |
| `android_click` | 按文字/id/desc 或坐标点击，`duration>=400` 长按 | 无障碍 |
| `android_gesture` | tap / longpress / swipe / drag / pinch 双指缩放 / two_finger | 无障碍 |
| `android_set_text` | 向输入框写文字并**回读校验**；清空用 `text=""` 或 `clear=true`（可指定 id/desc） | 无障碍 |
| `android_node_action` | click、long_click、focus、set_text、scroll、expand/collapse、select、copy/cut/paste、dismiss、toggle_check | 无障碍 |
| `android_wait_for` | 轮询等待元素出现（默认 10s，最多 15s） | 无障碍 |
| `android_scroll` | 滚动，支持连续次数 | 无障碍 |
| `android_global` | back/home/recents/notifications/quicksettings/power/lock/screenshot | 无障碍（锁屏 9+、截屏 11+；低版本截图用 android_screenshot） |
| `android_foreground` | 当前前台应用、类名、停留时长（**可能返回 IME 窗口**；聚焦应用用 `android_windows`） | 无障碍 |
| `android_windows` | 所有交互窗口（类型/层级/bounds/包名） | 无障碍 |
| `android_open` | 打开应用包或 URL | 无 |
| `android_open_settings` | 直达 wifi/蓝牙/电池/显示/无障碍/通知/usage/app/write_settings 等面板 | 无 |
| `android_notifications` | 通知标题/正文/按钮列表/key | 通知使用权 |
| `android_notification_action` | 点击通知按钮（回复、标记已读等） | 通知使用权 |
| `android_dismiss_notification` | 按 key 清除单条通知 | 通知使用权 |
| `android_dismiss_notifications` | 清除可清除通知 | 通知使用权 |
| `android_status` | 电量、亮屏、音量、亮度、权限、前台应用 | 无 |
| `android_policy` | 查看九项手机能力的 allow / ask / deny（只读） | 修改需在 App 设置页 |
| `android_device` | 型号/系统/ABI/语言时区/电量温度/内存/存储/网络/开机时长 | 无 |
| `android_screen` | 分辨率、密度、刷新率、旋转、锁屏、亮度 | 无 |
| `android_media` | 媒体键（播放/暂停/上下首/停止） | 无 |
| `android_media_session` | 列出媒体会话（应用/标题/歌手/状态）并可 play/pause/next/prev/stop/seek | 通知使用权 |
| `android_volume` | 音量百分比、按格加减、静音 | 无 |
| `android_brightness` | 设置屏幕亮度 | “修改系统设置” |
| `android_speak` / `android_tts_stop` | 系统 TTS 朗读 / 停止 | 无 |
| `android_toast` | 弹出系统 Toast 反馈 | 无 |
| `android_apps` | 列出可启动应用，可按关键词过滤 | 无 |
| `android_clipboard` | 读写剪贴板 | 后台读取可能为空 |
| `android_vibrate` | 振动毫秒或节奏 | 无 |

## 使用要点

- 陌生界面先 `android_ui` 或截图，再 `android_find` 精确定位；能用 id 就不用文字。
- 跨应用流程：`android_open` → `android_wait_for` → `android_node_action`/`android_click`。
- 系统导航优先 `android_global`；坐标直接取节点 `bounds` 中心。
- `android_set_text` 写入后会回读节点树校验，**读回不一致直接返回失败**（不再假报 ok）；清空输入框用 `text=""` 或 `clear=true`。`android_foreground` 可能拿到 IME 窗口，取聚焦应用请用 `android_windows`。
- 通知/媒体会话未授权时，用 `android_open_settings panel=notification_listener` 直达系统「通知使用权」并勾选 KHA。
- 不要在没有明确指令时锁屏、清通知、打开未知链接或点击有副作用的按钮。

## 权限分级（重要）

- 权限由用户在 App「设置 → 手机权限」按能力配置，每项三档：`allow`（直接执行）、`ask`（每次确认）、`deny`（禁止）。
- 九项能力：读取屏幕、截图、点击与输入、打开应用与系统操作、读取通知、通知操作、媒体与音量亮度、剪贴板、语音与振动。
- 默认：读类为 `allow`，操作类为 `ask`。用户可以随时修改，改完立即生效。
- `ask` 会弹出原生确认框；用户拒绝或 45 秒未确认则本次操作取消。不要自动重试，除非用户明确要求。
- `deny` 表示用户关闭了该能力，工具会直接返回“权限已关闭”。不要尝试换工具或拆分步骤绕过。
- 模型不能修改授权；`android_policy` 只读，用于查看当前各项权限。不要在对话里要求用户提升权限，除非用户主动问。

## 限制

- `android_screenshot`：Android 11+ 走无障碍；低于 11 走 MediaProjection（弹一次系统授权框，允许后截一帧，不落盘）。`android_global` 的 `screenshot` 仅 Android 11+，低版本请用 `android_screenshot`。`FLAG_SECURE` 窗口会截成黑屏（系统行为）。`android_status.screenshotSupported` 恒为 true（SDK<30 由 MediaProjection 支撑）。锁屏全局动作仅 Android 9+。
- 无障碍/通知使用权必须用户在系统设置手动开启。
- Android 10+ 后台读剪贴板可能为空。
- 自绘控件可能不暴露可编辑/可点击节点；此时改用坐标手势。
- 亮度需要 WRITE_SETTINGS；Root/Shizuku 特权 shell 不在此 MCP 内。
