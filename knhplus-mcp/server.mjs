// KNHPlus MCP server (stdio, JSON-RPC 2.0, no external dependencies).
// It forwards Android-only requests to the app's loopback bridge, which uses
// Accessibility, notification access, AudioManager and normal app APIs.

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createInterface } from "node:readline";

const SERVER_NAME = "knhplus";
const SERVER_VERSION = "1.0.37";
const runDir = process.env.KOKONA_APP_RUN || "";
const log = (...args) => console.error("[knhplus]", ...args);

function bridgeConfig() {
  if (!runDir) throw new Error("KOKONA_APP_RUN 未设置");
  const port = readFileSync(join(runDir, "screenshot.port"), "utf8").trim();
  const token = readFileSync(join(runDir, "screenshot.token"), "utf8").trim();
  return { port, token };
}

/**
 * Capability grants are owned by the app (AndroidPermissions) and exposed at
 * /policy. The default only decides what happens before the user visits
 * Settings → 手机权限; it is not a risk opinion.
 */
const DEFAULT_PERMISSIONS = Object.freeze({
  screen: "allow",
  screenshot: "allow",
  input: "ask",
  system: "ask",
  notifications_read: "allow",
  notifications_act: "ask",
  media: "ask",
  clipboard: "ask",
  feedback: "allow"
});

const CAPABILITY_FOR_TOOL = {
  android_status: "screen",
  android_ui: "screen",
  android_find: "screen",
  android_wait_for: "screen",
  android_foreground: "screen",
  android_windows: "screen",
  android_screen: "screen",
  android_device: "screen",
  android_apps: "screen",
  android_screenshot: "screenshot",
  android_click: "input",
  android_set_text: "input",
  android_scroll: "input",
  android_gesture: "input",
  android_node_action: "input",
  android_global: "system",
  android_open: "system",
  android_open_settings: "system",
  android_notifications: "notifications_read",
  android_notification_action: "notifications_act",
  android_dismiss_notification: "notifications_act",
  android_dismiss_notifications: "notifications_act",
  android_media: "media",
  android_media_session: "media",
  android_volume: "media",
  android_brightness: "media",
  android_clipboard: "clipboard",
  android_toast: "feedback",
  android_vibrate: "feedback",
  android_speak: "feedback",
  android_tts_stop: "feedback"
};

const CAPABILITY_LABEL = {
  screen: "读取屏幕",
  screenshot: "截图",
  input: "点击与输入",
  system: "打开应用与系统操作",
  notifications_read: "读取通知",
  notifications_act: "通知操作",
  media: "媒体与音量亮度",
  clipboard: "剪贴板",
  feedback: "语音与振动"
};

async function fetchPermissions() {
  try {
    const response = await callBridge("/policy", {}, 5000);
    const body = await response.json();
    if (body && body.ok && body.permissions && typeof body.permissions === "object") {
      return { ...DEFAULT_PERMISSIONS, ...body.permissions };
    }
  } catch {
  }
  return { ...DEFAULT_PERMISSIONS };
}

async function callBridge(path, params = {}, timeoutMs = 20000) {
  const { port, token } = bridgeConfig();
  const url = new URL(`http://127.0.0.1:${port}${path}`);
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      url.searchParams.set(key, String(value));
    }
  }
  // The token travels in a header so it never appears in a URL, proxy log or
  // error message. Loopback traffic stays inside the app UID.
  const response = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(timeoutMs)
  });
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      if (body && body.error) message = body.error;
    } catch {
      message = (await response.text()) || message;
    }
    throw new Error(message);
  }
  return response;
}

async function callJson(params) {
  const response = await callBridge("/plus", params);
  return await response.json();
}

const TOOLS = [
  {
    name: "android_status",
    description: "读取手机状态：电量/充电、亮屏、媒体音量、亮度、无障碍与通知权限、截图可用性、Root/Shizuku。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false }
  },
  {
    name: "android_screenshot",
    description: "截取当前屏幕并直接返回图片。仅 Android 11+ 且无障碍服务已开启时可用；低于 Android 11 会失败，请勿重试，改用 android_ui 读取界面。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false }
  },
  {
    name: "android_ui",
    description: "导出当前界面的无障碍节点树（文字、描述、资源 id、坐标 bounds、可点击/可编辑/可滚动/勾选）。用于先看清界面再点击。",
    inputSchema: {
      type: "object",
      properties: {
        limit: { type: "integer", description: "最多返回节点数，默认 500" }
      },
      additionalProperties: false
    }
  },
  {
    name: "android_click",
    description: "点击界面元素：优先按文字匹配（可点击祖先），也可直接给屏幕像素坐标。长按用 duration>=400。",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "要匹配的可见文字或内容描述（包含匹配）" },
        id: { type: "string", description: "按控件资源 id 匹配（包含匹配，如 settings_button）" },
        desc: { type: "string", description: "按内容描述精确包含匹配" },
        x: { type: "integer", description: "点击横坐标（px）" },
        y: { type: "integer", description: "点击纵坐标（px）" },
        duration: { type: "integer", description: "按压时长毫秒，默认 80；>=400 触发长按" }
      },
      additionalProperties: false
    }
  },
  {
    name: "android_set_text",
    description: "向可编辑输入框写入文字（不需要切换输入法）。默认写当前聚焦框，可用 id/desc 指定目标。",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "要写入的文字" },
        id: { type: "string", description: "目标输入框的资源 id（包含匹配）" },
        desc: { type: "string", description: "目标输入框的内容描述" }
      },
      required: ["text"],
      additionalProperties: false
    }
  },
  {
    name: "android_scroll",
    description: "滚动当前界面中的可滚动区域。",
    inputSchema: {
      type: "object",
      properties: {
        direction: { type: "string", enum: ["forward", "backward"], description: "默认 forward" },
        times: { type: "integer", description: "连续滚动次数，1-20，默认 1" }
      },
      additionalProperties: false
    }
  },
  {
    name: "android_global",
    description: "执行系统全局操作：返回、桌面、最近任务、通知栏、快捷设置、电源菜单、锁屏、系统截屏。screenshot 仅 Android 11+，低版本请勿调用。",
    inputSchema: {
      type: "object",
      properties: {
        name: {
          type: "string",
          enum: ["back", "home", "recents", "notifications", "quicksettings", "power", "lock", "screenshot"]
        }
      },
      required: ["name"],
      additionalProperties: false
    }
  },
  {
    name: "android_open",
    description: "打开已安装应用（package）或链接（url）。",
    inputSchema: {
      type: "object",
      properties: {
        package: { type: "string", description: "应用包名" },
        url: { type: "string", description: "http(s)/scheme 链接" }
      },
      additionalProperties: false
    }
  },
  {
    name: "android_notifications",
    description: "读取当前通知栏（标题、正文、包名、时间、可清除状态和可点击按钮列表）。需开启通知使用权。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false }
  },
  {
    name: "android_dismiss_notifications",
    description: "清除可清除的通知（常驻通知会保留）。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false }
  },
  {
    name: "android_media",
    description: "控制系统正在播放的媒体（播放/暂停/下一首/上一首/停止）。",
    inputSchema: {
      type: "object",
      properties: {
        command: { type: "string", enum: ["play", "pause", "playpause", "next", "previous", "stop"] }
      },
      required: ["command"],
      additionalProperties: false
    }
  },
  {
    name: "android_volume",
    description: "设置媒体音量百分比、按格加减，或静音/取消静音。",
    inputSchema: {
      type: "object",
      properties: {
        percent: { type: "integer", description: "0-100" },
        direction: { type: "string", enum: ["up", "down"], description: "按一格加减音量" },
        mute: { type: "boolean", description: "true 静音 / false 取消静音" }
      },
      additionalProperties: false
    }
  },
  {
    name: "android_brightness",
    description: "设置屏幕亮度百分比（需授予“修改系统设置”）。",
    inputSchema: {
      type: "object",
      properties: { percent: { type: "integer", description: "1-100" } },
      required: ["percent"],
      additionalProperties: false
    }
  },
  {
    name: "android_speak",
    description: "用系统 TTS 朗读一段文字（手机特有的输出通道），可用 android_tts_stop 停止。",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string", description: "要朗读的文字" } },
      required: ["text"],
      additionalProperties: false
    }
  },
  {
    name: "android_apps",
    description: "列出可启动的已安装应用（包名 + 名称），可按关键词过滤。",
    inputSchema: {
      type: "object",
      properties: { q: { type: "string", description: "按包名或名称过滤" } },
      additionalProperties: false
    }
  },
  {
    name: "android_clipboard",
    description: "读取剪贴板文字，或写入剪贴板。Android 10+ 后台读取可能为空。",
    inputSchema: {
      type: "object",
      properties: { set: { type: "string", description: "要写入剪贴板的文字；省略则读取" } },
      additionalProperties: false
    }
  },
  {
    name: "android_vibrate",
    description: "让手机振动：给毫秒数，或用逗号分隔的节奏（如 100,80,100,80,300）。",
    inputSchema: {
      type: "object",
      properties: {
        ms: { type: "integer", description: "默认 300" },
        pattern: { type: "string", description: "逗号分隔的振动节奏（毫秒）" }
      },
      additionalProperties: false
    }
  },
  {
    name: "android_gesture",
    description: "派发任意触摸手势：tap、longpress、swipe、drag、pinch（双指缩放）、two_finger（双指滑动）。",
    inputSchema: {
      type: "object",
      properties: {
        kind: { type: "string", enum: ["tap", "longpress", "swipe", "drag", "pinch", "two_finger"] },
        x: { type: "integer", description: "起点 x / 中心 x" },
        y: { type: "integer", description: "起点 y / 中心 y" },
        x2: { type: "integer", description: "终点 x（swipe/drag/two_finger）" },
        y2: { type: "integer", description: "终点 y（swipe/drag/two_finger）" },
        duration: { type: "integer", description: "手势时长毫秒" },
        radius: { type: "integer", description: "pinch 初始双指半径，默认 300" },
        endRadius: { type: "integer", description: "pinch 结束半径，默认 80（变小=缩小）" },
        gap: { type: "integer", description: "two_finger 两指间距，默认 200" }
      },
      required: ["kind"],
      additionalProperties: false
    }
  },
  {
    name: "android_find",
    description: "在无障碍节点树中查找元素，返回索引、文字、id、坐标和状态，供后续精确操作。",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "可见文字（包含匹配）" },
        id: { type: "string", description: "资源 id（包含匹配）" },
        desc: { type: "string", description: "内容描述（包含匹配）" },
        class: { type: "string", description: "类名（包含匹配）" },
        clickable: { type: "boolean", description: "只找可点击节点" },
        editable: { type: "boolean", description: "只找可编辑节点" },
        scrollable: { type: "boolean", description: "只找可滚动节点" },
        checkable: { type: "boolean", description: "只找勾选控件" },
        limit: { type: "integer", description: "最多返回 1-50，默认 20" }
      },
      additionalProperties: false
    }
  },
  {
    name: "android_node_action",
    description: "对匹配节点执行操作：click、long_click、focus、clear_focus、set_text、scroll_forward/backward、expand、collapse、select、clear_selection、copy、cut、paste、dismiss、toggle_check。",
    inputSchema: {
      type: "object",
      properties: {
        op: { type: "string", description: "要执行的操作" },
        text: { type: "string", description: "按文字定位节点" },
        id: { type: "string", description: "按资源 id 定位节点" },
        desc: { type: "string", description: "按内容描述定位节点" },
        value: { type: "string", description: "set_text 时写入的文字" }
      },
      required: ["op"],
      additionalProperties: false
    }
  },
  {
    name: "android_wait_for",
    description: "等待界面元素出现（轮询无障碍树），用于跨应用自动化；超时返回错误。",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "可见文字" },
        id: { type: "string", description: "资源 id" },
        desc: { type: "string", description: "内容描述" },
        timeoutMs: { type: "integer", description: "超时毫秒，默认 10000，最多 15000" }
      },
      additionalProperties: false
    }
  },
  {
    name: "android_foreground",
    description: "读取当前前台应用（包名、名称、窗口类名、停留时长）。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false }
  },
  {
    name: "android_windows",
    description: "列出所有交互窗口（类型、层级、是否活动/聚焦、坐标、包名）。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false }
  },
  {
    name: "android_screen",
    description: "读取屏幕信息：分辨率、密度、刷新率、旋转、亮屏、锁屏、亮度。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false }
  },
  {
    name: "android_device",
    description: "读取设备信息：型号/系统/ABI/语言时区、电量温度、内存、存储、网络、开机时长。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false }
  },
  {
    name: "android_media_session",
    description: "读取当前所有媒体会话（应用、标题、歌手、播放状态），或对指定会话执行 play/pause/playpause/next/previous/stop/seek。需通知使用权。",
    inputSchema: {
      type: "object",
      properties: {
        command: { type: "string", enum: ["list", "play", "pause", "playpause", "next", "previous", "stop", "seek"], description: "省略等同 list" },
        index: { type: "integer", description: "会话索引，默认 0" },
        positionMs: { type: "integer", description: "seek 目标位置毫秒" }
      },
      additionalProperties: false
    }
  },
  {
    name: "android_notification_action",
    description: "点击通知上的按钮（回复、标记已读、归档等）。先由 android_notifications 取 key 和按钮索引。",
    inputSchema: {
      type: "object",
      properties: {
        key: { type: "string", description: "通知 key" },
        index: { type: "integer", description: "按钮索引，从 0 开始" }
      },
      required: ["key", "index"],
      additionalProperties: false
    }
  },
  {
    name: "android_dismiss_notification",
    description: "按 key 清除单条通知。",
    inputSchema: {
      type: "object",
      properties: { key: { type: "string", description: "通知 key" } },
      required: ["key"],
      additionalProperties: false
    }
  },
  {
    name: "android_open_settings",
    description: "直接打开系统设置面板：wifi、bluetooth、battery、display、sound、storage、accessibility、notification、usage、app、write_settings 等。",
    inputSchema: {
      type: "object",
      properties: {
        panel: {
          type: "string",
          enum: ["settings", "wifi", "bluetooth", "data", "airplane", "battery", "display", "sound", "storage", "accessibility", "notification", "usage", "default_apps", "location", "security", "locale", "input_method", "home", "nfc", "date", "developer", "vpn", "device", "write_settings", "app"]
        },
        package: { type: "string", description: "panel=app 时目标应用包名" }
      },
      required: ["panel"],
      additionalProperties: false
    }
  },
  {
    name: "android_toast",
    description: "在手机上弹出系统 Toast 提示（适合给用户即时反馈）。",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string", description: "提示文字" } },
      required: ["text"],
      additionalProperties: false
    }
  },
  {
    name: "android_tts_stop",
    description: "停止当前正在进行的 TTS 朗读。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false }
  },
  {
    name: "android_policy",
    description: "查看当前手机能力授权：screen/screenshot/input/system/notifications_read/notifications_act/media/clipboard/feedback，每项为 allow（直接执行）、ask（每次确认）、deny（禁止）。授权由用户在 App 设置 → 手机权限 中调整，模型不能修改。",
    inputSchema: {
      type: "object",
      properties: {
        action: { type: "string", enum: ["get"], description: "默认 get" }
      },
      additionalProperties: false
    }
  }
];

function toolResult(payload) {
  return { content: [{ type: "text", text: JSON.stringify(payload, null, 2) }] };
}

function describeOperation(name, args, capability) {
  const parts = [];
  for (const key of ["text", "value", "url", "package", "panel", "op", "name", "command", "kind", "x", "y", "x2", "y2"]) {
    if (args[key] !== undefined && args[key] !== null && args[key] !== "") {
      parts.push(`${key}=${String(args[key]).slice(0, 120)}`);
    }
  }
  const label = CAPABILITY_LABEL[capability] || capability;
  return `工具：${name}\n能力：${label}\n参数：${parts.length ? parts.join("；") : "无"}`.slice(0, 700);
}

async function confirmOperation(name, args, capability) {
  let body;
  try {
    const response = await callBridge("/confirm", {
      detail: describeOperation(name, args, capability),
      capability,
      timeout: 45
    }, 60000);
    body = await response.json();
  } catch (error) {
    throw new Error("无法弹出手机操作确认：" + (error && error.message ? error.message : String(error))
      + "。操作已取消；请先打开 KokonaHarness 应用，且不要自动重试。");
  }
  if (body && body.allowed && body.grant) return body.grant;
  if (body && body.allowed) {
    throw new Error("确认凭据缺失，操作已取消；请检查 App 与服务版本是否一致。");
  }
  if (body && body.reason === "app_not_visible") {
    throw new Error("需要打开 KokonaHarness 应用并在弹窗中确认，操作已取消；不要自动重试。");
  }
  throw new Error("用户拒绝或确认超时，操作已取消；不要自动重试，除非用户明确要求。");
}

/**
 * Resolves the user's grant for one capability. `ask` returns a single-use
 * grant that the app verifies again on the actual request, so a denied or
 * stale state cannot be reused.
 */
async function authorize(name, args) {
  const capability = CAPABILITY_FOR_TOOL[name];
  if (!capability) throw new Error(`工具没有声明能力：${name}`);
  const permissions = await fetchPermissions();
  const mode = permissions[capability] || "ask";
  if (mode === "deny") {
    throw new Error(`「${CAPABILITY_LABEL[capability] || capability}」权限已关闭；请在 App 设置 → 手机权限 中调整后重试。`);
  }
  if (mode === "ask") {
    const grant = await confirmOperation(name, args, capability);
    return { capability, grant };
  }
  return { capability };
}

async function runTool(name, args = {}) {
  if (name === "android_policy") {
    const permissions = await fetchPermissions();
    return toolResult({
      ok: true,
      permissions,
      note: "授权由用户在 App 设置 → 手机权限 中调整，模型不能修改。"
    });
  }
  const auth = await authorize(name, args);
  if (name === "android_screenshot") {
    const response = await callBridge("/screenshot", auth);
    const buffer = Buffer.from(await response.arrayBuffer());
    return {
      content: [{ type: "image", data: buffer.toString("base64"), mimeType: "image/png" }]
    };
  }
  const map = {
    android_status: { action: "status" },
    android_ui: { action: "ui", limit: args.limit },
    android_click: { action: "click", text: args.text, id: args.id, desc: args.desc, x: args.x, y: args.y, duration: args.duration },
    android_set_text: { action: "set_text", text: args.text, id: args.id, desc: args.desc },
    android_scroll: { action: "scroll", direction: args.direction, times: args.times },
    android_global: { action: "global", name: args.name },
    android_gesture: { action: "gesture", kind: args.kind, x: args.x, y: args.y, x2: args.x2, y2: args.y2, duration: args.duration, radius: args.radius, endRadius: args.endRadius, gap: args.gap },
    android_find: { action: "find", text: args.text, id: args.id, desc: args.desc, class: args.class, clickable: args.clickable, editable: args.editable, scrollable: args.scrollable, checkable: args.checkable, limit: args.limit },
    android_node_action: { action: "node_action", op: args.op, text: args.text, id: args.id, desc: args.desc, value: args.value },
    android_wait_for: { action: "wait_for", text: args.text, id: args.id, desc: args.desc, timeoutMs: args.timeoutMs },
    android_foreground: { action: "foreground" },
    android_windows: { action: "windows" },
    android_screen: { action: "screen" },
    android_device: { action: "device" },
    android_open: { action: "open", package: args.package, url: args.url },
    android_open_settings: { action: "open_settings", panel: args.panel, package: args.package },
    android_notifications: { action: "notifications" },
    android_notification_action: { action: "notification_action", key: args.key, index: args.index },
    android_dismiss_notification: { action: "dismiss_notification", key: args.key },
    android_dismiss_notifications: { action: "dismiss_notifications" },
    android_media: { action: "media", command: args.command },
    android_media_session: { action: "media_session", command: args.command, index: args.index, positionMs: args.positionMs },
    android_volume: { action: "volume", percent: args.percent, direction: args.direction, mute: args.mute },
    android_brightness: { action: "brightness", percent: args.percent },
    android_speak: { action: "speak", text: args.text },
    android_tts_stop: { action: "tts_stop" },
    android_toast: { action: "toast", text: args.text },
    android_apps: { action: "apps", q: args.q },
    android_clipboard: { action: "clipboard", set: args.set },
    android_vibrate: { action: "vibrate", ms: args.ms, pattern: args.pattern }
  };
  const params = map[name];
  if (!params) throw new Error(`未知工具：${name}`);
  const data = await callJson({ ...params, ...auth });
  return toolResult(data);
}

function send(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

function sendError(id, code, message) {
  send({ jsonrpc: "2.0", id, error: { code, message } });
}

async function handle(message) {
  const { id, method, params } = message;
  if (method === "initialize") {
    send({
      jsonrpc: "2.0",
      id,
      result: {
        protocolVersion: (params && params.protocolVersion) || "2025-06-18",
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: SERVER_NAME, version: SERVER_VERSION }
      }
    });
    return;
  }
  if (method === "notifications/initialized" || method === "notifications/cancelled") return;
  if (method === "ping") {
    send({ jsonrpc: "2.0", id, result: {} });
    return;
  }
  if (method === "tools/list") {
    send({ jsonrpc: "2.0", id, result: { tools: TOOLS } });
    return;
  }
  if (method === "tools/call") {
    const name = params && params.name;
    try {
      const result = await runTool(name, (params && params.arguments) || {});
      send({ jsonrpc: "2.0", id, result });
    } catch (error) {
      send({
        jsonrpc: "2.0",
        id,
        result: {
          content: [{ type: "text", text: "调用失败：" + (error && error.message ? error.message : String(error)) }],
          isError: true
        }
      });
    }
    return;
  }
  if (id !== undefined) sendError(id, -32601, `unsupported method: ${method}`);
}

log("ready");

const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on("line", (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let message;
  try {
    message = JSON.parse(trimmed);
  } catch (error) {
    log("bad json", error.message);
    return;
  }
  handle(message).catch((error) => {
    log("handler failed", error);
    if (message && message.id !== undefined) sendError(message.id, -32603, String(error));
  });
});
