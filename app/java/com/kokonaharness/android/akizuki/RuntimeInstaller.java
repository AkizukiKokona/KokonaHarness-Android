package com.kokonaharness.android.akizuki;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Installs the bundled tar.zst runtime snapshot into app-private storage by
 * full extraction. No network is involved; everything ships in the APK.
 * Updates always unpack a complete new tree and swap it in, because partial
 * overlays silently kept stale or empty package directories behind.
 *
 * The archive is zstd, decoded by the `kunzstd` helper that also ships as an
 * asset. A native decoder is used instead of a Java library because Android has
 * no zstd API: the helper is exec'd with the compressed asset on its stdin and
 * its stdout is fed straight into TarExtractor, so the 700 MB tar is never
 * written to a temporary file.
 */
public final class RuntimeInstaller {
    private static final String ARCHIVE = "runtime.tar.zst";
    private static final String UNPACKER = "kunzstd";

    /** Bump when the bundled runtime changes. */
    // Bump the revision whenever the packaged snapshot changes shape, not only
    // when the upstream dsh version moves. r33 shipped the snapshot with the
    // pre-plugin settings UI inside it, so devices that installed it kept that
    // tree and our later kha-setthings bundle ended up layered on top of the
    // old files. r34 forces the one full re-extract that clears the overlap.
    public static final String RUNTIME_VERSION = "dsh-0.2.2-rc.1-r48";

    /**
     * The DSH release inside the runtime, without the packaging suffix: the boot
     * screen shows this next to the KHA version, and they are two different
     * things (app build vs. bundled engine).
     */
    public static String dshVersion() {
        String value = RUNTIME_VERSION;
        if (value.startsWith("dsh-")) value = value.substring(4);
        int suffix = value.lastIndexOf("-r");
        return suffix > 0 ? value.substring(0, suffix) : value;
    }

    public interface Progress {
        void onProgress(float fraction, String message);
    }

    private RuntimeInstaller() {
    }

    public static File runtimeDir(Context context) {
        return new File(context.getFilesDir(), "KokonaRuntime");
    }

    public static synchronized void install(Context context, Progress progress)
            throws IOException {
        File dir = runtimeDir(context);
        File marker = new File(context.getFilesDir(), "runtime.version");
        String wanted = RUNTIME_VERSION + "-" + android.os.Build.SUPPORTED_ABIS[0];
        if (dir.isDirectory() && marker.isFile()) {
            String installed = readText(marker);
            if (wanted.equals(installed) && runtimeLooksComplete(dir)) {
                // Exec bits are repaired on every boot, including this fast
                // path: the snapshot is packed on Windows/MSYS, which does not
                // preserve Unix modes for npm-shipped binaries outside bin/.
                // Without this, pnpm stays 0644 and plugin install/update dies
                // with "can't execute: Permission denied" on an existing install.
                ensureExecutables(dir);
                progress.onProgress(-1f, "运行时已就绪");
                return;
            }
        }

        long expected = readAssetLong(context, "runtime.size");
        long total = readAssetLong(context, "runtime.bytes");
        if (total <= 0) total = expected;
        long usable = context.getFilesDir().getUsableSpace();
        long needed = expected * 119 / 100 + 32L * 1024 * 1024;
        if (expected > 0 && usable < needed) {
            throw new IOException(String.format(Locale.US,
                    "存储空间不足：需要约 %d MB，当前可用 %d MB",
                    (needed + 32L * 1024 * 1024) / (1024 * 1024),
                    usable / (1024 * 1024)));
        }

        progress.onProgress(-1f, "正在解压运行时…");
        // Full install: unpack into a brand-new tree and swap it in afterwards.
        // Extracting over the previous tree kept files the new runtime no longer
        // ships, which made updates behave like a partial overlay.
        File staging = new File(context.getFilesDir(), "KokonaRuntime.new");
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("无法创建运行时目录");
        }

        try {
            extractArchive(context, staging, progress, total);
        } catch (Throwable error) {
            // The previous tree is still in place and still marked installed;
            // the next boot retries the same full install.
            deleteRecursively(staging);
            throw new IOException("运行时解压失败：" + error.getMessage(), error);
        }

        ensureExecutables(staging);

        // Swap the finished tree in: the old tree leaves atomically, so a
        // half-written runtime can never be left behind.
        File previous = new File(context.getFilesDir(), "KokonaRuntime.old");
        deleteRecursively(previous);
        if (dir.exists() && !dir.renameTo(previous)) {
            deleteRecursively(dir);
        }
        if (!staging.renameTo(dir)) {
            deleteRecursively(staging);
            if (previous.exists() && !dir.exists()) previous.renameTo(dir);
            throw new IOException("无法切换运行时目录");
        }
        deleteRecursively(previous);

        FileOutputStream out = new FileOutputStream(marker);
        out.write(wanted.getBytes());
        out.close();
        progress.onProgress(-1f, "运行时已就绪");
    }

    /**
     * Decode the bundled .tar.zst into {@code staging}.
     *
     * One pipe, no temporary file: a feeder thread pushes the compressed asset
     * into the helper's stdin while this thread parses the tar out of its
     * stdout. Both the feeder and a stderr drain run on their own threads so a
     * full pipe can never deadlock the decoder.
     */
    private static void extractArchive(final Context context, File staging,
                                       final Progress progress, long dataBytes)
            throws IOException {
        final File unpacker = materializeUnpacker(context);
        final Process process;
        try {
            process = new ProcessBuilder(unpacker.getAbsolutePath()).start();
        } catch (IOException error) {
            throw new IOException("无法启动解压器 " + unpacker + "：" + error.getMessage(), error);
        }

        final StringBuilder errors = new StringBuilder();

        Thread feed = new Thread(new Runnable() {
            @Override
            public void run() {
                try (InputStream src = new BufferedInputStream(
                        context.getAssets().open(ARCHIVE, AssetManager.ACCESS_STREAMING), 1 << 16);
                     OutputStream dst = process.getOutputStream()) {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = src.read(buffer)) > 0) dst.write(buffer, 0, read);
                } catch (IOException ignored) {
                    // A decoder that already failed closes the pipe early; the
                    // exit status reported below is the useful diagnosis.
                }
            }
        }, "kha-runtime-feed");
        feed.setDaemon(true);
        feed.start();

        Thread drain = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[512];
                try {
                    int read;
                    while ((read = process.getErrorStream().read(buffer)) > 0) {
                        synchronized (errors) {
                            if (errors.length() < 2000) errors.append(new String(buffer, 0, read));
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }, "kha-runtime-stderr");
        drain.setDaemon(true);
        drain.start();

        final long[] written = {0};
        final int[] lastPercent = {-1};
        final long total = dataBytes;
        try {
            TarExtractor.extract(new BufferedInputStream(process.getInputStream(), 1 << 16),
                    staging, new TarExtractor.Progress() {
                        @Override
                        public void onBytes(long bytes) {
                            written[0] += bytes;
                            if (total <= 0) return;
                            int percent = (int) Math.min(100, written[0] * 100 / total);
                            // Only publish when the integer percent changes:
                            // extraction reports every 64 KB and would otherwise
                            // flood the UI.
                            if (percent == lastPercent[0]) return;
                            lastPercent[0] = percent;
                            progress.onProgress(percent / 100f, "正在解压运行时…");
                        }
                    });
            int code = process.waitFor();
            try {
                feed.join(4000);
                drain.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (code != 0) {
                String detail;
                synchronized (errors) {
                    detail = errors.toString().trim();
                }
                throw new IOException("zstd 解压失败 (rc=" + code + ")"
                        + (detail.isEmpty() ? "" : "：" + detail));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("运行时解压被中断", error);
        } finally {
            process.destroy();
        }
    }

    /** Copy the bundled decoder out of the APK and make it executable. */
    private static File materializeUnpacker(Context context) throws IOException {
        File exe = new File(context.getFilesDir(), UNPACKER);
        try (InputStream input = context.getAssets().open(UNPACKER);
             FileOutputStream out = new FileOutputStream(exe)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = input.read(buffer)) > 0) out.write(buffer, 0, read);
            out.getFD().sync();
        }
        if (!exe.setExecutable(true, false)) {
            throw new IOException("无法设置解压器执行权限：" + exe);
        }
        return exe;
    }

    /**
     * Re-apply exec/read bits on everything the engine and dsh's plugin
     * manager actually execute. The tar snapshot is produced on Windows/MSYS,
     * where NTFS does not carry Unix modes for binaries unpacked from npm
     * tarballs, so helpers outside bin/ arrive as 0644. In particular
     * {@code @pnpm/exe.android-arm64/pnpm} is exec'd by bin/pnpm and failed
     * with "can't execute: Permission denied", which broke plugin install and
     * auto-update, and {@code python/bin/python3.15} (the CPython launcher,
     * under python/bin/ rather than bin/) failed the same way so every
     * python/python3/pip invocation returned rc=126. Called on every boot
     * (including the already-installed fast path) so fixing this never
     * requires re-extracting the 600 MB tree.
     */
    public static void ensureExecutables(File dir) {
        if (!dir.isDirectory()) return;
        File[] binaries = new File(dir, "bin").listFiles();
        if (binaries != null) {
            for (File file : binaries) if (file.isFile()) makeExecutable(file);
        }
        makeExecutable(new File(dir,
                "lib/node_modules/@pnpm/exe.android-arm64/pnpm"));
        // The CPython launcher is a thin executable (it links libpython3.15.so
        // and lives under python/bin/, not bin/), so the bin/* sweep above does
        // not touch it. Without this, python/python3/python3.15 fail with
        // "Permission denied" (rc=126) on every install. Covered on every boot
        // so an existing install is repaired without re-extracting the tree.
        File[] pyBin = new File(dir, "python/bin").listFiles();
        if (pyBin != null) {
            for (File file : pyBin) if (file.isFile()) makeExecutable(file);
        }
        // bin/git execs these helpers directly.
        File[] helpers = new File(dir, "libexec/git-core").listFiles();
        if (helpers != null) {
            for (File file : helpers) if (file.isFile()) makeExecutable(file);
        }
        // The Landlock runner sits in an ABI-named addon directory.
        File[] addons = new File(dir, "lib/node_modules/@deepseek-ai").listFiles();
        if (addons != null) {
            for (File entry : addons) {
                if (!entry.isDirectory()
                        || !entry.getName().startsWith("node-addon-system-android-")) continue;
                File[] runners = new File(entry, "bin").listFiles();
                if (runners == null) continue;
                for (File file : runners) if (file.isFile()) makeExecutable(file);
            }
        }
        File[] libraries = new File(dir, "lib").listFiles();
        if (libraries != null) {
            for (File file : libraries) if (file.isFile()) file.setReadable(true, false);
        }
    }

    private static void makeExecutable(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.setExecutable(true, false);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, false);
    }

    /**
     * Cheap sanity check on an installed tree. A version marker alone is not
     * enough: an interrupted or partial extraction can leave package
     * directories behind with their contents missing, and dsh then fails to
     * activate those bundles. Spot-check the entry points dsh must load.
     */
    private static boolean runtimeLooksComplete(File dir) {
        String[] required = {
                "bin/node",
                "lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
                "lib/node_modules/@deepseek-ai/dsh-app-boot/package.json",
                "lib/node_modules/@deepseek-ai/dsh-mcp-client/package.json",
                "lib/node_modules/dsh-better-sidebar/package.json",
                "lib/node_modules/dshmarket/package.json",
        };
        for (String relative : required) {
            File file = new File(dir, relative);
            if (!file.isFile() || file.length() == 0) {
                android.util.Log.w("RuntimeInstaller",
                        "runtime incomplete, missing " + relative + "; reinstalling");
                return false;
            }
        }
        return true;
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Sum of the regular-file bytes in the snapshot, i.e. what extraction reports. */
    private static long readAssetLong(Context context, String name) {
        try {
            InputStream input = context.getAssets().open(name);
            String text = readText(input);
            return Long.parseLong(text.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String readText(File file) {
        try {
            FileInputStream input = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int read = input.read(data);
            input.close();
            return read > 0 ? new String(data, 0, read) : "";
        } catch (IOException error) {
            return "";
        }
    }

    private static String readText(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[64];
        int read;
        while ((read = input.read(buffer)) > 0) {
            for (int i = 0; i < read; i++) {
                char c = (char) buffer[i];
                if (c >= '0' && c <= '9') builder.append(c);
            }
        }
        input.close();
        return builder.toString();
    }
}
