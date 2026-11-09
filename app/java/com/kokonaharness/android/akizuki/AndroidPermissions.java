package com.kokonaharness.android.akizuki;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Capability-level grants for the KNHPlus phone tools.
 *
 * <p>The app only provides the mechanism (allow / ask / deny with an audit
 * trail); the user decides which capabilities the model may use. Nothing here
 * ships an opinionated app blocklist or keyword-based risk scoring.</p>
 */
public final class AndroidPermissions {
    public static final String SCREEN = "screen";
    public static final String SCREENSHOT = "screenshot";
    public static final String INPUT = "input";
    public static final String SYSTEM = "system";
    public static final String NOTIFICATIONS_READ = "notifications_read";
    public static final String NOTIFICATIONS_ACT = "notifications_act";
    public static final String MEDIA = "media";
    public static final String CLIPBOARD = "clipboard";
    public static final String FEEDBACK = "feedback";

    public static final String ALLOW = "allow";
    public static final String ASK = "ask";
    public static final String DENY = "deny";

    private static final String PREFS = "kokona_permissions";

    private static final String[] CAPABILITIES = {
            SCREEN, SCREENSHOT, INPUT, SYSTEM, NOTIFICATIONS_READ,
            NOTIFICATIONS_ACT, MEDIA, CLIPBOARD, FEEDBACK
    };
    private static final String[] DEFAULTS = {
            ALLOW, ALLOW, ASK, ASK, ALLOW,
            ASK, ASK, ASK, ALLOW
    };

    private AndroidPermissions() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int indexOf(String capability) {
        for (int i = 0; i < CAPABILITIES.length; i++) {
            if (CAPABILITIES[i].equals(capability)) return i;
        }
        return -1;
    }

    private static boolean validMode(String mode) {
        return ALLOW.equals(mode) || ASK.equals(mode) || DENY.equals(mode);
    }

    public static boolean isCapability(String capability) {
        return indexOf(capability) >= 0;
    }

    /** User-facing Chinese label used by native confirmation dialogs. */
    public static String label(String capability) {
        if (SCREEN.equals(capability)) return "读取屏幕";
        if (SCREENSHOT.equals(capability)) return "截图";
        if (INPUT.equals(capability)) return "点击与输入";
        if (SYSTEM.equals(capability)) return "打开应用与系统操作";
        if (NOTIFICATIONS_READ.equals(capability)) return "读取通知";
        if (NOTIFICATIONS_ACT.equals(capability)) return "通知操作";
        if (MEDIA.equals(capability)) return "媒体与音量亮度";
        if (CLIPBOARD.equals(capability)) return "剪贴板";
        if (FEEDBACK.equals(capability)) return "语音与振动";
        return capability;
    }

    public static String mode(Context context, String capability) {
        int index = indexOf(capability);
        if (index < 0) return DENY;
        String saved = prefs(context).getString(capability, DEFAULTS[index]);
        return validMode(saved) ? saved : DEFAULTS[index];
    }

    public static boolean setMode(Context context, String capability, String mode) {
        if (indexOf(capability) < 0 || !validMode(mode)) return false;
        prefs(context).edit().putString(capability, mode).apply();
        return true;
    }

    public static void reset(Context context) {
        prefs(context).edit().clear().apply();
    }

    /** JSON consumed by the Settings permissions page and the MCP bridge. */
    public static String json(Context context) {
        JSONObject root = new JSONObject();
        JSONObject permissions = new JSONObject();
        try {
            for (String capability : CAPABILITIES) {
                permissions.put(capability, mode(context, capability));
            }
            root.put("ok", true);
            root.put("permissions", permissions);
        } catch (Exception ignored) {
        }
        return root.toString();
    }
}
