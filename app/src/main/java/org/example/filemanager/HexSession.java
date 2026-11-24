package org.example.filemanager;

import android.content.Context;
import android.os.*;
import androidx.lifecycle.ViewModel;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HexSession extends ViewModel {
    static final int PAGE = 256;
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final AtomicBoolean cancelled = new AtomicBoolean();
    final Handler main = new Handler(Looper.getMainLooper());
    HexDocument document;
    FileStore store;
    FileStore.Node source;
    String baseline, message = "读取中…";
    byte[] page = new byte[0], clipboard, query, replacement = new byte[0];
    long cursor, anchor = -1, pageStart;
    boolean busy, saving, closed, saved;
    int nibble = -1;
    Exception error;
    Runnable listener;
    public HexSession() {}
    interface Work { void run() throws Exception; }
    void notifyUi() { if (listener != null) listener.run(); }
    void run(Work work) {
        if (busy || closed) return;
        busy = true; error = null; cancelled.set(false); notifyUi();
        worker.execute(() -> {
            Exception failure = null;
            try { work.run(); if (document != null) readPage(); }
            catch (Exception e) { failure = e; }
            Exception result = failure;
            main.post(() -> { if (closed) return; busy = false; saving = false; error = result; notifyUi(); });
        });
    }
    void open(Context context, String key) {
        Context app = context.getApplicationContext();
        run(() -> {
            store = new FileStore(app); source = store.fromKey(key);
            if (source.directory) throw new IOException("请选择文件");
            baseline = store.fingerprint(source); File copy = File.createTempFile("hex-source-", ".tmp", app.getCacheDir());
            boolean owned = false;
            try {
                try (InputStream in = store.read(source); OutputStream out = new FileOutputStream(copy)) {
                    byte[] data = new byte[131072]; int count;
                    while ((count = in.read(data)) != -1) { check(); out.write(data, 0, count); }
                }
                if (!baseline.equals(store.fingerprint(source))) throw new IOException("读取期间原文件已变化，请重新打开");
                document = new HexDocument(copy, app.getCacheDir()); owned = true; message = source.name;
            } finally { if (!owned) Files.deleteIfExists(copy.toPath()); }
        });
    }
    void readPage() throws IOException {
        cursor = Math.min(cursor, document.size());
        if (anchor > document.size()) anchor = -1;
        pageStart = cursor / PAGE * PAGE; page = document.read(pageStart, PAGE);
    }
    void check() throws InterruptedIOException { if (cancelled.get()) throw new InterruptedIOException("已停止"); }
    long start() { return anchor < 0 ? cursor : Math.min(cursor, anchor); }
    long selectedLength() { return anchor < 0 ? 0 : Math.min(document.size(), Math.max(cursor, anchor) + 1) - start(); }
    void save(Context context) {
        if (busy || document == null) return;
        saving = true; File cache = context.getCacheDir();
        run(() -> {
            File output = File.createTempFile("hex-save-", ".tmp", cache);
            try { document.writeTo(output, cancelled::get);
                if (!baseline.equals(store.fingerprint(source))) throw new IOException("原文件已在外部改变，请重新打开后合并修改");
                EditorBackups.beforeSave(store, source);
                baseline = store.write(source, output, baseline); document.markSaved(); saved = true; message = "已保存"; }
            finally { Files.deleteIfExists(output.toPath()); }
        });
    }
    void move(long position, boolean selecting) {
        if (busy || document == null) return;
        if (selecting && anchor < 0) anchor = cursor;
        if (!selecting) anchor = -1;
        cursor = Math.max(0, Math.min(position, document.size())); nibble = -1;
        if (cursor / PAGE * PAGE != pageStart) run(() -> {}); else notifyUi();
    }
    void replace(byte[] data, boolean insert) {
        run(() -> {
            long at = start(), count = selectedLength();
            if (count == 0 && !insert) count = Math.min(data.length, document.size() - at);
            document.replace(at, count, data); cursor = at + data.length; anchor = -1; nibble = -1;
        });
    }
    void digit(int digit) {
        if (busy || document == null) return;
        if (nibble < 0) { nibble = digit; notifyUi(); }
        else { int value = nibble * 16 + digit; replace(new byte[]{(byte)value}, cursor == document.size()); }
    }
    void find(boolean forward) {
        if (query == null) return;
        run(() -> {
            long position = forward ? document.find(query, cursor + 1, document.size(), false, cancelled::get)
                    : document.find(query, 0, cursor + query.length - 1, true, cancelled::get);
            if (position < 0) position = forward ? document.find(query, 0, cursor + query.length, false, cancelled::get)
                    : document.find(query, cursor + 1, document.size(), true, cancelled::get);
            if (position < 0) message = "未找到匹配内容";
            else { cursor = position; anchor = position + query.length - 1; message = "匹配位置 0x" + Long.toHexString(position).toUpperCase(java.util.Locale.ROOT); }
        });
    }
    @Override protected void onCleared() {
        closed = true; listener = null; if (!saving) cancelled.set(true);
        worker.execute(() -> { if (document != null) try { document.close(); } catch (IOException ignored) {} });
        worker.shutdown();
    }
}
