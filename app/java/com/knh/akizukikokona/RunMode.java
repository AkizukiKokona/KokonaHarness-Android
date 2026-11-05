package com.knh.akizukikokona;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Run-mode capability detection and persistence.
 *
 * <p>The user picks one of three modes in Settings:
 * <ul>
 *   <li>{@code normal} — plain app UID, no extra privileges.</li>
 *   <li>{@code accessibility} — plain engine plus the accessibility service, which can
 *       take system screenshots (Android 11+) and drive the UI.</li>
 *   <li>{@code advanced} — prefers a root shell (Magisk) and falls back to Shizuku for
 *       privileged commands. With neither available it still runs in normal mode.</li>
 * </ul>
 */
public final class RunMode {
    public static final String NORMAL = "normal";
    public static final String ACCESSIBILITY = "accessibility";
    public static final String ADVANCED = "advanced";

    private static final String PREFS = "kokona_runmode";
    private static final String KEY_MODE = "mode";
    private static final String KEY_ROOT_GRANTED = "root_granted";
    private static final String KEY_ROOT_PATH = "root_path";
    private static final String KEY_ROOT_PROMPTED = "root_prompted";

    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/magisk/su", "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
    };

    private RunMode() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String mode(Context context) {
        return prefs(context).getString(KEY_MODE, NORMAL);
    }

    public static void setMode(Context context, String mode) {
        if (!NORMAL.equals(mode) && !ACCESSIBILITY.equals(mode) && !ADVANCED.equals(mode)) return;
        prefs(context).edit().putString(KEY_MODE, mode).apply();
    }

    /** The channel the engine will actually use: root, shizuku, or plain. */
    public static String effectiveChannel(Context context) {
        if (!ADVANCED.equals(mode(context))) return "normal";
        if (isRootGranted(context)) return "root";
        if (isShizukuGranted()) return "shizuku";
        return "normal";
    }

    public static boolean isRootGranted(Context context) {
        return prefs(context).getBoolean(KEY_ROOT_GRANTED, false);
    }

    /** Path to the su binary that was found, or null. */
    public static String suPath(Context context) {
        String stored = prefs(context).getString(KEY_ROOT_PATH, null);
        if (stored != null && new File(stored).isFile()) return stored;
        for (String path : SU_PATHS) {
            if (new File(path).isFile()) return path;
        }
        return null;
    }

    public static boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    public static boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable error) {
            return false;
        }
    }

    public static boolean isShizukuGranted() {
        try {
            if (!Shizuku.pingBinder()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            return false;
        }
    }

    public static boolean isAccessibilityEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            ComponentName component = new ComponentName(
                    context, ScreenshotAccessibilityService.class);
            String flat = component.flattenToString();
            for (String item : enabled.split(":")) {
                if (item.equalsIgnoreCase(flat)) return true;
            }
            return enabled.contains(context.getPackageName() + "/"
                    + ScreenshotAccessibilityService.class.getName());
        } catch (Throwable error) {
            return false;
        }
    }

    /** False when the kernel cannot host the Landlock sandbox used by workspace-write. */
    public static boolean sandboxSupported(Context context) {
        return EngineService.sandboxSupported(context);
    }

    /** JSON snapshot polled by the Settings row. */
    public static String infoJson(Context context) {
        JSONObject json = new JSONObject();
        try {
            json.put("mode", mode(context));
            json.put("effective", effectiveChannel(context));
            json.put("suFound", suPath(context) != null);
            json.put("rootGranted", isRootGranted(context));
            json.put("shizukuInstalled", isShizukuInstalled(context));
            json.put("shizukuRunning", isShizukuRunning());
            json.put("shizukuGranted", isShizukuGranted());
            json.put("accessibility", isAccessibilityEnabled(context));
            json.put("notification", AndroidCapabilities.notificationAccessEnabled(context));
            json.put("writeSettings", android.provider.Settings.System.canWrite(context));
            json.put("sandbox", sandboxSupported(context));
            json.put("sdk", Build.VERSION.SDK_INT);
        } catch (Exception ignored) {
        }
        return json.toString();
    }

    /**
     * First launch: if a su binary exists, ask the manager once (Magisk shows its
     * own prompt); other su implementations are discovered the same way. Also
     * lazily pick "advanced" when a privileged channel is already usable.
     */
    public static void firstLaunch(final Context context) {
        SharedPreferences state = prefs(context);
        if (state.getBoolean(KEY_ROOT_PROMPTED, false)) return;
        state.edit().putBoolean(KEY_ROOT_PROMPTED, true).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                probeRoot(context, true);
            }
        }, "kokona-root-probe").start();
    }

    /** Ask for root (call off the UI thread). Returns whether uid 0 was obtained. */
    public static boolean probeRoot(Context context, boolean mayPrompt) {
        String path = suPath(context);
        if (path == null) {
            prefs(context).edit().putBoolean(KEY_ROOT_GRANTED, false).apply();
            return false;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(path, "-c", "id").redirectErrorStream(true).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroy();
                prefs(context).edit().putBoolean(KEY_ROOT_GRANTED, false).apply();
                return false;
            }
            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
            boolean granted = process.exitValue() == 0
                    && output.toString().contains("uid=0");
            prefs(context).edit()
                    .putBoolean(KEY_ROOT_GRANTED, granted)
                    .putString(KEY_ROOT_PATH, path)
                    .apply();
            return granted;
        } catch (Exception error) {
            prefs(context).edit().putBoolean(KEY_ROOT_GRANTED, false).apply();
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Trigger the Shizuku permission dialog. Must run on the main thread. */
    public static void requestShizukuPermission(Context context) {
        if (!isShizukuInstalled(context)) {
            ToastCompat.show(context, "未安装 Shizuku");
            return;
        }
        if (!isShizukuRunning()) {
            ToastCompat.show(context, "请先启动 Shizuku，再回来授权");
            openShizuku(context);
            return;
        }
        try {
            if (isShizukuGranted()) {
                ToastCompat.show(context, "Shizuku 已授权");
                return;
            }
            Shizuku.requestPermission(0);
        } catch (Throwable error) {
            ToastCompat.show(context, "无法申请 Shizuku 权限：" + error.getMessage());
        }
    }

    public static void openShizuku(Context context) {
        try {
            android.content.Intent intent = context.getPackageManager()
                    .getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (intent == null) throw new Exception("no launch intent");
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable error) {
            ToastCompat.show(context, "未安装 Shizuku");
        }
    }

    public static void openAccessibilitySettings(Context context) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable error) {
            ToastCompat.show(context, "无法打开无障碍设置");
        }
    }

    /** Runs a command through Shizuku as the shell user; null when unavailable. */
    public static String execAsShell(String command) {
        if (!isShizukuGranted()) return null;
        try {
            moe.shizuku.server.IShizukuService service =
                    moe.shizuku.server.IShizukuService.Stub.asInterface(
                            new rikka.shizuku.ShizukuBinderWrapper(Shizuku.getBinder()));
            String[] args = {"/system/bin/sh", "-c", command};
            moe.shizuku.server.IRemoteProcess remote =
                    service.newProcess(args, shellEnvironment(), "/");
            java.io.InputStream out = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    remote.getInputStream());
            java.io.InputStream err = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    remote.getErrorStream());
            StringBuilder text = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(out, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(err, "UTF-8"));
            while ((line = errorReader.readLine()) != null) text.append(line).append('\n');
            remote.waitFor();
            return text.toString();
        } catch (Throwable error) {
            return null;
        }
    }

    private static String[] shellEnvironment() {
        String path = "/system/bin:/system/xbin:/product/bin:/vendor/bin";
        return new String[]{
                "PATH=" + path,
                "HOME=/data/local/tmp",
                "TMPDIR=/data/local/tmp",
                "ANDROID_DATA=/data",
                "ANDROID_ROOT=/system"
        };
    }
}
