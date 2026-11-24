package org.example.filemanager;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.concurrent.atomic.AtomicBoolean;

final class FileIntegrity {
    record Values(String sha256, String crc32) { }
    static Values calculate(FileStore store, FileStore.Node node) throws IOException {
        return calculate(store, node, new AtomicBoolean());
    }
    static Values calculate(FileStore store, FileStore.Node node, AtomicBoolean cancelled) throws IOException {
        check(cancelled);
        return calculate(store.read(node), cancelled);
    }
    static Values calculate(InputStream input, AtomicBoolean cancelled) throws IOException {
        try (InputStream owned = input) { return readDigest(owned, cancelled); }
    }
    private static Values readDigest(InputStream input, AtomicBoolean cancelled) throws IOException {
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        CRC32 crc = new CRC32(); byte[] buffer = new byte[128 * 1024]; int size;
        while (true) {
            check(cancelled);
            size = input.read(buffer);
            check(cancelled);
            if (size == -1) break;
            if (size > 0) { sha.update(buffer, 0, size); crc.update(buffer, 0, size); }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : sha.digest()) {
            int b = value & 255; hex.append("0123456789abcdef".charAt(b >>> 4)).append("0123456789abcdef".charAt(b & 15));
        }
        return new Values(hex.toString(), String.format(Locale.ROOT, "%08x", crc.getValue()));
    }
    private static void check(AtomicBoolean cancelled) throws Tasks.CancelledException {
        Tasks.Progress.check();
        if (cancelled.get()) throw new Tasks.CancelledException();
    }
    static String compare(String expected, Values values) {
        String candidate = expected == null ? "" : expected.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (candidate.startsWith("sha256:")) candidate = candidate.substring(7);
        else if (candidate.startsWith("sha-256:")) candidate = candidate.substring(8);
        else if (candidate.startsWith("crc32:")) candidate = candidate.substring(6);
        if (candidate.matches("[0-9a-f]{64}")) return candidate.equals(values.sha256()) ? "SHA-256 匹配" : "SHA-256 不匹配";
        if (candidate.matches("[0-9a-f]{8}")) return candidate.equals(values.crc32()) ? "CRC32 匹配" : "CRC32 不匹配";
        return "输入 64 位 SHA-256 或 8 位 CRC32";
    }
    private FileIntegrity() { }
}
