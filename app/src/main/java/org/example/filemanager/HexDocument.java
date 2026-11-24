package org.example.filemanager;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;

/** A disk-backed piece table. Editing never changes the source until FileStore publishes a save. */
final class HexDocument implements Closeable {
    static final int CLIP_LIMIT = 1024 * 1024;
    private record Piece(boolean added, long offset, long length) {}
    private record State(List<Piece> pieces, long size, long revision) {}
    private final File original, additions;
    private final RandomAccessFile base, edits;
    private List<Piece> pieces;
    private long size, revision, nextRevision, savedRevision;
    private final Deque<State> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    HexDocument(File snapshot, File cache) throws IOException {
        original = snapshot; additions = File.createTempFile("hex-edits-", ".tmp", cache);
        RandomAccessFile opened = new RandomAccessFile(snapshot, "r");
        try { edits = new RandomAccessFile(additions, "rw"); }
        catch (IOException e) { opened.close(); Files.deleteIfExists(additions.toPath()); throw e; }
        base = opened; size = base.length(); pieces = size == 0 ? List.of() : List.of(new Piece(false, 0, size));
    }
    long size() { return size; }
    boolean dirty() { return revision != savedRevision; }
    void markSaved() { savedRevision = revision; }
    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }
    private State state() { return new State(pieces, size, revision); }
    private void restore(State s) { pieces = s.pieces; size = s.size; revision = s.revision; }
    void undo() { if (canUndo()) { redo.push(state()); restore(undo.pop()); } }
    void redo() { if (canRedo()) { undo.push(state()); restore(redo.pop()); } }
    private void commit(List<Piece> updated, long length) {
        undo.push(state()); while (undo.size() > 100) undo.removeLast(); redo.clear();
        pieces = List.copyOf(updated); size = length; revision = ++nextRevision;
    }
    byte[] read(long position, int count) throws IOException {
        if (position < 0 || position > size || count < 0 || count > CLIP_LIMIT) throw new IOException("读取范围无效");
        byte[] out = new byte[(int)Math.min(count, size - position)]; int written = 0; long start = 0;
        for (Piece piece : pieces) {
            long end = start + piece.length;
            if (end > position && written < out.length) {
                long within = Math.max(0, position - start); int n = (int)Math.min(piece.length - within, out.length - written);
                RandomAccessFile file = piece.added ? edits : base; file.seek(piece.offset + within); file.readFully(out, written, n);
                written += n; position += n;
            }
            start = end; if (written == out.length) break;
        }
        return out;
    }
    private List<Piece> slice(long from, long to) {
        List<Piece> result = new ArrayList<>(); long start = 0;
        for (Piece p : pieces) {
            long end = start + p.length, a = Math.max(start, from), b = Math.min(end, to);
            if (a < b) result.add(new Piece(p.added, p.offset + a - start, b - a));
            start = end; if (start >= to) break;
        } return result;
    }
    void replace(long position, long count, byte[] bytes) throws IOException {
        if (position < 0 || count < 0 || position > size || count > size - position || bytes.length > CLIP_LIMIT)
            throw new IOException("编辑范围无效，单次粘贴最多 1 MiB");
        if (count == 0 && bytes.length == 0) return;
        long append = edits.length(); edits.seek(append); edits.write(bytes);
        List<Piece> result = slice(0, position);
        if (bytes.length > 0) result.add(new Piece(true, append, bytes.length));
        result.addAll(slice(position + count, size)); commit(result, Math.addExact(size - count, bytes.length));
    }
    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已停止");
    }
    private static int[] prefix(byte[] pattern) {
        int[] table = new int[pattern.length];
        for (int i = 1, j = 0; i < pattern.length; i++) {
            while (j > 0 && pattern[i] != pattern[j]) j = table[j - 1];
            if (pattern[i] == pattern[j]) j++; table[i] = j;
        } return table;
    }
    long find(byte[] pattern, long from, long end, boolean last, BooleanSupplier cancelled) throws IOException {
        if (pattern.length == 0 || pattern.length > CLIP_LIMIT) throw new IOException("搜索内容不能为空或超过 1 MiB");
        int[] table = prefix(pattern); int matched = 0; long found = -1;
        for (long pos = Math.max(0, from); pos < Math.min(size, end);) {
            check(cancelled); byte[] data = read(pos, (int)Math.min(65536, Math.min(size, end) - pos));
            for (int i = 0; i < data.length; i++) {
                while (matched > 0 && data[i] != pattern[matched]) matched = table[matched - 1];
                if (data[i] == pattern[matched]) matched++;
                if (matched == pattern.length) {
                    found = pos + i + 1 - matched; if (!last) return found; matched = table[matched - 1];
                }
            } pos += data.length;
        } return found;
    }
    /** Replaces non-overlapping matches as one undoable transaction; cancellation publishes nothing. */
    long replaceAll(byte[] pattern, byte[] replacement, BooleanSupplier cancelled) throws IOException {
        if (pattern.length == 0 || pattern.length > CLIP_LIMIT || replacement.length > CLIP_LIMIT) throw new IOException("搜索或替换长度无效");
        int[] table = prefix(pattern); int matched = 0; long count = 0, lastEnd = 0;
        List<Piece> result = new ArrayList<>(); long append = edits.length(); edits.seek(append); edits.write(replacement);
        for (long pos = 0; pos < size;) {
            check(cancelled); byte[] data = read(pos, 65536);
            for (int i = 0; i < data.length; i++) {
                while (matched > 0 && data[i] != pattern[matched]) matched = table[matched - 1];
                if (data[i] == pattern[matched]) matched++;
                if (matched == pattern.length) {
                    long end = pos + i + 1; result.addAll(slice(lastEnd, end - matched));
                    if (replacement.length > 0) result.add(new Piece(true, append, replacement.length));
                    lastEnd = end; matched = 0;
                    if (++count > 100000) throw new IOException("匹配超过 100000 处，请缩小搜索范围；原内容未改变");
                }
            } pos += data.length;
        }
        check(cancelled);
        if (count > 0) { result.addAll(slice(lastEnd, size)); commit(result, Math.addExact(size, Math.multiplyExact(count, replacement.length - (long)pattern.length))); }
        return count;
    }
    void writeTo(File output, BooleanSupplier cancelled) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
            byte[] buffer = new byte[131072];
            for (Piece p : pieces) {
                RandomAccessFile input = p.added ? edits : base; input.seek(p.offset);
                for (long remain = p.length; remain > 0;) {
                    check(cancelled); int count = (int)Math.min(buffer.length, remain); input.readFully(buffer, 0, count);
                    out.write(buffer, 0, count); remain -= count;
                }
            }
        }
    }
    static byte[] parse(String text, int format) throws IOException {
        try {
            if (text.length() > CLIP_LIMIT * 4) throw new IOException("输入内容过长");
            if (format == 1) return text.getBytes(StandardCharsets.UTF_8);
            if (format == 2) return android.util.Base64.decode(text, android.util.Base64.DEFAULT);
            String hex = text.replaceAll("\\s+", "");
            if ((hex.length() & 1) != 0 || !hex.matches("[0-9a-fA-F]*")) throw new IOException("十六进制需成对填写，例如 00 FF 41");
            byte[] result = new byte[hex.length() / 2];
            for (int i = 0; i < result.length; i++) result[i] = (byte)Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            return result;
        } catch (IllegalArgumentException e) { throw new IOException("内容格式无效", e); }
    }
    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2], digits = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < bytes.length; i++) { out[i * 2] = digits[(bytes[i] & 255) >>> 4]; out[i * 2 + 1] = digits[bytes[i] & 15]; }
        return new String(out);
    }
    static String format(byte[] bytes, int format) throws Exception {
        return switch (format) {
            case 1 -> new String(bytes, StandardCharsets.UTF_8);
            case 2 -> android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            case 3, 4, 5 -> hex(MessageDigest.getInstance(format == 3 ? "SHA-256" : format == 4 ? "SHA-1" : "MD5").digest(bytes));
            case 6 -> { CRC32 crc = new CRC32(); crc.update(bytes); yield String.format(Locale.ROOT, "%08X", crc.getValue()); }
            default -> hex(bytes);
        };
    }
    static String inspect(byte[] bytes, boolean bigEndian) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        StringBuilder out = new StringBuilder(hex(bytes)).append('\n');
        if (bytes.length >= 1) out.append("\n字节：").append(b.get(0)).append("\n字节（无符号）：").append(b.get(0) & 255);
        if (bytes.length >= 2) out.append("\n短整数：").append(b.getShort(0)).append("\n短整数（无符号）：").append(b.getShort(0) & 65535);
        if (bytes.length >= 4) out.append("\n整数：").append(b.getInt(0)).append("\n整数（无符号）：").append(Integer.toUnsignedString(b.getInt(0))).append("\n浮点数：").append(b.getFloat(0));
        if (bytes.length >= 8) out.append("\n长整数：").append(b.getLong(0)).append("\n长整数（无符号）：").append(Long.toUnsignedString(b.getLong(0))).append("\n双精度浮点数：").append(b.getDouble(0));
        return out.toString();
    }
    @Override public void close() throws IOException {
        try { base.close(); } finally { try { edits.close(); } finally { Files.deleteIfExists(original.toPath()); Files.deleteIfExists(additions.toPath()); } }
    }
}
