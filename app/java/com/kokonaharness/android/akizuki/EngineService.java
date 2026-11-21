package com.kokonaharness.android.akizuki;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the node/dsh engine: installs the bundled runtime once, launches the
 * Web server on a free loopback port, streams its URL to the UI, and keeps the
 * process alive as a foreground service.
 */
public class EngineService extends Service {
    public enum State { INSTALLING, STARTING, READY, ERROR, STOPPED }

    public static final class Snapshot {
        public final State state;
        public final String url;
        public final String message;
        public final float progress;

        Snapshot(State state, String url, String message, float progress) {
            this.state = state;
            this.url = url;
            this.message = message;
            this.progress = progress;
        }
    }

    public interface Listener {
        void onState(Snapshot snapshot);
    }

    private static final String TAG = "KokonaEngine";
    private static final String CHANNEL_ID = "engine";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP = "com.kokonaharness.android.akizuki.STOP";
    private static final String SIDEBAR_BUNDLE = "dsh-better-sidebar";
    private static final String SETTHINGS_BUNDLE = "kha-setthings";
    private static final String[] SETTHINGS_ASSETS = {
            "package.json", "cordis.patch.yml", "lib/index.js", "lib/client.js"
    };
    /**
     * Names earlier builds shipped for the same settings bundle. The capitalised
     * one reached devices as a real directory plus a profile link and a manifest
     * row, so it has to be scrubbed from all three places, not just node_modules:
     * a manifest row whose package is gone makes the Host skip the bundle, and
     * the plugin list then shows a permanently broken entry.
     */
    private static final String[] LEGACY_SETTHINGS = {
            "KHA-Setthings", "khakhakha-settings", "kha-settings"
    };
    /**
     * Where the engine announces its own URL. Two shapes are accepted because
     * the launcher owns neither: dsh 0.2.2-rc.5 still prints `dsh web: <url>`
     * with a process token query, but an older build printed the bare URL and
     * a future one may spell the token differently. The first pattern anchors
     * on the printed prefix, so an unrelated URL in a warning cannot make the
     * engine look ready; the second keeps the exact old form working.
     */
    private static final Pattern[] URL_PATTERNS = {
            Pattern.compile("dsh web:\\s*(https?://127\\.0\\.0\\.1:\\d+\\S*)"),
            Pattern.compile("(https?://127\\.0\\.0\\.1:\\d+/?\\?token=\\S+)"),
    };

    // --- safe mode -----------------------------------------------------------
    // A user-installed plugin can still kill the process in ways dsh's optional
    // entry policy cannot catch: a synchronous callback that throws is treated
    // as fatal for the whole app (dsh 0.2.2-rc.5 failure matrix). When that
    // happens twice in a row the next boot only loads the bundles below, so the
    // market and the KHA settings panel stay reachable and the user can remove
    // the offending plugin instead of losing the whole install.
    private static final String PREFS = "kha";
    private static final String KEY_FAIL_STREAK = "engine_fail_streak";
    private static final String KEY_SAFE_MODE = "safe_mode_active";
    private static final String KEY_SAFE_MODE_REASON = "safe_mode_reason";
    private static final String KEY_SAFE_MODE_NOTICE = "safe_mode_notice_shown";
    private static final String BACKUP_SUFFIX = ".safemode";
    private static final String KEY_LAST_RESET_BACKUP = "dsh_last_reset_backup";
    /** Two consecutive failed boots; one is often a transient OOM on a small device. */
    private static final int SAFE_MODE_THRESHOLD = 2;
    /**
     * Minimal set: the two bundles resolved from the dsh installation, the
     * market (the control surface the user needs), and KHA's own settings
     * panel. Sidebar/slider and every user bundle stay out.
     */
    private static final String[] SAFE_MODE_BUNDLES = {
            "@deepseek-ai/dsh-base",
            "@deepseek-ai/dsh-web-app",
            "dshmarket",
            SETTHINGS_BUNDLE,
    };
    private static final String SAFE_MODE_PATCH =
            "- id: otel\n  disabled: true\n- id: session-telemetry-otel\n  disabled: true\n";

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Object PUBLISH_LOCK = new Object();
    private static volatile Snapshot latest =
            new Snapshot(State.STOPPED, null, null, -1f);
    private static volatile EngineService instance;

    private volatile boolean stopping;
    private Process process;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        Intent intent = new Intent(context, EngineService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void restart(Context context) {
        Intent intent = new Intent(context, EngineService.class).setAction("RESTART");
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static Snapshot current() {
        return latest;
    }

    /** True while the last boot ran with user-installed bundles filtered out. */
    public static boolean isSafeMode(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_SAFE_MODE, false);
    }

    /** Why safe mode turned itself on, for the banner and the boot report. */
    public static String safeModeReason(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_SAFE_MODE_REASON, "");
    }

    /** Whether the one-time safe-mode explanation has already been shown. */
    public static boolean shouldExplainSafeMode(Context context) {
        return isSafeMode(context) && !context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_SAFE_MODE_NOTICE, false);
    }

    public static void markSafeModeExplained(Context context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_SAFE_MODE_NOTICE, true).apply();
    }

    /**
     * Leave safe mode: restore the bundle list captured on entry, minus any
     * plugin the user uninstalled while safe mode was active, and put the two
     * patch layers back. The caller restarts the engine afterwards.
     */
    public static boolean exitSafeMode(Context context) {
        File dshHome = new File(new File(context.getFilesDir(), "home"), ".dsh");
        File profile = new File(dshHome, "profiles/" + PROFILE_NAME);
        File manifest = new File(profile, "package.json");
        File backup = new File(profile, "package.json" + BACKUP_SUFFIX);
        boolean restored = !backup.isFile();
        if (backup.isFile() && manifest.isFile()) {
            try {
                org.json.JSONObject saved = new org.json.JSONObject(readText(backup));
                org.json.JSONObject current = new org.json.JSONObject(readText(manifest));
                org.json.JSONObject deps = current.optJSONObject("dependencies");
                org.json.JSONArray savedBundles = saved.getJSONObject("dsh")
                        .getJSONObject("profile").getJSONArray("bundles");
                org.json.JSONArray keep = new org.json.JSONArray();
                for (int i = 0; i < savedBundles.length(); i++) {
                    String name = savedBundles.getString(i);
                    // Keep the bundle when it ships with the app or is still
                    // installed. A bundle whose dependency is gone would show up
                    // as a permanently broken row in the plugin list.
                    if (isBuiltinBundle(name) || (deps != null && deps.has(name))) {
                        keep.put(name);
                    }
                }
                org.json.JSONObject dsh = current.optJSONObject("dsh");
                if (dsh == null) {
                    dsh = new org.json.JSONObject();
                    current.put("dsh", dsh);
                }
                org.json.JSONObject profileObject = dsh.optJSONObject("profile");
                if (profileObject == null) {
                    profileObject = new org.json.JSONObject();
                    dsh.put("profile", profileObject);
                }
                profileObject.put("bundles", keep);
                writeText(manifest, current.toString(2) + "\n");
                backup.delete();
                restored = true;
            } catch (Exception error) {
                // Keep the flag and the backup: a half-restored profile would
                // look like a normal install while still hiding every plugin.
                Log.w(TAG, "cannot restore the pre-safe-mode bundle list", error);
            }
        }
        if (!restored) return false;
        restorePatch(new File(profile, "cordis.patch.yml"));
        restorePatch(new File(dshHome, "cordis.patch.yml"));
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_SAFE_MODE, false)
                .putBoolean(KEY_SAFE_MODE_NOTICE, false)
                .putString(KEY_SAFE_MODE_REASON, "")
                .putInt(KEY_FAIL_STREAK, 0)
                .apply();
        Log.i(TAG, "safe mode left by user request");
        return true;
    }

    /**
     * Bundles that exist outside the profile's dependency list: the two dsh
     * bundles resolve from the installation, and kha-setthings is mounted by
     * link rather than by dependency. Without them the restore would drop the
     * KHA settings panel every time safe mode is left.
     */
    private static boolean isBuiltinBundle(String name) {
        return "@deepseek-ai/dsh-base".equals(name)
                || "@deepseek-ai/dsh-web-app".equals(name)
                || SETTHINGS_BUNDLE.equals(name);
    }

    /** Returns the tail of engine.log for the in-app log viewer. */
    public static String readLogTail(Context context, int maxChars) {
        File file = new File(new File(context.getFilesDir(), "logs"), "engine.log");
        if (!file.isFile()) return "";
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            try {
                long length = raf.length();
                long start = Math.max(0, length - Math.max(maxChars, 1024) * 4L);
                raf.seek(start);
                byte[] data = new byte[(int) (length - start)];
                raf.readFully(data);
                String text = new String(data, StandardCharsets.UTF_8);
                if (text.length() > maxChars) {
                    text = text.substring(text.length() - maxChars);
                }
                return text;
            } finally {
                raf.close();
            }
        } catch (Exception error) {
            return "";
        }
    }

    public static void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
        listener.onState(latest);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void publish(State state, String url, String message, float progress) {
        Snapshot snapshot = new Snapshot(state, url, message, progress);
        synchronized (PUBLISH_LOCK) {
            latest = snapshot;
            for (Listener listener : listeners) {
                try {
                    listener.onState(snapshot);
                } catch (Throwable ignored) {
                }
            }
        }
        EngineService service = instance;
        if (service != null) {
            service.updateNotification(snapshot);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification(latest));
        LocalBridgeServer.start(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopping = true;
            stopEngine();
            publish(State.STOPPED, null, null, -1f);
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("RESTART".equals(action)) {
            stopping = true;
            stopEngine();
            final Thread previous = worker;
            worker = null;
            Thread next = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (previous != null && previous.isAlive()) {
                        try {
                            previous.join(5000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    runEngine();
                }
            }, "kokona-engine");
            worker = next;
            next.start();
            return START_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runEngine();
                }
            }, "kokona-engine");
            worker.start();
        }
        return START_STICKY;
    }

    private void runEngine() {
        stopping = false;
        acquireWakeLock();
        String step = "install";
        try {
            publish(State.INSTALLING, null, getString(R.string.engine_installing), -1f);
            RuntimeInstaller.install(this, new RuntimeInstaller.Progress() {
                @Override
                public void onProgress(float fraction, String message) {
                    publish(State.INSTALLING, null, message, fraction);
                }
            });
            step = "runtime-patch";
            ensureRuntimePatch();
            step = "official-settings";
            restoreOfficialSettings();
            step = "setthings-bundle";
            installSetthingsBundle();
            step = "seed-profile";
            seedProfile();
            if (stopping) return;
            step = "safe-mode";
            maybeActivateSafeMode();
            if (stopping) return;
            step = "launch";
            publish(State.STARTING, null, "正在启动引擎…", -1f);
            launchProcess();
        } catch (final Throwable error) {
            Log.e(TAG, "engine failed at " + step, error);
            if (!stopping) {
                publish(State.ERROR, null, describe(step, error), -1f);
            }
        } finally {
            releaseWakeLock();
        }
    }

    private static final String PROFILE_NAME = "kokona";

    /**
     * The bundled node-addon-require-builtin has no Android binding; copy the JS
     * fallback into the installed runtime on every start. This keeps the runtime
     * version marker unchanged for bug-fix releases, so an upgrade does not
     * re-extract the 600 MB snapshot just to change one file.
     */
    private void ensureRuntimePatch() {
        File target = new File(RuntimeInstaller.runtimeDir(this),
                "lib/node_modules/node-addon-require-builtin/lib/index.js");
        try {
            if (target.isFile() && readText(target).contains("android-js-fallback")) return;
            InputStream input = getAssets().open("seed/node-addon-require-builtin-android.js");
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                input.close();
                return;
            }
            FileOutputStream output = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            } finally {
                input.close();
                output.close();
            }
        } catch (Exception error) {
            Log.w(TAG, "require-builtin fallback patch failed", error);
        }
    }

    /**
     * Node 26's native package.json reader fails non-deterministically with
     * ERR_INVALID_PACKAGE_CONFIG on some Android builds. Ship a tiny preload
     * shim (loaded via --require) that keeps the native fast path and falls
     * back to a plain JS JSON.parse only when that specific error is thrown.
     */
    private File ensureNodePatch() throws IOException {
        File patch = new File(getFilesDir(), "kha-node-patch.cjs");
        InputStream input = getAssets().open("kha-node-patch.cjs");
        try {
            FileOutputStream output = new FileOutputStream(patch);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
        return patch;
    }

    // `kha-setthings` is mounted through the profile's node_modules link and its
    // `dsh.profile.bundles` row, never through `dependencies`: it ships inside the
    // runtime snapshot and has no registry entry, so a versioned dependency here
    // would make the next pnpm install abort the whole profile.
    private static final String PROFILE_PACKAGE_JSON =
            "{\n  \"name\": \"dsh-profile-kokona\",\n  \"private\": true,\n"
            + "  \"dependencies\": { \"dshmarket\": \"1.74.0\","
            + " \"dsh-thinking-slider\": \"0.4.0\","
            + " \"dsh-better-sidebar\": \"0.32.1\" },\n"
            + "  \"dsh\": { \"profile\": { \"bundles\": [\"@deepseek-ai/dsh-base\","
            + " \"@deepseek-ai/dsh-web-app\", \"dshmarket\","
            + " \"dsh-thinking-slider\", \"dsh-better-sidebar\", \"kha-setthings\"] } }\n}\n";

    private static final String PROFILE_PNPM_WORKSPACE =
            "packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n";

    /**
     * dsh regenerates everything under {@code ~/.dsh} on every boot, so the
     * cheapest way out of an unwritable state tree is to move the whole thing
     * aside. Renaming only needs write access on the parent directory, which
     * the app always has, whereas deleting root-owned children does not work
     * and leaves dsh to fail with EACCES (errno -13) when it tries to write.
     *
     * The plugin manager writes far more than cordis.yml: installing or
     * uninstalling a bundle edits profile/package.json, the node_modules
     * links, the workspace/patch files and the market cache. A single
     * root-owned node anywhere in that set makes "remove plugin" fail with
     * EACCES on a device without root, so check every one of them.
     */
    private void resetDshStateIfUnwritable(File dshHome) {
        if (!dshHome.exists()) return;
        File profiles = new File(dshHome, "profiles");
        File profile = new File(profiles, PROFILE_NAME);
        File modules = new File(profile, "node_modules");
        File storages = new File(dshHome, "storages");
        File marketInDsh = new File(dshHome, ".dsh-market");
        File marketInHome = new File(dshHome.getParentFile(), ".dsh-market");
        boolean healthy = canCreateIn(dshHome)
                && canCreateIn(profiles)
                && canCreateIn(profile)
                && canCreateIn(modules)
                && canCreateIn(storages)
                && writableIfExists(new File(profile, "package.json"))
                && writableIfExists(new File(profile, "pnpm-workspace.yaml"))
                && writableIfExists(new File(profile, "cordis.yml"))
                && writableIfExists(new File(profile, "cordis.patch.yml"))
                && writableDirIfExists(marketInDsh)
                && writableDirIfExists(marketInHome);
        if (healthy) return;

        Log.w(TAG, "dsh state is not writable; resetting to fix plugin management");
        File aside = new File(dshHome.getParentFile(),
                ".dsh.reset." + System.currentTimeMillis());
        if (dshHome.renameTo(aside)) {
            // NEVER delete this backup. It is the user's conversations, themes
            // and plugin settings. The old code deleted it "best effort", which
            // turned a recoverable permission problem into permanent data loss.
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_LAST_RESET_BACKUP, aside.getAbsolutePath()).apply();
            Log.w(TAG, "moved unwritable dsh state to " + aside.getAbsolutePath()
                    + " (kept as backup, NOT deleted)");
        } else {
            Log.w(TAG, "cannot move unwritable dsh state; continuing anyway");
        }
    }

    /** Path of the last automatic state backup, for the settings panel. */
    public static String lastResetBackup(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_LAST_RESET_BACKUP, "");
    }

    /** True when a file is absent or the app can write it. */
    private static boolean writableIfExists(File file) {
        return !file.exists() || file.canWrite();
    }

    /** The market cache is normally a directory; tolerate either layout. */
    private static boolean writableDirIfExists(File dir) {
        if (!dir.exists()) return true;
        if (dir.isDirectory()) return canCreateIn(dir);
        return dir.canWrite();
    }

    /** True when {@code dir} exists and a new file can be created inside it. */
    private static boolean canCreateIn(File dir) {
        if (!dir.isDirectory()) return true;
        File probe = new File(dir, ".write-probe");
        try {
            if (!probe.createNewFile()) return probe.canWrite();
            return true;
        } catch (IOException error) {
            return false;
        } finally {
            probe.delete();
        }
    }

    private static boolean deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        return file.delete();
    }

    private void seedProfile() throws IOException {
        // dsh's generated state can be left unwritable: a previous root launch,
        // a data migration, or a ROM that relabels app data all produce files
        // the app cannot write, and dsh then aborts with EACCES (errno -13).
        // Moving the whole tree aside is the only reliable fix, because it
        // needs write access on the parent only. Do this before creating the
        // profile directory, so a reset always leaves a clean tree behind.
        File dshHome = new File(new File(getFilesDir(), "home"), ".dsh");
        resetDshStateIfUnwritable(dshHome);

        File profile = new File(dshHome, "profiles/" + PROFILE_NAME);
        if (!profile.isDirectory() && !profile.mkdirs() && !profile.isDirectory()) {
            throw new IOException("无法创建 " + PROFILE_NAME + " profile");
        }

        // Seed only a brand-new profile. Once it exists, package.json, the
        // node_modules links and the patch file are the USER's to manage: dsh's
        // plugin manager installs and uninstalls bundles by editing exactly
        // these files. Re-adding the built-ins on every boot (the old
        // ensureBuiltinBundles behaviour) silently resurrected anything the
        // user had removed, which made uninstall appear to work but never
        // stick. The only exception is the one-time sidebar restore below:
        // it runs a single time to undo the safe-mode experiment, then never
        // touches the profile again.
        File manifest = new File(profile, "package.json");
        boolean fresh = !manifest.exists();
        if (fresh) writeText(manifest, PROFILE_PACKAGE_JSON);
        if (fresh) {
            ensurePackageLink(profile, "dshmarket");
            ensurePackageLink(profile, "dsh-thinking-slider");
            ensurePackageLink(profile, "dsh-better-sidebar");
            ensureMcpLink(profile);
        }
        // One-time restore of the sidebar bundle for profiles that lost it
        // during the safe-mode experiment. Runs once, then never again, so the
        // user's own install/uninstall decisions still stick.
        restoreSidebarOnce(profile, manifest);
        syncSetthings(profile, manifest);

        File workspaceFile = new File(profile, "pnpm-workspace.yaml");
        if (!workspaceFile.exists()) writeText(workspaceFile, PROFILE_PNPM_WORKSPACE);
        File patch = new File(profile, "cordis.patch.yml");
        if (!patch.exists()) {
            InputStream input = getAssets().open("seed/cordis.patch.yml");
            FileOutputStream output = new FileOutputStream(patch);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
            } finally {
                input.close();
                output.close();
            }
            ensureKnhPlusPatch(profile, RuntimeInstaller.runtimeDir(this));
        }
    }

    /**
     * Called after the profile exists and before launch. Two consecutive boots
     * that never reached READY mean the profile itself (almost always a plugin)
     * is preventing startup, so the next attempt loads only the safe bundles.
     * The user's bundle list is backed up first and restored verbatim on exit.
     */
    private void maybeActivateSafeMode() {
        if (isSafeMode(this)) return;
        int streak = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_FAIL_STREAK, 0);
        if (streak < SAFE_MODE_THRESHOLD) return;
        enterSafeMode("连续 " + streak + " 次启动未完成");
    }

    private void enterSafeMode(String reason) {
        File dshHome = new File(new File(getFilesDir(), "home"), ".dsh");
        File profile = new File(dshHome, "profiles/" + PROFILE_NAME);
        File manifest = new File(profile, "package.json");
        if (!manifest.isFile()) return;
        try {
            File backup = new File(profile, "package.json" + BACKUP_SUFFIX);
            if (!backup.isFile()) copyFile(manifest, backup);

            org.json.JSONObject root = new org.json.JSONObject(readText(manifest));
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            if (dsh == null) {
                dsh = new org.json.JSONObject();
                root.put("dsh", dsh);
            }
            org.json.JSONObject profileObject = dsh.optJSONObject("profile");
            if (profileObject == null) {
                profileObject = new org.json.JSONObject();
                dsh.put("profile", profileObject);
            }
            org.json.JSONArray bundles = new org.json.JSONArray();
            for (String name : SAFE_MODE_BUNDLES) bundles.put(name);
            // dependencies is deliberately left untouched: dshmarket lists the
            // installed plugins from it, so the user can still uninstall the
            // broken one while in safe mode.
            profileObject.put("bundles", bundles);
            writeText(manifest, root.toString(2) + "\n");

            // A broken patch is a second way to make every boot fail, and it is
            // invisible in the plugin list. Replace both layers with the seed
            // and keep the originals next to them.
            backupPatch(new File(profile, "cordis.patch.yml"));
            backupPatch(new File(dshHome, "cordis.patch.yml"));

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_SAFE_MODE, true)
                    .putString(KEY_SAFE_MODE_REASON, reason)
                    .apply();
            Log.w(TAG, "safe mode active: " + reason);
        } catch (Exception error) {
            // Never let the rescue path itself abort a boot: an unmodified
            // profile still has a chance to start on its own.
            Log.w(TAG, "cannot enable safe mode", error);
        }
    }

    /** Snapshot one patch layer and replace it with the known-good seed. */
    private void backupPatch(File patch) {
        try {
            if (!patch.isFile()) return;
            File backup = new File(patch.getParentFile(), patch.getName() + BACKUP_SUFFIX);
            if (!backup.isFile()) copyFile(patch, backup);
            writeText(patch, SAFE_MODE_PATCH);
        } catch (Exception error) {
            Log.w(TAG, "cannot reset " + patch.getName(), error);
        }
    }

    private static void restorePatch(File patch) {
        File backup = new File(patch.getParentFile(), patch.getName() + BACKUP_SUFFIX);
        if (!backup.isFile()) return;
        try {
            copyFile(backup, patch);
            backup.delete();
        } catch (Exception error) {
            Log.w(TAG, "cannot restore " + patch.getName(), error);
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        FileInputStream input = new FileInputStream(from);
        try {
            FileOutputStream output = new FileOutputStream(to);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
    }

    /**
     * A boot that never printed a URL. Only this counts: a process that became
     * READY and crashed later is a different failure (the UI has the log), and
     * counting it would silently switch healthy installs into safe mode.
     */
    private void recordBootFailure(String detail) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int streak = prefs.getInt(KEY_FAIL_STREAK, 0) + 1;
        prefs.edit().putInt(KEY_FAIL_STREAK, streak).apply();
        Log.w(TAG, "boot failure #" + streak + ": " + detail);
        // Arm safe mode right away instead of waiting for another full boot:
        // the user is looking at the error page, and the retry button should
        // already boot the filtered profile.
        if (streak >= SAFE_MODE_THRESHOLD && !isSafeMode(this)) {
            enterSafeMode("连续 " + streak + " 次启动未完成");
        }
    }

    private void recordBootSuccess() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_FAIL_STREAK, 0).apply();
    }

    /**
     * Safe mode for the broken settings overlay: when enabled, drop
     * dsh-better-sidebar from the profile so the user can A/B test the
     * interference without ever opening the (unusable) settings page.
     * Only removes — switching the flag off does not reinstall anything.
     */
    /**
     * One-time migration from the short-lived sidebar safe-mode experiment.
     * The sidebar was never the culprit behind the broken settings page, so
     * put it back once for profiles that lost it. Afterwards the profile is
     * the user's own: nothing resurrects a bundle they uninstall.
     */
    private void restoreSidebarOnce(File profile, File manifest) {
        android.content.SharedPreferences prefs =
                getSharedPreferences("kha", MODE_PRIVATE);
        if (prefs.getBoolean("sidebar_restored_v1", false)) return;
        prefs.edit().putBoolean("sidebar_restored_v1", true).apply();
        if (!manifest.isFile()) return;
        try {
            String raw = new String(java.nio.file.Files.readAllBytes(manifest.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(raw);
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps == null) {
                deps = new org.json.JSONObject();
                root.put("dependencies", deps);
            }
            deps.put(SIDEBAR_BUNDLE, "0.32.1");
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            if (dsh == null) {
                dsh = new org.json.JSONObject();
                root.put("dsh", dsh);
            }
            org.json.JSONObject dshProfile = dsh.optJSONObject("profile");
            if (dshProfile == null) {
                dshProfile = new org.json.JSONObject();
                dsh.put("profile", dshProfile);
            }
            org.json.JSONArray bundles = dshProfile.optJSONArray("bundles");
            if (bundles == null) bundles = new org.json.JSONArray();
            boolean present = false;
            for (int i = 0; i < bundles.length(); i++) {
                if (SIDEBAR_BUNDLE.equals(bundles.optString(i))) present = true;
            }
            if (!present) bundles.put(SIDEBAR_BUNDLE);
            dshProfile.put("bundles", bundles);
            writeText(manifest, root.toString(2) + "\n");
            Log.i(TAG, "restored " + SIDEBAR_BUNDLE + " to the default profile");
        } catch (Exception error) {
            Log.w(TAG, "cannot restore sidebar bundle", error);
        }
        ensurePackageLink(profile, SIDEBAR_BUNDLE);
    }

    /**
     * The bundled runtime snapshot still contains our old settings
     * customisation (extra sections, a terminal action and CSS overrides).
     * Those were the source of the broken settings page, so drop the pristine
     * upstream client.js onto the installed runtime on every boot. This keeps
     * the 100 MB snapshot untouched while still taking effect immediately on
     * existing installations, and stays idempotent by comparing bytes.
     */
    private void restoreOfficialSettings() {
        File target = new File(RuntimeInstaller.runtimeDir(this),
                "lib/node_modules/@deepseek-ai/dsh-client-ui-settings-general/lib/client.js");
        if (!target.isFile()) return;
        try {
            byte[] official = readAsset("official-settings-client.js");
            byte[] installed = java.nio.file.Files.readAllBytes(target.toPath());
            if (java.util.Arrays.equals(official, installed)) return;
            java.nio.file.Files.write(target.toPath(), official);
            Log.i(TAG, "official settings UI restored (" + official.length + " bytes)");
        } catch (Exception error) {
            Log.w(TAG, "cannot restore official settings UI", error);
        }
    }

    private byte[] readAsset(String name) throws IOException {
        InputStream input = getAssets().open(name);
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    /**
     * Deploy our own settings bundle into the installed runtime. A dsh bundle
     * is just a directory under lib/node_modules, so copying three small files
     * out of assets is enough: no npm install and no re-packing of the 100 MB
     * snapshot. A byte comparison keeps this idempotent.
     */
    private void installSetthingsBundle() {
        File runtime = RuntimeInstaller.runtimeDir(this);
        // Old builds shipped a capitalised package with no bundle declaration.
        // Leaving it under node_modules makes the plugin manager keep reporting
        // "这个包没有声明组合包" for that stale entry, so remove it every boot.
        for (String legacy : LEGACY_SETTHINGS) {
            removeTreeOrLink(new File(runtime, "lib/node_modules/" + legacy));
        }

        File dir = new File(runtime,
                "lib/node_modules/" + SETTHINGS_BUNDLE);
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return;
            for (String name : SETTHINGS_ASSETS) {
                File target = new File(dir, name);
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) continue;
                byte[] source = readAsset(SETTHINGS_BUNDLE + "/" + name);
                if (target.isFile() && java.util.Arrays.equals(source,
                        java.nio.file.Files.readAllBytes(target.toPath()))) continue;
                java.nio.file.Files.write(target.toPath(), source);
            }
        } catch (Exception error) {
            Log.w(TAG, "kha-setthings bundle install failed", error);
        }
    }

    /**
     * Keep the first-party settings bundle installed, and keep the legacy
     * names out of the profile for good.
     *
     * This deliberately runs on every boot instead of once. The old one-shot
     * marker was set BEFORE the manifest write, so a single failure (read-only
     * profile, interrupted upgrade) retired the migration forever and left the
     * settings tab missing; and because a stale row in `dsh.profile.bundles`
     * makes the Host skip the bundle while the plugin list keeps advertising
     * it, "the whole settings page did nothing" was the visible result. The
     * scrub is idempotent and only writes when the parsed manifest actually
     * changes, so re-running it costs nothing.
     */
    private void syncSetthings(File profile, File manifest) {
        // Old capitalised directory / link must go before anything resolves
        // `kha-setthings`, otherwise both names compete for the same row id.
        for (String legacy : LEGACY_SETTHINGS) {
            removeTreeOrLink(new File(new File(profile, "node_modules"), legacy));
        }
        pruneLegacyPatch(profile);
        if (!manifest.isFile()) {
            ensurePackageLink(profile, SETTHINGS_BUNDLE);
            return;
        }
        try {
            String raw = new String(java.nio.file.Files.readAllBytes(manifest.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(raw);
            boolean changed = false;

            // `kha-setthings` is a first-party package that ships inside the
            // runtime snapshot; it is not on any registry. Listing it under
            // dependencies therefore makes the next `pnpm install` (which the
            // plugin manager runs on add/remove) fail to resolve the version and
            // abort the whole profile, which uninstalls every other plugin with
            // it. The node_modules link below is what the Host actually reads.
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps != null) {
                for (String legacy : LEGACY_SETTHINGS) {
                    if (deps.has(legacy)) {
                        deps.remove(legacy);
                        changed = true;
                    }
                }
                if (deps.has(SETTHINGS_BUNDLE)) {
                    deps.remove(SETTHINGS_BUNDLE);
                    changed = true;
                }
                if (deps.length() == 0) {
                    root.remove("dependencies");
                    changed = true;
                } else {
                    root.put("dependencies", deps);
                }
            }

            org.json.JSONObject dsh = root.optJSONObject("dsh");
            if (dsh == null) {
                dsh = new org.json.JSONObject();
                root.put("dsh", dsh);
                changed = true;
            }
            org.json.JSONObject dshProfile = dsh.optJSONObject("profile");
            if (dshProfile == null) {
                dshProfile = new org.json.JSONObject();
                dsh.put("profile", dshProfile);
                changed = true;
            }
            org.json.JSONArray bundles = dshProfile.optJSONArray("bundles");
            if (bundles == null) bundles = new org.json.JSONArray();

            org.json.JSONArray kept = new org.json.JSONArray();
            boolean present = false;
            for (int i = 0; i < bundles.length(); i++) {
                String name = bundles.optString(i);
                if (isLegacySetthings(name)) {
                    changed = true;
                    continue;
                }
                // Drop an exact duplicate rather than mounting the row twice.
                if (SETTHINGS_BUNDLE.equals(name)) {
                    if (present) {
                        changed = true;
                        continue;
                    }
                    present = true;
                }
                kept.put(name);
            }
            if (!present) {
                kept.put(SETTHINGS_BUNDLE);
                changed = true;
            }
            if (changed) {
                dshProfile.put("bundles", kept);
                writeText(manifest, root.toString(2) + "\n");
                Log.i(TAG, "profile reconciled: legacy settings bundles removed, "
                        + SETTHINGS_BUNDLE + " mounted");
            }
        } catch (Exception error) {
            Log.w(TAG, "cannot reconcile kha-setthings bundle", error);
        }
        ensurePackageLink(profile, SETTHINGS_BUNDLE);
    }

    private static boolean isLegacySetthings(String name) {
        for (String legacy : LEGACY_SETTHINGS) {
            if (legacy.equals(name)) return true;
        }
        return false;
    }

    /**
     * Drop rows that still mount one of the retired package names. A patch row
     * pointing at a package that no longer exists fails the loader's import for
     * that entry, which is reported as a broken plugin instead of being ignored.
     */
    private void pruneLegacyPatch(File profile) {
        File patch = new File(profile, "cordis.patch.yml");
        if (!patch.isFile()) return;
        try {
            String raw = new String(java.nio.file.Files.readAllBytes(patch.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = raw.split("\n", -1);
            boolean dropped = false;
            java.util.ArrayList<String> kept = new java.util.ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                boolean hit = false;
                for (String legacy : LEGACY_SETTHINGS) {
                    if (trimmed.equals("- id: " + legacy) || trimmed.equals("id: " + legacy)
                            || trimmed.equals("- name: " + legacy)
                            || trimmed.equals("name: " + legacy)
                            || trimmed.equals("- name: '" + legacy + "'")
                            || trimmed.equals("name: '" + legacy + "'")
                            || trimmed.equals("- name: \"" + legacy + "\"")
                            || trimmed.equals("name: \"" + legacy + "\"")) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    dropped = true;
                    continue;
                }
                kept.add(line);
            }
            if (!dropped) return;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < kept.size(); i++) {
                String line = kept.get(i);
                // An `insert:` whose list just lost its only row would be an
                // empty sequence, which the loader rejects.
                if (line.trim().equals("- insert:") && !hasFollowingChild(kept, i)) continue;
                out.append(line);
                if (i < kept.size() - 1) out.append('\n');
            }
            writeText(patch, out.toString());
            Log.i(TAG, "legacy settings patch rows removed");
        } catch (Exception error) {
            Log.w(TAG, "cannot prune legacy patch rows", error);
        }
    }

    private static boolean hasFollowingChild(java.util.List<String> lines, int index) {
        String parent = lines.get(index);
        int parentIndent = parent.indexOf('-');
        for (int i = index + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty()) continue;
            int indent = line.indexOf(line.trim());
            if (indent <= parentIndent) return false;
            return true;
        }
        return false;
    }

    /** Remove a directory, a plain file, or a symlink (dangling ones included). */
    private static void removeTreeOrLink(File path) {
        try {
            if (java.nio.file.Files.isSymbolicLink(path.toPath())) {
                java.nio.file.Files.deleteIfExists(path.toPath());
                return;
            }
        } catch (Exception ignored) {
            // Fall through to the recursive path below.
        }
        if (path.isDirectory()) deleteTree(path);
        else if (path.exists()) path.delete();
    }

    /**
     * The profile imports plugin rows by package name, so the built-in MCP
     * client package must also be linkable from the profile directory.
     */
    private void ensureMcpLink(File profile) {
        try {
            File scoped = new File(profile, "node_modules/@deepseek-ai");
            File link = new File(scoped, "dsh-mcp-client");
            if (link.exists()) return;
            if (!scoped.isDirectory() && !scoped.mkdirs()) return;
            java.nio.file.Files.createSymbolicLink(link.toPath(),
                    new File(RuntimeInstaller.runtimeDir(this),
                            "lib/node_modules/@deepseek-ai/dsh-mcp-client").toPath());
        } catch (Exception ignored) {
        }
    }

    /**
     * Adds the KNHPlus MCP server row once. The profile patch stays user-editable;
     * we only append when the row is absent.
     */
    private void ensureKnhPlusPatch(File profile, File runtime) {
        try {
            File patch = new File(profile, "cordis.patch.yml");
            String text = patch.isFile() ? readText(patch) : "";
            if (text.contains("id: knhplus-mcp")) return;
            String node = new File(runtime, "bin/node").getAbsolutePath();
            String server = new File(runtime,
                    "lib/node_modules/knhplus-mcp/server.mjs").getAbsolutePath();
            String run = new File(getFilesDir(), "run").getAbsolutePath();
            StringBuilder block = new StringBuilder();
            if (!text.isEmpty() && !text.endsWith("\n")) block.append('\n');
            block.append("- insert:\n")
                    .append("    - id: knhplus-mcp\n")
                    .append("      name: '@deepseek-ai/dsh-mcp-client'\n")
                    .append("      config:\n")
                    .append("        transport: stdio\n")
                    .append("        serverName: knhplus\n")
                    .append("        command: \"").append(node.replace("\"", "")).append("\"\n")
                    .append("        args:\n")
                    .append("          - \"").append(server.replace("\"", "")).append("\"\n")
                    .append("        env:\n")
                    .append("          KOKONA_APP_RUN: \"").append(run.replace("\"", "")).append("\"\n");
            writeText(patch, text + block);
        } catch (Exception ignored) {
            // A bad patch must never block boot; dsh reports it on its own.
        }
    }

    private static String readText(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        } finally {
            input.close();
        }
    }

    private void ensurePackageLink(File profile, String name) {
        try {
            File modules = new File(profile, "node_modules");
            File link = new File(modules, name);
            File target = new File(RuntimeInstaller.runtimeDir(this),
                    "lib/node_modules/" + name);
            // A dangling symlink reports exists() == false, which used to send
            // the old code down the create path where it threw and was swallowed,
            // leaving the bundle unresolvable forever.
            if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
                try {
                    if (java.nio.file.Files.readSymbolicLink(link.toPath())
                            .equals(target.toPath())) return;
                } catch (Exception ignored) {
                    // Unreadable link: replace it below.
                }
                java.nio.file.Files.deleteIfExists(link.toPath());
            } else if (link.exists()) {
                return;
            }
            if (!modules.isDirectory() && !modules.mkdirs()) return;
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (Exception ignored) {
            // Filesystem without symlink support: the bundle degrades to a listed
            // but inactive entry rather than blocking the engine.
        }
    }

    /**
     * Android has no bubblewrap, and Landlock only exists in Linux 5.13+ (Android 9
     * kernels are 4.9/4.14). Keep the confined mode only where the kernel supports
     * Landlock and this device's ABI actually ships the launcher; otherwise the
     * engine runs inside the app UID sandbox with full file access.
     */
    private static String permissionMode(File runtime) {
        if (kernelSupportsLandlock() && landlockRunnerAvailable(runtime)) return "workspace-write";
        return "danger-full-access";
    }

    /** Whether the kernel can host the sandboxed workspace-write preset at all. */
    public static boolean sandboxSupported(Context context) {
        return kernelSupportsLandlock() && landlockRunnerAvailable(RuntimeInstaller.runtimeDir(context));
    }

    private static boolean kernelSupportsLandlock() {
        String release = System.getProperty("os.version");
        if (release == null) return false;
        String[] parts = release.split("\\.");
        try {
            int major = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
            int minor = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
            return major > 5 || (major == 5 && minor >= 13);
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean landlockRunnerAvailable(File runtime) {
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0];
        String arch;
        if (abi.equals("arm64-v8a")) arch = "arm64";
        else if (abi.equals("x86_64")) arch = "x64";
        else if (abi.equals("armeabi-v7a")) arch = "arm";
        else if (abi.equals("x86")) arch = "x86";
        else return false;
        File runner = new File(runtime, "lib/node_modules/@deepseek-ai/node-addon-system-android-"
                + arch + "/bin/landlock-run");
        return runner.isFile();
    }

    private static void writeText(File file, String text) throws IOException {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    private String describe(String step, Throwable error) {
        StringBuilder text = new StringBuilder("引擎启动失败（")
                .append(step).append("）");
        String message = error.getMessage();
        if (message == null || message.isEmpty()) message = error.toString();
        text.append('\n').append(error.getClass().getSimpleName())
                .append(": ").append(message);

        // EACCES/ENOENT on Android almost always means "this exact path",
        // so surface the failing file plus its owner and mode.
        Throwable cause = error.getCause();
        for (int depth = 0; cause != null && depth < 3; depth++) {
            text.append("\n↑ ").append(cause.getClass().getSimpleName())
                    .append(": ").append(cause.getMessage());
            cause = cause.getCause();
        }
        appendPathFacts(text, message);
        text.append("\n\n阶段: ").append(step)
                .append("\n数据目录可写: ").append(getFilesDir().canWrite())
                .append("\n设备: Android ").append(Build.VERSION.RELEASE)
                .append(" (sdk ").append(Build.VERSION.SDK_INT).append(')')
                .append("\nABI: ").append(Build.SUPPORTED_ABIS.length > 0
                        ? Build.SUPPORTED_ABIS[0] : "?");
        return text.toString();
    }

    private static void appendPathFacts(StringBuilder text, String message) {
        int slash = message == null ? -1 : message.indexOf("/data/");
        if (slash < 0) return;
        int end = slash;
        while (end < message.length() && !Character.isWhitespace(message.charAt(end))
                && message.charAt(end) != '\'' && message.charAt(end) != '"'
                && message.charAt(end) != ',') {
            end++;
        }
        File target = new File(message.substring(slash, end));
        text.append("\n路径: ").append(target.getAbsolutePath())
                .append("\n  存在=").append(target.exists())
                .append(" 可读=").append(target.canRead())
                .append(" 可写=").append(target.canWrite());
        File parent = target.getParentFile();
        if (parent != null) {
            text.append("\n  父目录=").append(parent.getAbsolutePath())
                    .append(" 可写=").append(parent.canWrite());
        }
    }

    /**
     * Android 10+ stopped publishing net.dns*, so the glibc launcher cannot read
     * resolvers from properties. Hand it the live list instead; kokona-grun
     * turns this file into resolv.conf before every run.
     */
    private void writeDnsServers(File runtime) {
        try {
            ConnectivityManager connectivity =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) return;
            Network active = connectivity.getActiveNetwork();
            if (active == null) return;
            LinkProperties properties = connectivity.getLinkProperties(active);
            if (properties == null) return;
            StringBuilder out = new StringBuilder();
            for (InetAddress address : properties.getDnsServers()) {
                String host = address.getHostAddress();
                // Link-local IPv6 resolvers need an interface scope that glibc's
                // resolver does not carry; skip them in favour of the rest.
                if (host == null || host.startsWith("fe80")) continue;
                out.append("nameserver ").append(host).append('\n');
            }
            if (out.length() == 0) return;
            File etc = new File(runtime, "kokona-glibc/etc");
            if (!etc.isDirectory() && !etc.mkdirs()) return;
            try (FileOutputStream stream = new FileOutputStream(new File(etc, "dns.servers"))) {
                stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "dns servers for glibc: " + out.toString().trim());
        } catch (Throwable error) {
            Log.w(TAG, "glibc dns list unavailable: " + error);
        }
    }

    private void launchProcess() throws IOException {
        final boolean startedSafe = isSafeMode(this);
        File runtime = RuntimeInstaller.runtimeDir(this);
        File home = new File(getFilesDir(), "home");
        File workspace = new File(getFilesDir(), "workspace");
        File tmp = new File(getCacheDir(), "tmp");
        File logs = new File(getFilesDir(), "logs");
        if (!home.exists() && !home.mkdirs()) throw new IOException("无法创建 HOME 目录");
        if (!workspace.exists() && !workspace.mkdirs()) {
            throw new IOException("无法创建工作区目录");
        }
        if (!tmp.exists() && !tmp.mkdirs()) throw new IOException("无法创建临时目录");
        if (!logs.exists() && !logs.mkdirs()) throw new IOException("无法创建日志目录");

        String runtimePath = runtime.getAbsolutePath();
        File node = new File(runtime, "bin/node");
        File bin = new File(runtime, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (!node.isFile()) throw new IOException("运行时缺少 node");
        if (!bin.isFile()) throw new IOException("运行时缺少 dsh");

        writeDnsServers(runtime);

        File nodePatch = ensureNodePatch();
        ProcessBuilder builder = new ProcessBuilder(
                node.getAbsolutePath(),
                "--expose-internals",
                "--require", nodePatch.getAbsolutePath(),
                bin.getAbsolutePath(),
                "--profile", PROFILE_NAME,
                "--host", "127.0.0.1",
                "--port", "0",
                "--no-open");
        builder.directory(workspace);
        builder.redirectErrorStream(true);

        Map<String, String> env = builder.environment();
        env.put("HOME", home.getAbsolutePath());
        env.put("DSH_HOME", new File(home, ".dsh").getAbsolutePath());
        env.put("DSH_PERMISSION_MODE", permissionMode(runtime));
        env.put("PREFIX", runtimePath);
        env.put("KOKONA_RUNTIME", runtimePath);
        env.put("TERMUX__PREFIX", runtimePath);
        env.put("TERMUX__ROOTFS", "/");
        // KOKONA_GLIBC pins the prefix for scripts run through a shell that did
        // not inherit this environment; the launchers themselves also resolve it
        // from their own location, so this is a shortcut, not the only source.
        env.put("KOKONA_GLIBC", runtimePath + "/kokona-glibc");
        // Tells the runtime's directory opener that it is running on Android,
        // not desktop Linux: Node reports process.platform === "linux" on
        // both, so platform alone cannot pick the system file manager.
        env.put("KOKONA_ANDROID", "1");
        // kokona-glibc/bin deliberately does NOT join PATH any more. Its nine
        // tools used to sit behind the bionic bin purely by ordering, and the
        // only reason nothing broke is that no name collided yet -- a bionic
        // curl/git/node would silently resolve to the glibc build the moment
        // one did. The glibc tools now have their own k-prefixed launchers in
        // <runtime>/bin (kiconv, kgetent, ...), plus kokona-grun itself, so the
        // two namespaces cannot overlap at all.
        env.put("PATH", runtimePath + "/bin:/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", runtimePath + "/lib");
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("TEMP", tmp.getAbsolutePath());
        env.put("TMP", tmp.getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("TERMINFO", runtimePath + "/share/terminfo");
        env.put("MAGIC", runtimePath + "/share/misc/magic.mgc");
        env.put("GIT_EXEC_PATH", runtimePath + "/libexec/git-core");
        env.put("GIT_TEMPLATE_DIR", runtimePath + "/share/git-core/templates");
        env.put("LANG", "en_US.UTF-8");
        env.put("LC_ALL", "en_US.UTF-8");
        env.put("SHELL", runtimePath + "/bin/bash");
        env.put("SSL_CERT_FILE", runtimePath + "/etc/tls/cert.pem");
        // The bundled git was linked against Termux's OpenSSL and has
        // /data/data/com.termux/files/usr/etc/tls/cert.pem compiled in as its
        // trust anchor, which does not exist under this package, so every HTTPS
        // remote failed at the TLS handshake ("error adding trust anchors"). git
        // honours GIT_SSL_CAINFO over that baked-in path; point it at the same
        // bundle the rest of the runtime uses.
        env.put("GIT_SSL_CAINFO", runtimePath + "/etc/tls/cert.pem");
        // libcrypto.so.3 was built for Termux and has
        // /data/data/com.termux/files/usr/etc/tls compiled in as OPENSSLDIR.
        // OpenSSL reads openssl.cnf from that directory while initialising and
        // aborts the process if it cannot, which is why every start on a device
        // died with "OpenSSL configuration error ... Permission denied" before
        // any JavaScript ran. Point it at the copy bundled inside the runtime.
        env.put("OPENSSL_CONF", runtimePath + "/etc/tls/openssl.cnf");
        env.put("SSL_CERT_DIR", runtimePath + "/etc/tls");
        env.put("CURL_CA_BUNDLE", runtimePath + "/etc/tls/cert.pem");
        // dsh's plugin manager shells out to pnpm. Default to a mirror that is
        // reachable from mainland China so the primary source does not strand
        // installs/updates on a network error once pnpm itself can execute.
        env.put("npm_config_registry", "https://registry.npmmirror.com");
        env.put("NODE_NO_WARNINGS", "1");
        env.put("KOKONA_APP_RUN", new File(getFilesDir(), "run").getAbsolutePath());
        env.put("DSH_BUNDLED_SKILL_DIR", runtimePath + "/skills");

        Process launched = startEngineProcess(builder, workspace, env);
        process = launched;
        Log.i(TAG, "node started pid=" + pidOf(launched));

        File logFile = new File(logs, "engine.log");
        if (logFile.length() > 2L * 1024 * 1024) {
            File previous = new File(logs, "engine.log.1");
            //noinspection ResultOfMethodCallIgnored
            previous.delete();
            //noinspection ResultOfMethodCallIgnored
            logFile.renameTo(previous);
        }
        FileOutputStream logStream = new FileOutputStream(logFile, true);
        Writer logWriter = new OutputStreamWriter(logStream, StandardCharsets.UTF_8);
        logWriter.write("\n===== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()) + " =====\n");
        logWriter.flush();

        ArrayDeque<String> tail = new ArrayDeque<>();
        boolean ready = false;
        int lineCount = 0;
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                logWriter.write(line);
                logWriter.write("\n");
                logWriter.flush();
                lineCount++;
                synchronized (tail) {
                    tail.addLast(line);
                    while (tail.size() > 24) tail.removeFirst();
                }
                if (!ready) {
                    String found = matchEngineUrl(line);
                    if (found != null) {
                        ready = true;
                        recordBootSuccess();
                        publish(State.READY, found, "引擎已就绪", 1f);
                        Log.i(TAG, "engine url ready");
                    }
                }
            }
            int exit = launched.waitFor();
            Log.i(TAG, "node exited " + exit + " after " + lineCount + " output lines");
            if (!stopping) {
                // Exit code 13 is Node's "unfinished top-level await": dsh's
                // boot promise never settled and nothing else kept the loop
                // alive. The cause is in the last lines of output, so show
                // enough of them instead of only the very last line.
                StringBuilder report = new StringBuilder("引擎进程已退出（")
                        .append(exit).append("）");
                if (exit == 13) {
                    report.append("\nNode 启动 promise 未完成（顶层 await 未结束）");
                }
                if (lineCount == 0) {
                    report.append("\n引擎没有任何输出（进程在打印前就退出了）");
                    report.append("\nABI=").append(Build.SUPPORTED_ABIS.length > 0
                            ? Build.SUPPORTED_ABIS[0] : "?");
                }
                synchronized (tail) {
                    for (String entry : tail) {
                        if (entry == null || entry.isEmpty()) continue;
                        report.append('\n').append(entry);
                    }
                }
                recordBootFailure("exit=" + exit + ", lines=" + lineCount);
                if (!startedSafe && isSafeMode(this)) {
                    report.append("\n\n已切换到安全模式：点「重试」将只加载插件市场、KHA 设置和基础界面，"
                            + "你可以先在插件页卸载刚装的插件。");
                } else if (startedSafe) {
                    report.append("\n\n（安全模式下仍然失败：问题不在插件，可能是运行时或数据目录）");
                }
                publish(State.ERROR, null, report.toString(), -1f);
            }
        } catch (Exception error) {
            Log.e(TAG, "reading engine output failed", error);
            if (!stopping) {
                publish(State.ERROR, null, "读取引擎输出失败：" + describe("read-output", error), -1f);
            }
        } finally {
            try {
                logWriter.close();
            } catch (IOException ignored) {
            }
            try {
                launched.destroy();
            } catch (Exception ignored) {
            }
            process = null;
        }
    }

    /** First URL that looks like this process's own listener, or null. */
    private static String matchEngineUrl(String line) {
        for (Pattern pattern : URL_PATTERNS) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    private static int pidOf(Process value) {
        try {
            java.lang.reflect.Method method = Process.class.getMethod("pid");
            Object result = method.invoke(value);
            return result instanceof Integer ? (Integer) result : -1;
        } catch (Exception error) {
            return -1;
        }
    }

    /**
     * Starts node directly, or wraps it in a root shell when the advanced mode
     * selected root. The root path goes through a generated script so every env
     * override survives su's environment sanitising.
     */
    private Process startEngineProcess(ProcessBuilder builder, File workspace,
                                       Map<String, String> env) throws IOException {
        // The shipping product must work WITHOUT root. The diagnostic build
        // only uses a root shell when the user drops the A/B marker file; the
        // default path reproduces the real "error 13" condition.
        boolean wantRoot = Diagnostics.rootLaunchRequested(this)
                || "root".equals(RunMode.effectiveChannel(this));
        if (!wantRoot) return builder.start();
        String su = RunMode.suPath(this);
        if (su == null) {
            Log.w(TAG, "root launch requested but su is unavailable; falling back");
            return builder.start();
        }
        File runDir = new File(getFilesDir(), "run");
        if (!runDir.isDirectory() && !runDir.mkdirs()) return builder.start();
        File script = new File(runDir, "engine-launch.sh");
        StringBuilder text = new StringBuilder("#!/system/bin/sh\n");
        text.append("cd ").append(shellQuote(workspace.getAbsolutePath())).append('\n');
        for (Map.Entry<String, String> entry : env.entrySet()) {
            text.append("export ").append(entry.getKey()).append('=')
                    .append(shellQuote(entry.getValue())).append('\n');
        }
        text.append("exec");
        for (String argument : builder.command()) {
            text.append(' ').append(shellQuote(argument));
        }
        text.append('\n');
        writeText(script, text.toString());
        //noinspection ResultOfMethodCallIgnored
        script.setExecutable(true, false);
        ProcessBuilder root = new ProcessBuilder(su, "-c",
                "/system/bin/sh " + shellQuote(script.getAbsolutePath()));
        root.directory(workspace);
        root.redirectErrorStream(true);
        return root.start();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void stopEngine() {
        stopping = true;
        Process current = process;
        if (current != null) {
            try {
                current.destroy();
            } catch (Exception ignored) {
            }
        }
        process = null;
    }

    private void acquireWakeLock() {
        try {
            PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "KokonaHarness::engine");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    private void createChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "引擎状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("KHA 引擎运行状态");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(Snapshot snapshot) {
        PendingIntent content = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, EngineService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = getString(R.string.app_name);
        String text;
        switch (snapshot.state) {
            case READY:
                text = getString(R.string.engine_running);
                break;
            case ERROR:
                text = getString(R.string.engine_error);
                break;
            case INSTALLING:
                text = getString(R.string.engine_installing);
                break;
            default:
                text = getString(R.string.engine_starting);
                break;
        }
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_48)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(R.drawable.ic_stat_24,
                        getString(R.string.stop), stop).build());
        return builder.build();
    }

    private void updateNotification(Snapshot snapshot) {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.notify(NOTIFICATION_ID, buildNotification(snapshot));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        stopEngine();
        LocalBridgeServer.stop();
        // An overlay without an engine has nothing to drive it; leaving the
        // window behind would put an orphan pet on the user's home screen.
        OverlayHost.hideQuietly(this);
        instance = null;
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
