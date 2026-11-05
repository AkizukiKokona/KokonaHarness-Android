package com.knh.akizukikokona;

import android.app.Notification;
import android.content.ComponentName;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Notification access used by the KNHPlus tools: reading the shade and
 * dismissing notifications is something a desktop agent cannot do for Android.
 */
public class NotificationAccessService extends NotificationListenerService {
    private static volatile NotificationAccessService instance;

    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean isEnabled(android.content.Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            if (TextUtils.isEmpty(enabled)) return false;
            return enabled.contains(new ComponentName(context, NotificationAccessService.class)
                    .flattenToString());
        } catch (Throwable error) {
            return false;
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
        super.onListenerDisconnected();
    }

    /** Active notifications as JSON, newest first. */
    public static String listJson() throws Exception {
        NotificationAccessService service = instance;
        if (service == null) {
            throw new IllegalStateException("通知使用权未开启，请在 环境高级设置 中开启");
        }
        StatusBarNotification[] active = service.getActiveNotifications();
        if (active == null) active = new StatusBarNotification[0];
        List<StatusBarNotification> sorted = new ArrayList<>();
        Collections.addAll(sorted, active);
        Collections.sort(sorted, new Comparator<StatusBarNotification>() {
            @Override
            public int compare(StatusBarNotification a, StatusBarNotification b) {
                return Long.compare(b.getPostTime(), a.getPostTime());
            }
        });
        JSONArray array = new JSONArray();
        for (StatusBarNotification item : sorted) {
            JSONObject json = new JSONObject();
            json.put("key", item.getKey());
            json.put("package", item.getPackageName());
            json.put("time", item.getPostTime());
            Notification notification = item.getNotification();
            CharSequence title = notification.extras.getCharSequence("android.title");
            CharSequence text = notification.extras.getCharSequence("android.text");
            CharSequence bigText = notification.extras.getCharSequence("android.bigText");
            CharSequence subText = notification.extras.getCharSequence("android.subText");
            json.put("title", title == null ? "" : title.toString());
            json.put("text", text == null ? "" : text.toString());
            if (bigText != null) json.put("bigText", bigText.toString());
            if (subText != null) json.put("subText", subText.toString());
            if (notification.category != null) json.put("category", notification.category);
            json.put("ongoing", item.isOngoing());
            json.put("clearable", item.isClearable());
            json.put("id", item.getId());
            JSONArray actions = new JSONArray();
            if (notification.actions != null) {
                for (int index = 0; index < notification.actions.length; index++) {
                    Notification.Action action = notification.actions[index];
                    CharSequence label = action.title;
                    JSONObject entry = new JSONObject();
                    entry.put("index", index);
                    entry.put("title", label == null ? "" : label.toString());
                    entry.put("available", action.actionIntent != null);
                    actions.put(entry);
                }
            }
            json.put("actions", actions);
            array.put(json);
        }
        JSONObject root = new JSONObject();
        root.put("count", array.length());
        root.put("notifications", array);
        return root.toString();
    }

    /** Send one notification action button (reply, mark read, archive...). */
    public static String action(String key, int index) throws Exception {
        NotificationAccessService service = instance;
        if (service == null) throw new IllegalStateException("通知使用权未开启");
        StatusBarNotification item = findByKey(service, key);
        if (item == null) throw new IllegalStateException("通知已消失：" + key);
        Notification.Action[] actions = item.getNotification().actions;
        if (actions == null || actions.length == 0) {
            throw new IllegalStateException("这条通知没有可点击按钮");
        }
        if (index < 0 || index >= actions.length) {
            throw new IllegalArgumentException("按钮索引无效，可选 0-" + (actions.length - 1));
        }
        Notification.Action action = actions[index];
        if (action.actionIntent == null) throw new IllegalStateException("该按钮不可执行");
        action.actionIntent.send();
        return action.title == null ? "" : action.title.toString();
    }

    public static boolean dismiss(String key) {
        NotificationAccessService service = instance;
        if (service == null) return false;
        try {
            service.cancelNotification(key);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static StatusBarNotification findByKey(NotificationAccessService service, String key) {
        StatusBarNotification[] active = service.getActiveNotifications();
        if (active == null) return null;
        for (StatusBarNotification item : active) {
            if (item.getKey().equals(key)) return item;
        }
        return null;
    }

    public static int dismissAll() {
        NotificationAccessService service = instance;
        if (service == null) return 0;
        StatusBarNotification[] active = service.getActiveNotifications();
        if (active == null) return 0;
        int count = 0;
        for (StatusBarNotification item : active) {
            if (item.isOngoing()) continue;
            try {
                service.cancelNotification(item.getKey());
                count++;
            } catch (Throwable ignored) {
            }
        }
        return count;
    }
}
