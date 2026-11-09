package com.kokonaharness.android.akizuki;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * KNHPlus: Android-only capabilities exposed through the loopback bridge.
 *
 * <p>These are the things a desktop agent cannot do on a phone: drive other
 * apps through the accessibility tree, dispatch multi-finger gestures,
 * navigate with system global actions, read the notification shade, control
 * media sessions, or speak out loud. Everything works without root; only the
 * parts that need an extra grant say so in their error message.</p>
 */
public final class AndroidCapabilities {
    private static final int MAX_NODES = 500;
    private static final int MAX_DEPTH = 40;

    private static final Object TTS_LOCK = new Object();
    private static TextToSpeech ttsEngine;
    private static String ttsPending;
    private static boolean ttsReady;
    private static String ttsError;

    private AndroidCapabilities() {
    }

    public static String run(Context context, Uri uri) throws Exception {
        String action = uri.getQueryParameter("action");
        if (action == null) throw new IllegalArgumentException("missing action");
        switch (action) {
            case "status": return status(context);
            case "ui": return uiTree(uri);
            case "click": return click(uri);
            case "set_text": return setText(uri);
            case "scroll": return scroll(uri);
            case "global": return global(uri);
            case "gesture": return gesture(context, uri);
            case "find": return find(uri);
            case "node_action": return nodeAction(uri);
            case "wait_for": return waitFor(uri);
            case "foreground": return foreground(context);
            case "windows": return windows();
            case "screen": return screen(context);
            case "device": return device(context);
            case "open": return open(context, uri);
            case "open_settings": return openSettings(context, uri);
            case "notifications": return notifications();
            case "notification_action": return notificationAction(uri);
            case "dismiss_notification": return dismissNotification(uri);
            case "dismiss_notifications": return dismissNotifications();
            case "media": return media(context, uri);
            case "media_session": return mediaSession(context, uri);
            case "volume": return volume(context, uri);
            case "brightness": return brightness(context, uri);
            case "speak": return speak(context, uri);
            case "tts_stop": return ttsStop();
            case "toast": return toast(context, uri);
            case "apps": return apps(context, uri);
            case "clipboard": return clipboard(context, uri);
            case "vibrate": return vibrate(context, uri);
            default: throw new IllegalArgumentException("未知操作：" + action);
        }
    }

    private static JSONObject ok() throws Exception {
        JSONObject json = new JSONObject();
        json.put("ok", true);
        return json;
    }

    private static String status(Context context) throws Exception {
        JSONObject json = ok();
        json.put("sdk", Build.VERSION.SDK_INT);
        json.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        json.put("accessibility", ScreenshotAccessibilityService.isConnected());
        json.put("screenshotSupported", Build.VERSION.SDK_INT >= 30);
        if (Build.VERSION.SDK_INT < 30) {
            json.put("screenshotNote", "Android 11 以下不支持无障碍截图，请改用 android_ui");
        }
        json.put("notificationAccess", NotificationAccessService.isConnected());
        json.put("root", RunMode.isRootGranted(context));
        json.put("shizuku", RunMode.isShizukuGranted());

        BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (battery != null) {
            json.put("battery", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            json.put("charging", battery.isCharging());
        }
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        json.put("screenOn", power != null && power.isInteractive());
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        json.put("locked", keyguard != null && keyguard.isKeyguardLocked());
        json.put("foreground", ScreenshotAccessibilityService.foregroundPackage());

        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            json.put("volumePercent", max == 0 ? 0 : Math.round(current * 100f / max));
            json.put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC));
        }
        try {
            int brightness = Settings.System.getInt(
                    context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            json.put("brightnessPercent", Math.round(brightness * 100f / 255f));
        } catch (Throwable ignored) {
        }
        json.put("canWriteSettings", Settings.System.canWrite(context));
        return json.toString();
    }

    private static String uiTree(Uri uri) throws Exception {
        AccessibilityNodeInfo root = ScreenshotAccessibilityService.rootNode();
        if (root == null) throw new IllegalStateException("无障碍服务未开启或当前没有窗口");
        int limit = intParam(uri, "limit", MAX_NODES);
        if (limit < 1) limit = 1;
        if (limit > 2000) limit = 2000;
        JSONArray nodes = new JSONArray();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{root, 0});
        while (!queue.isEmpty() && nodes.length() < limit) {
            Object[] pair = queue.removeFirst();
            AccessibilityNodeInfo node = (AccessibilityNodeInfo) pair[0];
            int depth = (Integer) pair[1];
            nodes.put(describe(node, depth));
            if (depth >= MAX_DEPTH) continue;
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) queue.addLast(new Object[]{child, depth + 1});
            }
        }
        JSONObject json = ok();
        json.put("count", nodes.length());
        json.put("nodes", nodes);
        return json.toString();
    }

    private static JSONObject describe(AccessibilityNodeInfo node, int depth) throws Exception {
        JSONObject json = new JSONObject();
        json.put("depth", depth);
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence cls = node.getClassName();
        CharSequence id = node.getViewIdResourceName();
        if (text != null) json.put("text", text.toString());
        if (desc != null) json.put("description", desc.toString());
        if (cls != null) json.put("class", cls.toString());
        if (id != null) json.put("id", id.toString());
        json.put("package", node.getPackageName() == null ? "" : node.getPackageName().toString());
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        json.put("bounds", bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom);
        if (node.isClickable()) json.put("clickable", true);
        if (node.isLongClickable()) json.put("longClickable", true);
        if (node.isEditable()) json.put("editable", true);
        if (node.isScrollable()) json.put("scrollable", true);
        if (node.isFocused()) json.put("focused", true);
        if (node.isSelected()) json.put("selected", true);
        if (!node.isEnabled()) json.put("enabled", false);
        if (node.isCheckable()) json.put("checked", node.isChecked());
        return json;
    }

    /** A node selector shared by find / wait / node_action. */
    private static final class Sel {
        String text;
        String id;
        String desc;
        String cls;
        boolean clickable;
        boolean editable;
        boolean scrollable;
        boolean checkable;

        boolean any() {
            return text != null || id != null || desc != null || cls != null;
        }

        boolean match(AccessibilityNodeInfo node) {
            if (text != null && !contains(node.getText(), text)
                    && !contains(node.getContentDescription(), text)) return false;
            if (desc != null && !contains(node.getContentDescription(), desc)) return false;
            if (id != null && !contains(node.getViewIdResourceName(), id)) return false;
            if (cls != null && !contains(node.getClassName(), cls)) return false;
            if (clickable && !node.isClickable()) return false;
            if (editable && !node.isEditable()) return false;
            if (scrollable && !node.isScrollable()) return false;
            if (checkable && !node.isCheckable()) return false;
            return true;
        }
    }

    private static boolean contains(CharSequence value, String needle) {
        return value != null && value.toString().contains(needle);
    }

    private static Sel selector(Uri uri) {
        Sel sel = new Sel();
        sel.text = emptyToNull(uri.getQueryParameter("text"));
        sel.id = emptyToNull(uri.getQueryParameter("id"));
        sel.desc = emptyToNull(uri.getQueryParameter("desc"));
        sel.cls = emptyToNull(uri.getQueryParameter("class"));
        sel.clickable = boolParam(uri, "clickable", false);
        sel.editable = boolParam(uri, "editable", false);
        sel.scrollable = boolParam(uri, "scrollable", false);
        sel.checkable = boolParam(uri, "checkable", false);
        return sel;
    }

    private static AccessibilityNodeInfo findFirst(AccessibilityNodeInfo node, Sel sel, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        if (sel.match(node)) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findFirst(node.getChild(index), sel, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static void collect(AccessibilityNodeInfo node, Sel sel, int depth,
                                List<AccessibilityNodeInfo> out, int limit) {
        if (node == null || depth > MAX_DEPTH || out.size() >= limit) return;
        if (sel.match(node)) out.add(node);
        for (int index = 0; index < node.getChildCount() && out.size() < limit; index++) {
            collect(node.getChild(index), sel, depth + 1, out, limit);
        }
    }

    private static String find(Uri uri) throws Exception {
        AccessibilityNodeInfo root = ScreenshotAccessibilityService.rootNode();
        if (root == null) throw new IllegalStateException("无障碍服务未开启");
        Sel sel = selector(uri);
        if (!sel.any() && !sel.clickable && !sel.editable && !sel.scrollable && !sel.checkable) {
            throw new IllegalArgumentException("需要 text/id/desc/class 或至少一个过滤条件");
        }
        int limit = intParam(uri, "limit", 20);
        if (limit < 1) limit = 1;
        if (limit > 50) limit = 50;
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collect(root, sel, 0, found, limit);
        JSONArray array = new JSONArray();
        for (int index = 0; index < found.size(); index++) {
            AccessibilityNodeInfo node = found.get(index);
            JSONObject item = describe(node, 0);
            item.put("index", index);
            array.put(item);
        }
        JSONObject json = ok();
        json.put("count", array.length());
        json.put("nodes", array);
        return json.toString();
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node,
                                                           boolean longClick) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (longClick ? current.isLongClickable() : current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private static String click(Uri uri) throws Exception {
        AccessibilityNodeInfo root = ScreenshotAccessibilityService.rootNode();
        if (root == null) throw new IllegalStateException("无障碍服务未开启");
        Sel sel = selector(uri);
        if (sel.any()) {
            AccessibilityNodeInfo match = findFirst(root, sel, 0);
            if (match == null) throw new IllegalStateException("界面上找不到匹配节点");
            long duration = intParam(uri, "duration", 80);
            AccessibilityNodeInfo target = clickableAncestor(match, duration >= 400);
            if (target == null) throw new IllegalStateException("该节点不可点击（可改用坐标）");
            int action = duration >= 400
                    ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                    : AccessibilityNodeInfo.ACTION_CLICK;
            if (!target.performAction(action)) {
                throw new IllegalStateException("点击被系统拒绝");
            }
            JSONObject json = ok();
            json.put("clicked", describe(target, 0));
            return json.toString();
        }
        int x = intParam(uri, "x", -1);
        int y = intParam(uri, "y", -1);
        if (x < 0 || y < 0) throw new IllegalArgumentException("需要 text/id/desc 或 x/y 坐标");
        int duration = intParam(uri, "duration", 80);
        ScreenshotAccessibilityService.gesture(x, y, x, y, Math.max(1, duration));
        JSONObject json = ok();
        json.put("clicked", x + "," + y);
        return json.toString();
    }

    private static String setText(Uri uri) throws Exception {
        AccessibilityNodeInfo root = ScreenshotAccessibilityService.rootNode();
        if (root == null) throw new IllegalStateException("无障碍服务未开启");
        String text = uri.getQueryParameter("text");
        if (text == null) throw new IllegalArgumentException("missing text");
        Sel sel = new Sel();
        sel.id = emptyToNull(uri.getQueryParameter("id"));
        sel.desc = emptyToNull(uri.getQueryParameter("desc"));
        AccessibilityNodeInfo target = sel.any() ? findFirst(root, sel, 0) : findEditable(root, 0);
        if (target == null) throw new IllegalStateException("当前界面没有可编辑输入框");
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle bundle = new Bundle();
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
            throw new IllegalStateException("系统拒绝写入文字");
        }
        JSONObject json = ok();
        json.put("text", text);
        return json.toString();
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        if (node.isEditable()) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(index), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static String scroll(Uri uri) throws Exception {
        AccessibilityNodeInfo root = ScreenshotAccessibilityService.rootNode();
        if (root == null) throw new IllegalStateException("无障碍服务未开启");
        String direction = uri.getQueryParameter("direction");
        boolean forward = direction == null || !direction.equals("backward");
        int times = intParam(uri, "times", 1);
        if (times < 1) times = 1;
        if (times > 20) times = 20;
        int done = 0;
        for (int index = 0; index < times; index++) {
            AccessibilityNodeInfo scrollable = findScrollable(root, 0);
            if (scrollable == null) {
                if (done == 0) throw new IllegalStateException("当前界面没有可滚动区域");
                break;
            }
            int action = forward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            if (!scrollable.performAction(action)) {
                if (done == 0) throw new IllegalStateException("滚动被系统拒绝");
                break;
            }
            done++;
        }
        JSONObject json = ok();
        json.put("forward", forward);
        json.put("times", done);
        return json.toString();
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        if (node.isScrollable()) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findScrollable(node.getChild(index), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static String nodeAction(Uri uri) throws Exception {
        AccessibilityNodeInfo root = ScreenshotAccessibilityService.rootNode();
        if (root == null) throw new IllegalStateException("无障碍服务未开启");
        String op = uri.getQueryParameter("op");
        if (op == null) throw new IllegalArgumentException("missing op");
        Sel sel = selector(uri);
        if (!sel.any()) throw new IllegalArgumentException("需要 text/id/desc 定位节点");
        AccessibilityNodeInfo match = findFirst(root, sel, 0);
        if (match == null) throw new IllegalStateException("界面上找不到匹配节点");
        AccessibilityNodeInfo target = match;
        String label = op;
        switch (op) {
            case "click":
            case "long_click": {
                boolean longClick = op.equals("long_click");
                target = clickableAncestor(match, longClick);
                if (target == null) throw new IllegalStateException("该节点不支持" + op);
                if (!target.performAction(longClick
                        ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                        : AccessibilityNodeInfo.ACTION_CLICK)) {
                    throw new IllegalStateException("系统拒绝：" + op);
                }
                break;
            }
            case "focus":
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                break;
            case "clear_focus":
                target.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
                break;
            case "set_text": {
                String value = uri.getQueryParameter("value");
                if (value == null) throw new IllegalArgumentException("missing value");
                Bundle bundle = new Bundle();
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
                    throw new IllegalStateException("系统拒绝写入文字");
                }
                break;
            }
            case "scroll_forward":
                if (!target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    throw new IllegalStateException("无法向前滚动");
                }
                break;
            case "scroll_backward":
                if (!target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    throw new IllegalStateException("无法向后滚动");
                }
                break;
            case "expand":
                if (!target.performAction(AccessibilityNodeInfo.ACTION_EXPAND)) {
                    throw new IllegalStateException("无法展开节点");
                }
                break;
            case "collapse":
                if (!target.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE)) {
                    throw new IllegalStateException("无法折叠节点");
                }
                break;
            case "select":
                target.performAction(AccessibilityNodeInfo.ACTION_SELECT);
                break;
            case "clear_selection":
                target.performAction(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION);
                break;
            case "copy":
                target.performAction(AccessibilityNodeInfo.ACTION_COPY);
                break;
            case "cut":
                target.performAction(AccessibilityNodeInfo.ACTION_CUT);
                break;
            case "paste":
                target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                break;
            case "dismiss":
                target.performAction(AccessibilityNodeInfo.ACTION_DISMISS);
                break;
            case "toggle_check": {
                if (!target.isCheckable()) throw new IllegalStateException("节点不是勾选控件");
                if (!target.isClickable()) {
                    AccessibilityNodeInfo parent = clickableAncestor(target, false);
                    if (parent != null) target = parent;
                }
                if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    throw new IllegalStateException("系统拒绝切换勾选");
                }
                break;
            }
            default: throw new IllegalArgumentException("未知节点操作：" + op);
        }
        JSONObject json = ok();
        json.put("op", label);
        json.put("node", describe(target, 0));
        return json.toString();
    }

    private static String waitFor(Uri uri) throws Exception {
        Sel sel = selector(uri);
        if (!sel.any()) throw new IllegalArgumentException("需要 text/id/desc 定位节点");
        int timeout = intParam(uri, "timeoutMs", 10000);
        if (timeout < 500) timeout = 500;
        if (timeout > 15000) timeout = 15000;
        long started = SystemClock.uptimeMillis();
        while (true) {
            AccessibilityNodeInfo root = ScreenshotAccessibilityService.rootNode();
            if (root != null) {
                AccessibilityNodeInfo found = findFirst(root, sel, 0);
                if (found != null) {
                    JSONObject json = ok();
                    json.put("waitedMs", SystemClock.uptimeMillis() - started);
                    json.put("node", describe(found, 0));
                    return json.toString();
                }
            }
            if (SystemClock.uptimeMillis() - started >= timeout) {
                throw new IllegalStateException("等待超时（" + timeout + "ms），节点未出现");
            }
            Thread.sleep(250);
        }
    }

    private static String foreground(Context context) throws Exception {
        String pkg = ScreenshotAccessibilityService.foregroundPackage();
        String cls = ScreenshotAccessibilityService.foregroundClass();
        if (pkg == null) {
            AccessibilityWindowInfo active = ScreenshotAccessibilityService.activeWindow();
            if (active != null && active.getRoot() != null) {
                AccessibilityNodeInfo root = active.getRoot();
                pkg = root.getPackageName() == null ? null : root.getPackageName().toString();
                cls = root.getClassName() == null ? null : root.getClassName().toString();
            }
        }
        JSONObject json = ok();
        if (pkg == null) {
            json.put("package", "");
            json.put("note", "无障碍服务未开启或尚未收到窗口事件");
            return json.toString();
        }
        json.put("package", pkg);
        if (cls != null) json.put("class", cls);
        long since = ScreenshotAccessibilityService.foregroundSince();
        if (since >= 0) json.put("sinceMs", SystemClock.uptimeMillis() - since);
        try {
            PackageManager manager = context.getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(pkg, 0);
            json.put("label", manager.getApplicationLabel(info).toString());
        } catch (Throwable ignored) {
        }
        return json.toString();
    }

    private static String windows() throws Exception {
        List<AccessibilityWindowInfo> list = ScreenshotAccessibilityService.windows();
        if (list == null) throw new IllegalStateException("无障碍服务未开启");
        JSONArray array = new JSONArray();
        for (AccessibilityWindowInfo window : list) {
            JSONObject item = new JSONObject();
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            item.put("type", windowTypeName(window.getType()));
            item.put("layer", window.getLayer());
            item.put("active", window.isActive());
            item.put("focused", window.isFocused());
            item.put("bounds", bounds.left + "," + bounds.top + ","
                    + bounds.right + "," + bounds.bottom);
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                CharSequence cls = root.getClassName();
                if (pkg != null) item.put("package", pkg.toString());
                if (cls != null) item.put("class", cls.toString());
            }
            array.put(item);
        }
        JSONObject json = ok();
        json.put("count", array.length());
        json.put("windows", array);
        return json.toString();
    }

    private static String windowTypeName(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "application";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "input_method";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "accessibility_overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "split_divider";
            default: return "unknown";
        }
    }

    private static String screen(Context context) throws Exception {
        JSONObject json = ok();
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager != null) {
            Display display = manager.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            json.put("width", metrics.widthPixels);
            json.put("height", metrics.heightPixels);
            json.put("densityDpi", metrics.densityDpi);
            json.put("density", metrics.density);
            json.put("refreshRate", display.getRefreshRate());
            int rotation = display.getRotation();
            int degrees = 0;
            if (rotation == android.view.Surface.ROTATION_90) degrees = 90;
            else if (rotation == android.view.Surface.ROTATION_180) degrees = 180;
            else if (rotation == android.view.Surface.ROTATION_270) degrees = 270;
            json.put("rotation", degrees);
        }
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        json.put("screenOn", power != null && power.isInteractive());
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        json.put("locked", keyguard != null && keyguard.isKeyguardLocked());
        try {
            int brightness = Settings.System.getInt(
                    context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            json.put("brightnessPercent", Math.round(brightness * 100f / 255f));
        } catch (Throwable ignored) {
        }
        return json.toString();
    }

    private static String device(Context context) throws Exception {
        JSONObject json = ok();
        json.put("manufacturer", Build.MANUFACTURER);
        json.put("model", Build.MODEL);
        json.put("device", Build.DEVICE);
        json.put("sdk", Build.VERSION.SDK_INT);
        json.put("release", Build.VERSION.RELEASE);
        json.put("abi", Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0]);
        json.put("locale", Locale.getDefault().toLanguageTag());
        json.put("timezone", java.util.TimeZone.getDefault().getID());
        json.put("uptimeMs", SystemClock.elapsedRealtime());

        BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (battery != null) {
            json.put("battery", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            json.put("charging", battery.isCharging());
        }
        try {
            Intent sticky = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) {
                int temp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                if (temp != 0) json.put("batteryTempC", temp / 10.0);
            }
        } catch (Throwable ignored) {
        }

        ActivityManager activity = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activity != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            activity.getMemoryInfo(memory);
            json.put("memTotalMb", memory.totalMem / (1024 * 1024));
            json.put("memFreeMb", memory.availMem / (1024 * 1024));
            json.put("lowMemory", memory.lowMemory);
        }
        try {
            StatFs stat = new StatFs(context.getFilesDir().getAbsolutePath());
            long blockSize = stat.getBlockSizeLong();
            json.put("storageTotalMb", stat.getBlockCountLong() * blockSize / (1024 * 1024));
            json.put("storageFreeMb", stat.getAvailableBlocksLong() * blockSize / (1024 * 1024));
        } catch (Throwable ignored) {
        }

        ConnectivityManager connectivity = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) {
            NetworkInfo info = connectivity.getActiveNetworkInfo();
            json.put("network", info == null ? "none" : info.getTypeName());
            json.put("connected", info != null && info.isConnected());
        }
        return json.toString();
    }

    private static String global(Uri uri) throws Exception {
        String name = uri.getQueryParameter("name");
        if (name == null) throw new IllegalArgumentException("missing name");
        int action;
        switch (name) {
            case "back": action = AccessibilityService.GLOBAL_ACTION_BACK; break;
            case "home": action = AccessibilityService.GLOBAL_ACTION_HOME; break;
            case "recents": action = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quicksettings": action = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power": action = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG; break;
            case "lock":
                if (Build.VERSION.SDK_INT < 28) {
                    throw new IllegalStateException("锁屏操作需要 Android 9 及以上");
                }
                action = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;
                break;
            case "screenshot":
                if (Build.VERSION.SDK_INT < 30) {
                    throw new IllegalStateException("当前系统 Android " + Build.VERSION.SDK_INT
                            + " 低于 Android 11，无障碍系统截屏不可用；请勿重试，改用 android_ui 获取界面信息");
                }
                action = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT;
                break;
            default: throw new IllegalArgumentException("未知全局操作：" + name);
        }
        if (!ScreenshotAccessibilityService.globalAction(action)) {
            throw new IllegalStateException("系统拒绝执行：" + name);
        }
        JSONObject json = ok();
        json.put("global", name);
        return json.toString();
    }

    private static String gesture(Context context, Uri uri) throws Exception {
        String kind = uri.getQueryParameter("kind");
        if (kind == null) kind = "tap";
        int x = intParam(uri, "x", -1);
        int y = intParam(uri, "y", -1);
        int x2 = intParam(uri, "x2", -1);
        int y2 = intParam(uri, "y2", -1);
        int duration = intParam(uri, "duration", -1);
        int width = 1080;
        int height = 1920;
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            manager.getDefaultDisplay().getRealMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
        }
        List<Path> paths = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        List<Long> durations = new ArrayList<>();

        switch (kind) {
            case "tap": {
                if (x < 0 || y < 0) throw new IllegalArgumentException("tap 需要 x/y");
                Path path = new Path();
                path.moveTo(x, y);
                paths.add(path);
                starts.add(0L);
                durations.add((long) Math.max(1, duration < 0 ? 60 : duration));
                break;
            }
            case "longpress": {
                if (x < 0 || y < 0) throw new IllegalArgumentException("longpress 需要 x/y");
                Path path = new Path();
                path.moveTo(x, y);
                paths.add(path);
                starts.add(0L);
                durations.add((long) Math.max(400, duration < 0 ? 600 : duration));
                break;
            }
            case "swipe":
            case "drag": {
                if (x < 0 || y < 0 || x2 < 0 || y2 < 0) {
                    throw new IllegalArgumentException("swipe/drag 需要 x/y/x2/y2");
                }
                Path path = new Path();
                path.moveTo(x, y);
                path.lineTo(x2, y2);
                paths.add(path);
                starts.add(0L);
                durations.add((long) Math.max(1, duration < 0 ? 300 : duration));
                break;
            }
            case "pinch": {
                int cx = x < 0 ? width / 2 : x;
                int cy = y < 0 ? height / 2 : y;
                int startRadius = intParam(uri, "radius", 300);
                int endRadius = intParam(uri, "endRadius", 80);
                long ms = Math.max(1, duration < 0 ? 400 : duration);
                Path left = new Path();
                left.moveTo(cx - startRadius, cy);
                left.lineTo(cx - endRadius, cy);
                Path right = new Path();
                right.moveTo(cx + startRadius, cy);
                right.lineTo(cx + endRadius, cy);
                paths.add(left);
                paths.add(right);
                starts.add(0L);
                starts.add(0L);
                durations.add(ms);
                durations.add(ms);
                break;
            }
            case "two_finger": {
                if (x < 0 || y < 0 || x2 < 0 || y2 < 0) {
                    throw new IllegalArgumentException("two_finger 需要 x/y/x2/y2");
                }
                int gap = intParam(uri, "gap", 200);
                long ms = Math.max(1, duration < 0 ? 300 : duration);
                Path first = new Path();
                first.moveTo(x, y);
                first.lineTo(x2, y2);
                Path second = new Path();
                second.moveTo(x + gap, y);
                second.lineTo(x2 + gap, y2);
                paths.add(first);
                paths.add(second);
                starts.add(0L);
                starts.add(0L);
                durations.add(ms);
                durations.add(ms);
                break;
            }
            default: throw new IllegalArgumentException("未知手势：" + kind);
        }

        long[] startArray = new long[starts.size()];
        long[] durationArray = new long[durations.size()];
        for (int index = 0; index < starts.size(); index++) {
            startArray[index] = starts.get(index);
            durationArray[index] = durations.get(index);
        }
        ScreenshotAccessibilityService.gesture(paths, startArray, durationArray);
        JSONObject json = ok();
        json.put("gesture", kind);
        json.put("strokes", paths.size());
        return json.toString();
    }

    private static String open(Context context, Uri uri) throws Exception {
        String pkg = uri.getQueryParameter("package");
        String url = uri.getQueryParameter("url");
        if (pkg != null && !pkg.isEmpty()) {
            PackageManager manager = context.getPackageManager();
            Intent launch = manager.getLaunchIntentForPackage(pkg);
            if (launch == null) throw new IllegalStateException("应用无法启动：" + pkg);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        } else if (url != null && !url.isEmpty()) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(view);
        } else {
            throw new IllegalArgumentException("需要 package 或 url");
        }
        JSONObject json = ok();
        json.put("opened", pkg != null ? pkg : url);
        return json.toString();
    }

    private static String openSettings(Context context, Uri uri) throws Exception {
        String panel = uri.getQueryParameter("panel");
        if (panel == null) throw new IllegalArgumentException("missing panel");
        String pkg = uri.getQueryParameter("package");
        Intent intent;
        switch (panel) {
            case "settings": intent = new Intent(Settings.ACTION_SETTINGS); break;
            case "wifi": intent = new Intent(Settings.ACTION_WIFI_SETTINGS); break;
            case "bluetooth": intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS); break;
            case "data": intent = new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS); break;
            case "airplane": intent = new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS); break;
            case "battery": intent = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS); break;
            case "display": intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS); break;
            case "sound": intent = new Intent(Settings.ACTION_SOUND_SETTINGS); break;
            case "storage": intent = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS); break;
            case "accessibility": intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); break;
            case "notification":
                intent = new Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS");
                break;
            case "usage": intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS); break;
            case "default_apps":
                intent = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
                break;
            case "location": intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS); break;
            case "security": intent = new Intent(Settings.ACTION_SECURITY_SETTINGS); break;
            case "locale": intent = new Intent(Settings.ACTION_LOCALE_SETTINGS); break;
            case "input_method": intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS); break;
            case "home": intent = new Intent(Settings.ACTION_HOME_SETTINGS); break;
            case "nfc": intent = new Intent(Settings.ACTION_NFC_SETTINGS); break;
            case "date": intent = new Intent(Settings.ACTION_DATE_SETTINGS); break;
            case "developer":
                intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                break;
            case "vpn": intent = new Intent(Settings.ACTION_VPN_SETTINGS); break;
            case "device": intent = new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS); break;
            case "write_settings":
                intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + context.getPackageName()));
                break;
            case "app":
                if (pkg == null || pkg.isEmpty()) {
                    throw new IllegalArgumentException("app 面板需要 package");
                }
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg));
                break;
            default: throw new IllegalArgumentException("未知设置面板：" + panel);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException error) {
            context.startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        JSONObject json = ok();
        json.put("panel", panel);
        return json.toString();
    }

    private static String notifications() throws Exception {
        return NotificationAccessService.listJson();
    }

    private static String notificationAction(Uri uri) throws Exception {
        String key = uri.getQueryParameter("key");
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("missing key");
        int index = intParam(uri, "index", -1);
        JSONObject json = ok();
        json.put("action", NotificationAccessService.action(key, index));
        return json.toString();
    }

    private static String dismissNotification(Uri uri) throws Exception {
        String key = uri.getQueryParameter("key");
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("missing key");
        JSONObject json = ok();
        json.put("dismissed", NotificationAccessService.dismiss(key));
        return json.toString();
    }

    private static String dismissNotifications() throws Exception {
        JSONObject json = ok();
        json.put("dismissed", NotificationAccessService.dismissAll());
        return json.toString();
    }

    private static String media(Context context, Uri uri) throws Exception {
        String cmd = uri.getQueryParameter("command");
        if (cmd == null) throw new IllegalArgumentException("missing command");
        int key;
        switch (cmd) {
            case "play": case "pause": case "playpause":
                key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "next": key = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": key = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "stop": key = KeyEvent.KEYCODE_MEDIA_STOP; break;
            default: throw new IllegalArgumentException("未知媒体命令：" + cmd);
        }
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new IllegalStateException("缺少音频服务");
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        JSONObject json = ok();
        json.put("media", cmd);
        return json.toString();
    }

    private static String mediaSession(Context context, Uri uri) throws Exception {
        MediaSessionManager manager = (MediaSessionManager)
                context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (manager == null) throw new IllegalStateException("缺少媒体会话服务");
        ComponentName listener = new ComponentName(context, NotificationAccessService.class);
        List<MediaController> sessions;
        try {
            sessions = manager.getActiveSessions(listener);
        } catch (SecurityException error) {
            throw new IllegalStateException("需要通知使用权才能读取媒体会话，请在 环境高级设置 中开启");
        }
        if (sessions == null) sessions = new ArrayList<>();
        String command = uri.getQueryParameter("command");
        int index = intParam(uri, "index", 0);
        JSONObject json = ok();

        if (command == null || command.isEmpty() || command.equals("list")) {
            JSONArray array = new JSONArray();
            for (int position = 0; position < sessions.size(); position++) {
                MediaController controller = sessions.get(position);
                JSONObject item = new JSONObject();
                item.put("index", position);
                item.put("package", controller.getPackageName());
                try {
                    android.media.MediaMetadata metadata = controller.getMetadata();
                    if (metadata != null) {
                        android.media.MediaDescription description = metadata.getDescription();
                        if (description != null) {
                            CharSequence title = description.getTitle();
                            CharSequence artist = description.getSubtitle();
                            if (title != null) item.put("title", title.toString());
                            if (artist != null) item.put("artist", artist.toString());
                        }
                        String album = metadata.getString(
                                android.media.MediaMetadata.METADATA_KEY_ALBUM);
                        if (album != null) item.put("album", album);
                    }
                } catch (Throwable ignored) {
                }
                PlaybackState state = controller.getPlaybackState();
                if (state != null) {
                    item.put("state", playbackStateName(state.getState()));
                    item.put("playing", state.getState() == PlaybackState.STATE_PLAYING);
                    item.put("positionMs", state.getPosition());
                    item.put("speed", state.getPlaybackSpeed());
                }
                array.put(item);
            }
            json.put("count", array.length());
            json.put("sessions", array);
            return json.toString();
        }

        if (index < 0 || index >= sessions.size()) {
            throw new IllegalStateException("媒体会话索引无效：" + index + "（共 " + sessions.size() + " 个）");
        }
        MediaController controller = sessions.get(index);
        MediaController.TransportControls controls = controller.getTransportControls();
        switch (command) {
            case "play": controls.play(); break;
            case "pause": controls.pause(); break;
            case "playpause": {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) controls.pause();
                else controls.play();
                break;
            }
            case "next": controls.skipToNext(); break;
            case "previous": controls.skipToPrevious(); break;
            case "stop": controls.stop(); break;
            case "seek": {
                long position = longParam(uri, "positionMs", 0);
                controls.seekTo(position);
                break;
            }
            default: throw new IllegalArgumentException("未知媒体命令：" + command);
        }
        json.put("media", command);
        json.put("index", index);
        json.put("package", controller.getPackageName());
        return json.toString();
    }

    private static String playbackStateName(int state) {
        switch (state) {
            case PlaybackState.STATE_NONE: return "none";
            case PlaybackState.STATE_STOPPED: return "stopped";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_FAST_FORWARDING: return "fast_forwarding";
            case PlaybackState.STATE_REWINDING: return "rewinding";
            case PlaybackState.STATE_BUFFERING: return "buffering";
            case PlaybackState.STATE_ERROR: return "error";
            default: return "unknown(" + state + ")";
        }
    }

    private static String volume(Context context, Uri uri) throws Exception {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new IllegalStateException("缺少音频服务");
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        String mute = uri.getQueryParameter("mute");
        if (mute != null) {
            boolean muted = Boolean.parseBoolean(mute);
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            JSONObject json = ok();
            json.put("muted", muted);
            return json.toString();
        }
        String direction = uri.getQueryParameter("direction");
        if (direction != null) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    direction.equals("up") ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            JSONObject json = ok();
            json.put("volumePercent", max == 0 ? 0 : Math.round(current * 100f / max));
            return json.toString();
        }
        int percent = intParam(uri, "percent", -1);
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("percent 需在 0-100");
        int target = Math.round(max * percent / 100f);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        JSONObject json = ok();
        json.put("volumePercent", percent);
        return json.toString();
    }

    private static String brightness(Context context, Uri uri) throws Exception {
        if (!Settings.System.canWrite(context)) {
            throw new IllegalStateException("未授予“修改系统设置”，请在 环境高级设置 中开启");
        }
        int percent = intParam(uri, "percent", -1);
        if (percent < 1 || percent > 100) throw new IllegalArgumentException("percent 需在 1-100");
        int value = Math.round(255 * percent / 100f);
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, value);
        JSONObject json = ok();
        json.put("brightnessPercent", percent);
        return json.toString();
    }

    private static String speak(Context context, Uri uri) throws Exception {
        String text = uri.getQueryParameter("text");
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("missing text");
        speakText(context.getApplicationContext(), text);
        JSONObject json = ok();
        json.put("speaking", text);
        return json.toString();
    }

    private static void speakText(final Context app, final String text) {
        synchronized (TTS_LOCK) {
            if (ttsEngine == null) {
                ttsPending = text;
                ttsError = null;
                ttsEngine = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                    @Override
                    public void onInit(int status) {
                        synchronized (TTS_LOCK) {
                            if (status != TextToSpeech.SUCCESS) {
                                ttsError = "TTS 初始化失败";
                                if (ttsEngine != null) ttsEngine.shutdown();
                                ttsEngine = null;
                                ttsPending = null;
                                return;
                            }
                            ttsReady = true;
                            ttsEngine.setLanguage(Locale.getDefault());
                            if (ttsPending != null) {
                                ttsEngine.speak(ttsPending, TextToSpeech.QUEUE_FLUSH,
                                        null, "knhplus");
                                ttsPending = null;
                            }
                        }
                    }
                });
                return;
            }
            if (!ttsReady) {
                ttsPending = text;
                return;
            }
            ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "knhplus");
        }
    }

    private static String ttsStop() throws Exception {
        synchronized (TTS_LOCK) {
            if (ttsEngine != null) ttsEngine.stop();
        }
        JSONObject json = ok();
        json.put("stopped", true);
        return json.toString();
    }

    private static String toast(Context context, Uri uri) throws Exception {
        final String text = uri.getQueryParameter("text");
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("missing text");
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        JSONObject json = ok();
        json.put("toast", text);
        return json.toString();
    }

    private static String apps(Context context, Uri uri) throws Exception {
        PackageManager manager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = manager.queryIntentActivities(launcher, 0);
        String filter = uri.getQueryParameter("q");
        List<String> seen = new ArrayList<>();
        JSONArray array = new JSONArray();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (seen.contains(pkg)) continue;
            seen.add(pkg);
            String label = info.loadLabel(manager).toString();
            if (filter != null && !filter.isEmpty()
                    && !pkg.toLowerCase().contains(filter.toLowerCase())
                    && !label.toLowerCase().contains(filter.toLowerCase())) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("package", pkg);
            item.put("label", label);
            array.put(item);
        }
        JSONObject json = ok();
        json.put("count", array.length());
        json.put("apps", array);
        return json.toString();
    }

    private static String clipboard(Context context, Uri uri) throws Exception {
        ClipboardManager clipboard = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) throw new IllegalStateException("缺少剪贴板服务");
        String set = uri.getQueryParameter("set");
        if (set != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("KNHPlus", set));
            JSONObject json = ok();
            json.put("set", true);
            return json.toString();
        }
        ClipData clip = clipboard.getPrimaryClip();
        String text = "";
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence value = clip.getItemAt(0).coerceToText(context);
            text = value == null ? "" : value.toString();
        }
        JSONObject json = ok();
        json.put("text", text);
        json.put("note", "Android 10+ 只在应用可见时允许读取剪贴板，后台可能返回空");
        return json.toString();
    }

    private static String vibrate(Context context, Uri uri) throws Exception {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            throw new IllegalStateException("设备不支持振动");
        }
        String patternText = uri.getQueryParameter("pattern");
        if (patternText != null && !patternText.isEmpty()) {
            String[] parts = patternText.split(",");
            long[] pattern = new long[parts.length];
            for (int index = 0; index < parts.length; index++) {
                try {
                    pattern[index] = Long.parseLong(parts[index].trim());
                } catch (NumberFormatException ignored) {
                    pattern[index] = 0;
                }
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
            JSONObject json = ok();
            json.put("pattern", patternText);
            return json.toString();
        }
        int ms = intParam(uri, "ms", 300);
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(ms);
        }
        JSONObject json = ok();
        json.put("ms", ms);
        return json.toString();
    }

    private static int intParam(Uri uri, String name, int fallback) {
        String value = uri.getQueryParameter(name);
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static long longParam(Uri uri, String name, long fallback) {
        String value = uri.getQueryParameter(name);
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static boolean boolParam(Uri uri, String name, boolean fallback) {
        String value = uri.getQueryParameter(name);
        if (value == null || value.isEmpty()) return fallback;
        return Boolean.parseBoolean(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Whether the notification listener is enabled in system settings. */
    public static boolean notificationAccessEnabled(Context context) {
        return NotificationAccessService.isEnabled(context)
                || NotificationAccessService.isConnected();
    }

    /** Settings screen for notification access. */
    public static void openNotificationAccess(Context context) {
        try {
            Intent intent = new Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable error) {
            context.startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** Settings screen for "modify system settings" (brightness control). */
    public static void openWriteSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable error) {
            context.startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** Compact status line for the settings panel. */
    public static JSONObject permissionJson(Context context) throws Exception {
        JSONObject json = new JSONObject();
        json.put("accessibility", RunMode.isAccessibilityEnabled(context));
        json.put("notification", notificationAccessEnabled(context));
        json.put("writeSettings", Settings.System.canWrite(context));
        return json;
    }
}
