package org.example.filemanager;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.zip.CRC32;

/** Four digests share one read. Call calculate on a worker and cancel when its dialog closes. */
final class PropertyChecksums {
    record Values(String md5, String sha1, String sha256, String crc32, long bytes) { }
    static final class Scan {
        private final AtomicBoolean started = new AtomicBoolean();
        private final Tasks.Progress progress = new Tasks.Progress();
        private final CancellationSignal signal = new CancellationSignal();
        private final AtomicReference<OwnedInput> active = new AtomicReference<>();

        void cancel() {
            progress.cancelled.set(true);
            // Provider cancellation and stream.close may wait. UI dismissal must not.
            Tasks.CONTROL.execute(signal::cancel);
            OwnedInput input = active.get();
            if (input != null) Tasks.CONTROL.execute(() -> { try { input.close(); } catch (IOException ignored) { } });
        }
        Values calculate(FileStore store, FileStore.Node node) throws IOException { return calculate(store, node, null); }
        Values calculate(FileStore store, FileStore.Node node, LongConsumer bytes) throws IOException {
            return calculate(store, node, bytes, false);
        }
        Values calculateSha256(FileStore store, FileStore.Node node, LongConsumer bytes) throws IOException {
            return calculate(store, node, bytes, true);
        }
        private Values calculate(FileStore store, FileStore.Node node, LongConsumer bytes, boolean sha256Only) throws IOException {
            begin();
            return work(() -> {
                check(); if (node.directory) throw new IOException("请选择文件计算校验值");
                FileStore.Node source = node.file == null ? node : RootAccess.automatic(node);
                String before = source.file == null ? store.identity(source) : regularIdentity(source); check();
                InputStream input = source.file != null && !source.root ? openLocal(source, before) : store.readForSearch(source, signal);
                Values values = consume(input, bytes, sha256Only);
                check(); String after = source.file == null ? store.identity(source) : regularIdentity(source);
                if (!before.equals(after)) throw new IOException("计算期间文件发生变化，请重新校验");
                return values;
            });
        }
        /** Owns and closes the supplied input, including cancellation before the first read. */
        Values calculate(InputStream input, LongConsumer bytes) throws IOException {
            begin(); return work(() -> consume(input, bytes, false));
        }
        private void begin() {
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("校验任务不能重复运行");
        }
        private Values work(Tasks.Job<Values> job) throws IOException {
            try { return progress.run(job); }
            catch (Exception error) {
                if (cancelled(error)) throw new Tasks.CancelledException();
                if (error instanceof IOException io) throw io;
                throw new IOException(error.getMessage(), error);
            }
        }
        private Values consume(InputStream raw, LongConsumer listener, boolean sha256Only) throws IOException {
            if (raw == null) throw new IOException("无法打开文件");
            OwnedInput input = new OwnedInput(raw); active.set(input);
            try (input) {
                check();
                MessageDigest md5 = sha256Only ? null : digest("MD5"), sha1 = sha256Only ? null : digest("SHA-1"), sha256 = digest("SHA-256");
                CRC32 crc32 = sha256Only ? null : new CRC32(); byte[] buffer = new byte[128 * 1024]; long total = 0;
                long last = android.os.SystemClock.elapsedRealtime();
                if (listener != null) listener.accept(0);
                for (;;) {
                    check(); int count = input.read(buffer); check();
                    if (count < 0) break;
                    if (count == 0) continue;
                    if (md5 != null) md5.update(buffer, 0, count);
                    if (sha1 != null) sha1.update(buffer, 0, count);
                    sha256.update(buffer, 0, count);
                    if (crc32 != null) crc32.update(buffer, 0, count);
                    total = Math.addExact(total, count);
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (listener != null && now - last >= 100) { listener.accept(total); last = now; }
                }
                check(); if (listener != null) listener.accept(total);
                return new Values(md5 == null ? "" : hex(md5.digest()), sha1 == null ? "" : hex(sha1.digest()), hex(sha256.digest()),
                        crc32 == null ? "" : String.format(Locale.ROOT, "%08x", crc32.getValue()), total);
            } finally { active.compareAndSet(input, null); }
        }
        private void check() throws Tasks.CancelledException {
            if (progress.cancelled.get() || signal.isCanceled()) throw new Tasks.CancelledException();
            Tasks.Progress.check();
        }
        private boolean cancelled(Throwable error) {
            if (progress.cancelled.get() || Thread.currentThread().isInterrupted()) return true;
            for (Throwable cause = error; cause != null; cause = cause.getCause())
                if (cause instanceof Tasks.CancelledException || cause instanceof OperationCanceledException) return true;
            return false;
        }
    }
    private static final class OwnedInput extends FilterInputStream {
        private final AtomicBoolean closed = new AtomicBoolean();
        OwnedInput(InputStream input) { super(input); }
        @Override public void close() throws IOException { if (closed.compareAndSet(false, true)) super.close(); }
    }
    static String regularIdentity(FileStore.Node node) throws IOException {
        String identity;
        if (node.root) {
            String[] fields = RootDirectoryReader.stat(node.path(), "%f|%d:%i:%s:%Y:%Z:%y:%z:%a:%u:%g", false).split("\\|", 2);
            try {
                if (fields.length != 2 || !OsConstants.S_ISREG(Integer.parseInt(fields[0], 16)))
                    throw new IOException("仅普通文件支持校验，请打开链接目标；设备和管道不读取");
                identity = fields[1];
            } catch (NumberFormatException error) { throw new IOException("ROOT 文件信息无效", error); }
        } else try {
            StructStat stat = Os.lstat(node.path());
            if (!OsConstants.S_ISREG(stat.st_mode)) throw new IOException("仅普通文件支持校验，请打开链接目标；设备和管道不读取");
            identity = localIdentity(stat);
        } catch (android.system.ErrnoException error) { throw new IOException(error.getMessage(), error); }
        if (node.identity != null && !identity.startsWith(node.identity + ":"))
            throw new IOException("所选文件已被替换，请刷新后重新校验");
        return identity;
    }
    private static InputStream openLocal(FileStore.Node node, String expected) throws IOException {
        // O_PATH classifies the leaf without opening a replaced device/FIFO. Reopen only
        // this pinned regular inode, so a path replacement cannot make open() block.
        final int pathOnly = 010000000;
        try {
            FileDescriptor raw = Os.open(node.path(), pathOnly | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
            ParcelFileDescriptor pinned;
            try { pinned = ParcelFileDescriptor.dup(raw); } finally { Os.close(raw); }
            try (pinned) {
                StructStat stat = Os.fstat(pinned.getFileDescriptor());
                if (!OsConstants.S_ISREG(stat.st_mode) || !expected.equals(localIdentity(stat)))
                    throw new IOException("文件已变化或不是普通文件，请刷新后重新校验");
                Tasks.Progress.check();
                raw = Os.open("/proc/self/fd/" + pinned.getFd(), OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
                ParcelFileDescriptor readable;
                try { readable = ParcelFileDescriptor.dup(raw); } finally { Os.close(raw); }
                try {
                    if (!expected.equals(localIdentity(Os.fstat(readable.getFileDescriptor()))))
                        throw new IOException("文件打开期间已变化，请重新校验");
                    return new ParcelFileDescriptor.AutoCloseInputStream(readable) {
                        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
                            int read = super.read(buffer, offset, count);
                            if (read < 0) try {
                                if (!expected.equals(localIdentity(Os.fstat(readable.getFileDescriptor()))))
                                    throw new IOException("计算期间文件发生变化，请重新校验");
                            } catch (android.system.ErrnoException error) { throw new IOException(error.getMessage(), error); }
                            return read;
                        }
                    };
                } catch (IOException | RuntimeException | android.system.ErrnoException error) { readable.close(); throw error; }
            }
        } catch (android.system.ErrnoException error) { throw new IOException(error.getMessage(), error); }
    }
    private static String localIdentity(StructStat stat) {
        if (android.os.Build.VERSION.SDK_INT >= 27)
            return FileStore.statIdentity(stat) + ":" + stat.st_mtim.tv_nsec + ":" + stat.st_ctim.tv_sec + ":" + stat.st_ctim.tv_nsec;
        return FileStore.statIdentity(stat) + ":" + stat.st_ctime;
    }
    static String compare(String expected, Values values) {
        String candidate = expected == null ? "" : expected.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String explicit = null;
        int colon = candidate.indexOf(':');
        if (colon >= 0) {
            explicit = candidate.substring(0, colon).replace("-", "");
            candidate = candidate.substring(colon + 1);
        }
        String algorithm = switch (candidate.length()) {
            case 32 -> "md5"; case 40 -> "sha1"; case 64 -> "sha256"; case 8 -> "crc32"; default -> "";
        };
        if (algorithm.isEmpty() || !candidate.matches("[0-9a-f]+") || explicit != null && !explicit.equals(algorithm))
            return "输入 32 位 MD5、40 位 SHA-1、64 位 SHA-256 或 8 位 CRC32";
        String actual = switch (algorithm) { case "md5" -> values.md5; case "sha1" -> values.sha1;
            case "sha256" -> values.sha256; default -> values.crc32; };
        String label = switch (algorithm) { case "sha1" -> "SHA-1"; case "sha256" -> "SHA-256";
            default -> algorithm.toUpperCase(Locale.ROOT); };
        return label + (candidate.equals(actual) ? " 匹配" : " 不匹配");
    }
    private static MessageDigest digest(String name) {
        try { return MessageDigest.getInstance(name); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) { int b = value & 255; output.append("0123456789abcdef".charAt(b >>> 4)).append("0123456789abcdef".charAt(b & 15)); }
        return output.toString();
    }
    private PropertyChecksums() { }
}
