package com.knh.akizukikokona;

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
    private static final String ACTION_STOP = "com.knh.akizukikokona.STOP";
    private static final Pattern URL_PATTERN =
            Pattern.compile("http://127\\.0\\.0\\.1:\\d+/?\\?token=[A-Za-z0-9_-]+");

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
        try {
            publish(State.INSTALLING, null, getString(R.string.engine_installing), -1f);
            RuntimeInstaller.install(this, new RuntimeInstaller.Progress() {
                @Override
                public void onProgress(float fraction, String message) {
                    publish(State.INSTALLING, null, message, fraction);
                }
            });
            ensureRuntimePatch();
            seedProfile();
            if (stopping) return;
            publish(State.STARTING, null, "正在启动引擎…", -1f);
            launchProcess();
        } catch (final Throwable error) {
            Log.e(TAG, "engine failed", error);
            if (!stopping) {
                publish(State.ERROR, null, describe(error), -1f);
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

    private static final String PROFILE_PACKAGE_JSON =
            "{\n  \"name\": \"dsh-profile-kokona\",\n  \"private\": true,\n"
            + "  \"dependencies\": { \"dshmarket\": \"1.73.10\","
            + " \"dsh-thinking-slider\": \"0.3.4\","
            + " \"dsh-better-sidebar\": \"0.30.1\" },\n"
            + "  \"dsh\": { \"profile\": { \"bundles\": [\"@deepseek-ai/dsh-base\","
            + " \"@deepseek-ai/dsh-web-app\", \"dshmarket\","
            + " \"dsh-thinking-slider\", \"dsh-better-sidebar\"] } }\n}\n";

    private static final String PROFILE_PNPM_WORKSPACE =
            "packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n";

    private void seedProfile() throws IOException {
        File profile = new File(new File(new File(getFilesDir(), "home"), ".dsh"),
                "profiles/" + PROFILE_NAME);
        if (!profile.isDirectory() && !profile.mkdirs() && !profile.isDirectory()) {
            throw new IOException("无法创建 " + PROFILE_NAME + " profile");
        }
        File manifest = new File(profile, "package.json");
        if (!manifest.exists()) writeText(manifest, PROFILE_PACKAGE_JSON);
        else ensureBuiltinBundles(manifest);
        ensurePackageLink(profile, "dshmarket");
        ensurePackageLink(profile, "dsh-thinking-slider");
        ensurePackageLink(profile, "dsh-better-sidebar");
        ensureMcpLink(profile);
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
        }
        ensureKnhPlusPatch(profile, RuntimeInstaller.runtimeDir(this));
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
            if (link.exists()) return;
            if (!modules.isDirectory() && !modules.mkdirs()) return;
            java.nio.file.Files.createSymbolicLink(link.toPath(),
                    new File(RuntimeInstaller.runtimeDir(this),
                            "lib/node_modules/" + name).toPath());
        } catch (Exception ignored) {
            // Filesystem without symlink support: the bundle degrades to a listed
            // but inactive entry rather than blocking the engine.
        }
    }

    /**
     * Older profiles predate the built-in bundles. Add them once without
     * disturbing the user's own dependencies or bundle order.
     */
    private void ensureBuiltinBundles(File manifest) {
        try {
            String raw = new String(java.nio.file.Files.readAllBytes(manifest.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(raw);
            org.json.JSONObject dependencies = root.optJSONObject("dependencies");
            if (dependencies == null) {
                dependencies = new org.json.JSONObject();
                root.put("dependencies", dependencies);
            }
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            if (dsh == null) {
                dsh = new org.json.JSONObject();
                root.put("dsh", dsh);
            }
            org.json.JSONObject profile = dsh.optJSONObject("profile");
            if (profile == null) {
                profile = new org.json.JSONObject();
                dsh.put("profile", profile);
            }
            org.json.JSONArray bundles = profile.optJSONArray("bundles");
            if (bundles == null) bundles = new org.json.JSONArray();
            boolean changed = false;
            String[][] builtins = {
                    {"dshmarket", "1.73.10"},
                    {"dsh-thinking-slider", "0.3.4"},
                    {"dsh-better-sidebar", "0.30.1"}
            };
            for (String[] bundle : builtins) {
                String name = bundle[0];
                boolean hasBundle = false;
                for (int index = 0; index < bundles.length(); index++) {
                    if (name.equals(bundles.optString(index))) hasBundle = true;
                }
                if (!dependencies.has(name)) {
                    dependencies.put(name, bundle[1]);
                    changed = true;
                }
                if (!hasBundle) {
                    bundles.put(name);
                    changed = true;
                }
            }
            if (changed) {
                profile.put("bundles", bundles);
                writeText(manifest, root.toString(2) + "\n");
            }
        } catch (Exception ignored) {
            // A malformed manifest is reported by dsh itself; never block boot here.
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

    private String describe(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) message = error.toString();
        return "引擎启动失败： " + message;
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

        ProcessBuilder builder = new ProcessBuilder(
                node.getAbsolutePath(),
                "--expose-internals",
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
        env.put("CURL_CA_BUNDLE", runtimePath + "/etc/tls/cert.pem");
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
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                logWriter.write(line);
                logWriter.write("\n");
                logWriter.flush();
                synchronized (tail) {
                    tail.addLast(line);
                    while (tail.size() > 24) tail.removeFirst();
                }
                if (!ready) {
                    Matcher matcher = URL_PATTERN.matcher(line);
                    if (matcher.find()) {
                        ready = true;
                        publish(State.READY, matcher.group(), "引擎已就绪", 1f);
                        Log.i(TAG, "engine url ready");
                    }
                }
            }
            int exit = launched.waitFor();
            Log.i(TAG, "node exited " + exit);
            if (!stopping) {
                String last;
                synchronized (tail) {
                    last = tail.isEmpty() ? "" : tail.peekLast();
                }
                publish(State.ERROR, null, "引擎进程已退出（" + exit + "）"
                        + (last == null || last.isEmpty() ? "" : "\n" + last), -1f);
            }
        } catch (Exception error) {
            Log.e(TAG, "reading engine output failed", error);
            if (!stopping) {
                publish(State.ERROR, null, "读取引擎输出失败：" + describe(error), -1f);
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
        if (!"root".equals(RunMode.effectiveChannel(this))) return builder.start();
        String su = RunMode.suPath(this);
        if (su == null) return builder.start();
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
        channel.setDescription("KokonaHarness 引擎运行状态");
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
        instance = null;
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
