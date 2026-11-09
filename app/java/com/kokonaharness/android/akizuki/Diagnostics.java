package com.kokonaharness.android.akizuki;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot self-check shipped in the diagnostic build. It gathers everything
 * needed to explain the Android 16 "error 13" startup failure and writes text
 * logs to /sdcard/Download/ for the user to pull.
 *
 * <p>The final product must work WITHOUT root. Root is only used here as an
 * A/B control: the engine stays on the normal no-root launch path unless the
 * user explicitly drops a marker file for a second, root-backed pass.</p>
 */
public final class Diagnostics {

    public static final String PUBLIC_LOG = "/sdcard/Download/KHA-DIAG.txt";
    public static final String ROOT_MARKER = "/sdcard/Download/KHA-DIAG-USE-ROOT";

    private static final String TAG = "KHADIAG";
    private static final String LOG_FILE = "KHA-DIAG.txt";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/debug_ramdisk/su", "/data/adb/magisk/su", "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
    };

    private Diagnostics() {
    }

    /**
     * Diagnostic A/B switch. The default is false, i.e. the engine launches in
     * the plain app domain with no root - exactly how the shipping build must
     * work. Dropping the marker file enables one root-backed comparison pass.
     */
    public static boolean rootLaunchRequested(Context context) {
        return new File(ROOT_MARKER).isFile()
                || new File(context.getFilesDir(), "diag.use-root").isFile();
    }

    public static String modeName(Context context) {
        return rootLaunchRequested(context) ? "root" : "noroot";
    }

    public static void run(final Context context) {
        if (!STARTED.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                try {
                    collect(context.getApplicationContext(), sb);
                } catch (Throwable error) {
                    out(sb, "SELF-CHECK FAILED: " + error);
                    Log.e(TAG, "self-check failed", error);
                }
                publish(context.getApplicationContext(), sb);
            }
        }, "kha-diag").start();
    }

    private static void collect(Context context, StringBuilder sb) {
        File files = context.getFilesDir();
        File runtime = new File(files, "KokonaRuntime");
        String rt = runtime.getAbsolutePath();
        String nodePath = new File(runtime, "bin/node").getAbsolutePath();
        String dshPath = new File(runtime,
                "lib/node_modules/@deepseek-ai/dsh/lib/bin.js").getAbsolutePath();
        String su = findSu(context);

        out(sb, "KHA SELF-CHECK  " + new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        out(sb, "package=" + context.getPackageName() + "  build=1.0.36-diag");
        out(sb, "MODE=" + modeName(context)
                + "  rootLaunchRequested=" + rootLaunchRequested(context));
        out(sb, "NOTE: final product must work WITHOUT root; root is only an A/B probe.");

        section(sb, "1. DEVICE / ROM");
        out(sb, "manufacturer=" + Build.MANUFACTURER + " brand=" + Build.BRAND
                + " model=" + Build.MODEL);
        out(sb, "release=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT
                + " display=" + Build.DISPLAY);
        out(sb, "fingerprint=" + Build.FINGERPRINT);
        out(sb, "abis=" + Arrays.toString(Build.SUPPORTED_ABIS));
        out(sb, "rom props:\n" + su(su, "getprop | grep -iE "
                + "'ro.build.version|ro.product.cpu.abilist|ro.secure|ro.debuggable|ro.build.display.id' "
                + "| head -30"));

        section(sb, "2. SELINUX / ROOT");
        out(sb, "app_process_context=" + readTextFile("/proc/self/attr/current"));
        out(sb, "selinux_enforce=" + readTextFile("/sys/fs/selinux/enforce")
                + "  (1=enforcing, 0=permissive)");
        out(sb, "su_path=" + (su == null ? "(none)" : su));
        out(sb, "su_id=" + su(su, "id"));
        out(sb, "su_context=" + su(su, "cat /proc/self/attr/current"));
        out(sb, "getenforce=" + su(su, "getenforce"));
        out(sb, "node_label=" + su(su, "ls -Z '" + nodePath + "' 2>&1"));
        out(sb, "files_label=" + su(su, "ls -Zd '" + files.getAbsolutePath() + "' 2>&1"));

        section(sb, "3. RUNTIME");
        out(sb, "runtimeDir=" + rt + " exists=" + runtime.isDirectory());
        File node = new File(nodePath);
        out(sb, "node exists=" + node.isFile() + " size="
                + (node.isFile() ? node.length() : -1) + " canExec=" + node.canExecute());
        File dsh = new File(dshPath);
        out(sb, "dsh_bin exists=" + dsh.isFile() + " size="
                + (dsh.isFile() ? dsh.length() : -1));
        out(sb, "runtime.version="
                + readTextFile(new File(files, "runtime.version").getAbsolutePath()));
        out(sb, "symlinks_in_runtime=" + su(su, "find '" + rt
                + "' -type l 2>/dev/null | wc -l"));
        String nld = context.getApplicationInfo().nativeLibraryDir;
        out(sb, "nativeLibraryDir=" + nld);
        out(sb, "nativeLibraryDir listing:\n" + su(su, "ls -lZ '" + nld
                + "' 2>&1 | head -30"));

        Map<String, String> env = new HashMap<String, String>();
        env.put("LD_LIBRARY_PATH", rt + "/lib");

        section(sb, "4. TEST A - NO ROOT: app domain exec (product target)");
        out(sb, proc(new String[]{nodePath, "-v"}, env, 20));

        section(sb, "5. TEST B - ROOT CONTROL: su domain exec");
        out(sb, su(su, "LD_LIBRARY_PATH='" + rt + "/lib' '" + nodePath + "' -v 2>&1"));

        section(sb, "5b. TEST D - NO ROOT FIX CANDIDATE: nativeLibraryDir exec");
        File nldNode = new File(nld, "libnode.so");
        out(sb, "libnode.so=" + nldNode.getAbsolutePath()
                + " exists=" + nldNode.isFile()
                + " canExec=" + nldNode.canExecute());
        if (nldNode.isFile()) {
            out(sb, proc(new String[]{nldNode.getAbsolutePath(), "-v"}, env, 20));
        } else {
            out(sb, "(libnode.so not installed for this ABI; skip)");
        }

        section(sb, "6. TEST C - Node package.json reader (app data vs /data/local/tmp)");
        testPackageReader(context, sb, su, rt, nodePath);

        section(sb, "7. ENGINE STATE / LOG");
        EngineService.Snapshot snap = EngineService.current();
        out(sb, "state=" + snap.state + " message=" + snap.message);
        out(sb, "engine.log tail:\n" + EngineService.readLogTail(context, 4000));

        section(sb, "8. SYSTEM LOGS (avc / denied / error 13)");
        out(sb, su(su, "logcat -d -t 800 2>/dev/null | grep -iE "
                + "'avc:|denied|error=13|KokonaEngine|KHADIAG' | tail -80"));
        out(sb, "--- dmesg avc ---\n" + su(su,
                "dmesg 2>/dev/null | grep -i avc | tail -30"));
        out(sb, "--- mount ---\n" + su(su,
                "mount | grep -E ' /data | /data/user ' | head -20"));

        section(sb, "9. ROOT vs NO-ROOT COMPARISON");
        out(sb, "TEST A = no-root app domain (this is the product target).");
        out(sb, "TEST B = root domain (diagnostic control only).");
        out(sb, "TEST D = no-root nativeLibraryDir (candidate permanent fix).");
        out(sb, "If A fails with error=13 but D prints a version, the permanent");
        out(sb, "fix is to launch the engine from nativeLibraryDir - no root needed.");

        section(sb, "10. HOW TO READ");
        out(sb, "TEST A EXCEPTION error=13 => SELinux/W^X blocks exec from app data dir.");
        out(sb, "TEST B prints vXX.X.X => root shell bypasses the restriction.");
        out(sb, "TEST C FAIL(app data)+OK(/data/local/tmp) => Node cannot read");
        out(sb, "   package.json on app_data_file (ERR_INVALID_PACKAGE_CONFIG).");
        out(sb, "Full log also at: " + new File(files, LOG_FILE).getAbsolutePath());
        out(sb, "Public copy: " + PUBLIC_LOG + " and KHA-DIAG-<mode>.txt");
        out(sb, "logcat tag: " + TAG);
    }

    private static void testPackageReader(Context context, StringBuilder sb,
                                          String su, String rt, String nodePath) {
        File files = context.getFilesDir();
        File probe = new File(files, "kha-probe");
        probe.mkdirs();
        writeText(new File(probe, "package.json"),
                "{\"name\":\"kha-probe\",\"type\":\"module\","
                        + "\"exports\":{\".\":\"./index.mjs\"}}\n");
        writeText(new File(probe, "index.mjs"), "export const x = 1;\n");

        File script = new File(files, "kha-probe-run.mjs");
        writeText(script,
                "for (const d of process.argv.slice(2)) {\n"
                        + "  try { await import(d + '/index.mjs'); console.log('OK ' + d); }\n"
                        + "  catch (e) { console.log('FAIL ' + d + ' | ' + e.code + ' | '"
                        + " + String(e.message).split('\\n')[0]); }\n"
                        + "}\n");

        String tmp = "/data/local/tmp/kha-probe";
        String create = "rm -rf '" + tmp + "'; mkdir -p '" + tmp + "'; "
                + "cp '" + probe.getAbsolutePath() + "/package.json' '" + tmp + "/package.json'; "
                + "cp '" + probe.getAbsolutePath() + "/index.mjs' '" + tmp + "/index.mjs'; "
                + "ls -Z '" + tmp + "/package.json'";
        out(sb, "create tmp probe:\n" + su(su, create));
        out(sb, "node probe result:\n" + su(su, "LD_LIBRARY_PATH='" + rt
                + "/lib' '" + nodePath + "' '" + script.getAbsolutePath() + "' '"
                + probe.getAbsolutePath() + "' '" + tmp + "' 2>&1"));
    }

    private static void publish(Context context, StringBuilder sb) {
        String text = sb.toString();
        for (String line : text.split("\n")) {
            Log.i(TAG, line);
        }
        String suffix = modeName(context);
        File priv = new File(context.getFilesDir(), LOG_FILE);
        writeText(priv, text);
        writeText(new File(context.getFilesDir(), "KHA-DIAG-" + suffix + ".txt"), text);
        File ext = context.getExternalFilesDir(null);
        if (ext != null) {
            ext.mkdirs();
            writeText(new File(ext, LOG_FILE), text);
            writeText(new File(ext, "KHA-DIAG-" + suffix + ".txt"), text);
        }
        String su = findSu(context);
        if (su != null) {
            String cmd = "mkdir -p /sdcard/Download && cp '" + priv.getAbsolutePath()
                    + "' " + PUBLIC_LOG
                    + " && cp '" + priv.getAbsolutePath()
                    + "' /sdcard/Download/KHA-DIAG-" + suffix + ".txt"
                    + " && chmod 0644 /sdcard/Download/KHA-DIAG*.txt && echo COPIED";
            Log.i(TAG, "public copy: " + proc(new String[]{su, "-c", cmd}, null, 20));
        }
        Log.i(TAG, "=== SELF-CHECK COMPLETE (" + suffix + ") -> " + PUBLIC_LOG + " ===");
    }

    private static String findSu(Context context) {
        String known = RunMode.suPath(context);
        if (known != null) return known;
        for (String path : SU_PATHS) {
            if (new File(path).isFile()) return path;
        }
        return null;
    }

    private static String su(String su, String command) {
        if (su == null) return "(su unavailable)";
        return proc(new String[]{su, "-c", command}, null, 25);
    }

    private static String proc(String[] cmd, Map<String, String> env, int timeoutSeconds) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            if (env != null) builder.environment().putAll(env);
            builder.redirectErrorStream(true);
            process = builder.start();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            final StringBuilder text = new StringBuilder();
            Thread drain = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            text.append(line).append('\n');
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "kha-diag-drain");
            drain.start();
            boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                process.destroy();
                return "(timeout after " + timeoutSeconds + "s)\n" + text;
            }
            drain.join(2000);
            return text.toString().trim() + "\n[exit=" + process.exitValue() + "]";
        } catch (Exception error) {
            return "EXCEPTION: " + error.getClass().getSimpleName() + ": "
                    + error.getMessage();
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String readTextFile(String path) {
        try {
            FileInputStream input = new FileInputStream(path);
            byte[] buffer = new byte[512];
            int read = input.read(buffer);
            input.close();
            return read > 0 ? new String(buffer, 0, read, StandardCharsets.UTF_8).trim()
                    : "(empty)";
        } catch (Exception error) {
            return "(unreadable: " + error.getMessage() + ")";
        }
    }

    private static void writeText(File file, String text) {
        try {
            Writer writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8);
            writer.write(text);
            writer.close();
        } catch (Exception ignored) {
        }
    }

    private static void out(StringBuilder sb, String line) {
        sb.append(line).append('\n');
    }

    private static void section(StringBuilder sb, String title) {
        out(sb, "\n===== " + title + " =====");
    }
}
