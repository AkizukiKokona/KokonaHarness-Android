package com.knh.akizukikokona;

import android.content.Context;
import android.content.res.AssetManager;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Installs the bundled tar.xz runtime snapshot into app-private storage once
 * per runtime version. No network is involved; everything ships in the APK.
 */
public final class RuntimeInstaller {
    /** Bump when the bundled runtime changes. */
    public static final String RUNTIME_VERSION = "dsh-0.2.2-rc.1-r28";

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
            if (wanted.equals(installed)) {
                progress.onProgress(-1f, "运行时已就绪");
                return;
            }
        }

        long expected = readExpectedSize(context);
        long total = readAssetLong(context, "runtime.bytes");
        if (total <= 0) total = expected;
        long usable = context.getFilesDir().getUsableSpace();
        if (expected > 0 && usable < expected * 119 / 100 + 32L * 1024 * 1024) {
            throw new IOException(String.format(Locale.US,
                    "存储空间不足：需要约 %d MB，当前可用 %d MB",
                    (expected + 64L * 1024 * 1024) / (1024 * 1024),
                    usable / (1024 * 1024)));
        }

        progress.onProgress(-1f, "正在解压运行时…");
        deleteTree(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) {
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
            TarExtractor.extract(xz, dir, new TarExtractor.Progress() {
                @Override
                public void onBytes(long bytes) {
                    written[0] += bytes;
                    if (dataBytes <= 0) return;
                    int percent = (int) Math.min(100, written[0] * 100 / dataBytes);
                    // Only publish when the integer percent changes: extraction
                    // reports every 64 KB and would otherwise flood the UI.
                    if (percent == lastPercent[0]) return;
                    lastPercent[0] = percent;
                    progress.onProgress(percent / 100f, "正在解压运行时…");
                }
            });
            xz.close();
        } catch (Throwable error) {
            deleteTree(dir);
            throw new IOException("运行时解压失败：" + error.getMessage(), error);
        }

        File bin = new File(dir, "bin");
        File[] binaries = bin.listFiles();
        if (binaries != null) {
            for (File file : binaries) {
                file.setExecutable(true, false);
                file.setReadable(true, false);
            }
        }
        File[] libraries = new File(dir, "lib").listFiles();
        if (libraries != null) {
            for (File file : libraries) {
                if (file.isFile()) {
                    file.setReadable(true, false);
                }
            }
        }

        FileOutputStream out = new FileOutputStream(marker);
        out.write(wanted.getBytes());
        out.close();
        progress.onProgress(-1f, "运行时已就绪");
    }

    private static long readExpectedSize(Context context) {
        return readAssetLong(context, "runtime.size");
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

    static void deleteTree(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
