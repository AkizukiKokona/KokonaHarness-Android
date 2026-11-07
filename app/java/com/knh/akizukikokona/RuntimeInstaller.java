package com.knh.akizukikokona;

import android.content.Context;
import android.content.res.AssetManager;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Installs the bundled tar.xz runtime snapshot into app-private storage by
 * full extraction. No network is involved; everything ships in the APK.
 * The per-file manifest is still written so future tooling can diff installs,
 * but updates always rewrite the whole tree (simplest and most reliable).
 */
public final class RuntimeInstaller {
    /** Bump when the bundled runtime changes. */
    public static final String RUNTIME_VERSION = "dsh-0.2.2-rc.1-r33";

    private static final String MANIFEST_ASSET = "runtime.manifest";

    public interface Progress {
        void onProgress(float fraction, String message);
    }

    private static final class Entry {
        final long size;
        final String hash;

        Entry(long size, String hash) {
            this.size = size;
            this.hash = hash;
        }
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
        File installedManifest = new File(context.getFilesDir(), "runtime.manifest");
        String wanted = RUNTIME_VERSION + "-" + android.os.Build.SUPPORTED_ABIS[0];
        if (dir.isDirectory() && marker.isFile() && installedManifest.isFile()) {
            String installed = readText(marker);
            if (wanted.equals(installed)) {
                progress.onProgress(-1f, "运行时已就绪");
                return;
            }
        }

        Map<String, Entry> next;
        try {
            InputStream input = context.getAssets().open(MANIFEST_ASSET);
            next = readManifest(input);
        } catch (Exception error) {
            throw new IOException("运行时清单缺失", error);
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

        AssetManager assets = context.getAssets();
        try {
            InputStream raw = new BufferedInputStream(
                    assets.open("runtime.tar.xz", AssetManager.ACCESS_STREAMING), 65536);
            XZInputStream xz = new XZInputStream(raw);
            final long[] written = {0};
            final int[] lastPercent = {-1};
            final long dataBytes = total;
            final String message = "正在解压运行时…";
            TarExtractor.extract(xz, staging, new TarExtractor.Progress() {
                @Override
                public void onBytes(long bytes) {
                    written[0] += bytes;
                    if (dataBytes <= 0) return;
                    int percent = (int) Math.min(100, written[0] * 100 / dataBytes);
                    // Only publish when the integer percent changes: extraction
                    // reports every 64 KB and would otherwise flood the UI.
                    if (percent == lastPercent[0]) return;
                    lastPercent[0] = percent;
                    progress.onProgress(percent / 100f, message);
                }
            }, new TarExtractor.Filter() {
                @Override
                public boolean shouldExtract(String name, long size) {
                    return true;
                }
            });
            xz.close();
        } catch (Throwable error) {
            // The previous tree is still in place and still marked installed;
            // the next boot retries the same full install.
            deleteRecursively(staging);
            throw new IOException("运行时解压失败：" + error.getMessage(), error);
        }

        // Re-apply exec/read bits: tar entries may not carry mode information.
        File bin = new File(staging, "bin");
        File[] binaries = bin.listFiles();
        if (binaries != null) {
            for (File file : binaries) {
                file.setExecutable(true, false);
                file.setReadable(true, false);
            }
        }
        File[] libraries = new File(staging, "lib").listFiles();
        if (libraries != null) {
            for (File file : libraries) {
                if (file.isFile()) {
                    file.setReadable(true, false);
                }
            }
        }

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

        File staged = new File(context.getFilesDir(), "runtime.manifest.tmp");
        copyAsset(assets, MANIFEST_ASSET, staged);
        if (installedManifest.isFile() && !installedManifest.delete()) {
            throw new IOException("无法替换运行时清单");
        }
        if (!staged.renameTo(installedManifest)) {
            throw new IOException("无法保存运行时清单");
        }

        FileOutputStream out = new FileOutputStream(marker);
        out.write(wanted.getBytes());
        out.close();
        progress.onProgress(-1f, "运行时已就绪");
    }

    private static Map<String, Entry> readManifest(InputStream input) throws IOException {
        Map<String, Entry> entries = new HashMap<String, Entry>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8), 16384);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int first = line.indexOf(' ');
                int second = first < 0 ? -1 : line.indexOf(' ', first + 1);
                if (second < 0) continue;
                try {
                    long size = Long.parseLong(line.substring(first + 1, second));
                    entries.put(line.substring(second + 1),
                            new Entry(size, line.substring(0, first)));
                } catch (NumberFormatException ignored) {
                }
            }
        } finally {
            reader.close();
        }
        return entries;
    }

    private static Map<String, Entry> readManifest(File file) {
        try {
            return readManifest(new FileInputStream(file));
        } catch (IOException error) {
            return new HashMap<String, Entry>();
        }
    }

    private static String hash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileInputStream input = new FileInputStream(file);
            try {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
            } finally {
                input.close();
            }
            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(Character.forDigit((value >> 4) & 0xf, 16))
                        .append(Character.forDigit(value & 0xf, 16));
            }
            return builder.toString();
        } catch (Exception error) {
            return "";
        }
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

    private static void copyAsset(AssetManager assets, String name, File target)
            throws IOException {
        InputStream input = assets.open(name, AssetManager.ACCESS_STREAMING);
        FileOutputStream output = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        } finally {
            try {
                input.close();
            } finally {
                output.close();
            }
        }
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
