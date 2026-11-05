package com.knh.akizukikokona;

import android.system.Os;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal tar reader for the bundled runtime snapshot. It understands GNU
 * long names and PAX extended headers, because npm package paths easily
 * exceed the 100-character ustar field.
 */
public final class TarExtractor {
    private static final int BLOCK = 512;

    public interface Progress {
        void onBytes(long bytes);
    }

    private TarExtractor() {
    }

    public static void extract(InputStream tar, File destination, Progress progress)
            throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建目标目录");
        }
        String longName = null;
        String paxPath = null;
        while (true) {
            byte[] header = readBlock(tar);
            if (header == null) return;
            boolean empty = true;
            for (byte value : header) {
                if (value != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) return;

            char type = (char) header[156];
            long size = parseNumber(header, 124, 12);
            if (size < 0) throw new IOException("tar 条目大小非法");

            if (type == 'L') {
                longName = readSmall(tar, size);
                continue;
            }
            if (type == 'x') {
                String pax = readSmall(tar, size);
                String found = paxValue(pax, "path");
                if (found != null) paxPath = found;
                continue;
            }
            if (type == 'g' || type == 'K') {
                skip(tar, size);
                continue;
            }

            String name = paxPath != null ? paxPath
                    : (longName != null ? longName : headerName(header));
            longName = null;
            paxPath = null;
            if (name == null || name.isEmpty()) {
                skip(tar, size);
                continue;
            }

            File target = resolve(destination, name);
            int mode = (int) parseNumber(header, 100, 8);
            if (type == '5') {
                if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
                    throw new IOException("无法创建目录 " + name);
                }
                chmod(target, mode | 0700);
                skip(tar, size);
            } else if (type == '0' || type == '\0' || type == '7') {
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录 " + parent);
                }
                FileOutputStream out = new FileOutputStream(target);
                try {
                    copy(tar, out, size, progress);
                } finally {
                    out.close();
                }
                chmod(target, mode);
            } else if (type == '2') {
                // Snapshot archives are built without symlinks; tolerate and skip.
                skip(tar, size);
            } else {
                skip(tar, size);
            }
        }
    }

    private static void copy(InputStream input, FileOutputStream out, long size,
                             Progress progress) throws IOException {
        byte[] buffer = new byte[65536];
        long remaining = size;
        while (remaining > 0) {
            int want = (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, want);
            if (read < 0) throw new EOFException("tar 数据提前结束");
            out.write(buffer, 0, read);
            remaining -= read;
            // Report while writing instead of once per file: the archive holds a
            // few very large files (node), and only streaming keeps the boot
            // percentage advancing smoothly.
            if (progress != null) progress.onBytes(read);
        }
        skip(input, size % BLOCK == 0 ? 0 : BLOCK - size % BLOCK);
    }

    private static byte[] readBlock(InputStream input) throws IOException {
        byte[] block = new byte[BLOCK];
        int offset = 0;
        while (offset < BLOCK) {
            int read = input.read(block, offset, BLOCK - offset);
            if (read < 0) {
                if (offset == 0) return null;
                throw new EOFException("tar 头不完整");
            }
            offset += read;
        }
        return block;
    }

    private static String readSmall(InputStream input, long size) throws IOException {
        if (size > 65536) throw new IOException("tar 扩展头异常");
        byte[] data = new byte[(int) size];
        int offset = 0;
        while (offset < data.length) {
            int read = input.read(data, offset, data.length - offset);
            if (read < 0) throw new EOFException("tar 数据提前结束");
            offset += read;
        }
        skip(input, (BLOCK - size % BLOCK) % BLOCK);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void skip(InputStream input, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) throw new EOFException("tar 数据提前结束");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static File resolve(File root, String name) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new IOException("非法的 tar 路径 " + name);
        }
        return new File(root, normalized);
    }

    private static String headerName(byte[] header) {
        String name = cString(header, 0, 100);
        String prefix = cString(header, 345, 155);
        if (!prefix.isEmpty()) return prefix + "/" + name;
        return name;
    }

    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long parseNumber(byte[] header, int offset, int length) {
        String text = cString(header, offset, length).trim();
        if (text.isEmpty()) return 0;
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static String paxValue(String pax, String key) {
        for (String line : pax.split("\n")) {
            int space = line.indexOf(' ');
            if (space <= 0) continue;
            String body = line.substring(space + 1);
            if (body.startsWith(key + "=")) {
                return body.substring(key.length() + 1);
            }
        }
        return null;
    }

    private static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode & 0777);
        } catch (Exception ignored) {
        }
    }
}
