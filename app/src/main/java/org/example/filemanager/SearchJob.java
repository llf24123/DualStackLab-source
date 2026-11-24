package org.example.filemanager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

final class SearchJob {
    static final int LIMIT = 2000;
    private static final int ENCODING_SAMPLE = 64 * 1024;
    private static final int MAX_LINE = 1024 * 1024;
    private static final ExecutorService WORKER = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "file-search"); thread.setDaemon(true); return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile SearchJob active;
    private final List<FileStore.Node> matches = new ArrayList<>();
    private final Map<String, Hit> hits = new HashMap<>();
    private final Set<Runnable> listeners = new HashSet<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final android.os.CancellationSignal signal = new android.os.CancellationSignal();
    private final AtomicReference<InputStream> reading = new AtomicReference<>();
    private FutureTask<Void> task;
    final String query;
    final FileStore.Node root;
    final boolean contents, hidden;
    final Pattern pattern;
    private int scanned, denied;
    private boolean finished;
    private String error;
    private long lastPublish;
    /** Where a match sits: {@code line} is 1-based for content hits and 0 for name hits; start/end index {@code text}. */
    record Hit(int line, String text, int start, int end) {}
    record Snapshot(List<FileStore.Node> matches, int scanned, int denied, boolean finished,
                    boolean cancelled, String error) { }
    private SearchJob(String query, FileStore.Node root, Pattern pattern, boolean contents, boolean hidden) {
        this.query = query; this.root = root; this.pattern = pattern; this.contents = contents; this.hidden = hidden;
    }
    static SearchJob active() { return active; }
    static SearchJob start(Context context, FileStore.Node root, String query, Pattern pattern, boolean contents) {
        return start(context, root, query, pattern, contents, true);
    }
    static synchronized SearchJob start(Context context, FileStore.Node root, String query, Pattern pattern, boolean contents, boolean hidden) {
        if (active != null) active.cancel();
        SearchJob job = new SearchJob(query, root, pattern, contents, hidden); active = job;
        FileStore store = new FileStore(context.getApplicationContext());
        job.task = new FutureTask<>(() -> { job.search(store, root, pattern, contents, hidden); return null; });
        WORKER.execute(job.task);
        return job;
    }
    synchronized Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(matches), scanned, denied, finished, cancelled.get(), error);
    }
    synchronized Hit hit(FileStore.Node node) { return hits.get(node.key()); }
    synchronized void listen(Runnable listener) { listeners.add(listener); }
    synchronized void unlisten(Runnable listener) { listeners.remove(listener); }
    void cancel() {
        synchronized (this) {
            if (finished || !cancelled.compareAndSet(false, true)) return;
            // Results are frozen immediately, even if an external provider is slow to acknowledge cancellation.
            finished = true;
        }
        if (task != null) task.cancel(true);
        InputStream input = reading.getAndSet(null);
        // Provider IPC and close() must never block the UI, nor delay each other.
        Tasks.CONTROL.execute(signal::cancel);
        if (input != null) Tasks.CONTROL.execute(() -> close(input));
        publish(true);
    }
    private boolean stopped() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
    private void check() throws Tasks.CancelledException { if (stopped()) throw new Tasks.CancelledException(); }
    private static void close(InputStream input) { try { input.close(); } catch (IOException ignored) { } }
    private Hit readContent(FileStore store, FileStore.Node node, Pattern pattern) throws IOException {
        check();
        InputStream input = store.readForSearch(node, signal);
        reading.set(input);
        try {
            check();
            return findStream(input, pattern);
        } finally { if (reading.compareAndSet(input, null)) close(input); }
    }
    static Hit findStream(InputStream input, Pattern pattern) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input, ENCODING_SAMPLE + 1);
        buffered.mark(ENCODING_SAMPLE + 1);
        ByteArrayOutputStream sampleBytes = new ByteArrayOutputStream(ENCODING_SAMPLE);
        byte[] block = new byte[8192];
        while (sampleBytes.size() < ENCODING_SAMPLE) {
            Tasks.Progress.check();
            int count = buffered.read(block, 0, Math.min(block.length, ENCODING_SAMPLE - sampleBytes.size()));
            if (count < 0) break;
            if (count > 0) sampleBytes.write(block, 0, count);
        }
        byte[] sample = sampleBytes.toByteArray();
        if (sample.length < ENCODING_SAMPLE) return contentHit(pattern, TextCodec.decode(sample, null).text());
        TextCodec.Decoded encoding = TextCodec.decodePrefix(sample, null);
        buffered.reset();
        if (buffered.skip(encoding.bom().length) != encoding.bom().length) throw new EOFException("无法读取文本编码标记");
        Reader reader = new InputStreamReader(buffered, encoding.charset());
        SearchPattern matcher = new SearchPattern(pattern);
        char[] chars = new char[32 * 1024];
        StringBuilder line = new StringBuilder();
        int lineNumber = 1;
        boolean afterCr = false;
        for (int size; (size = reader.read(chars)) >= 0;) {
            Tasks.Progress.check();
            for (int i = 0; i < size; i++) {
                char c = chars[i];
                if (c == '\n' && afterCr) { afterCr = false; continue; }
                afterCr = c == '\r';
                if (c == '\r' || c == '\n') {
                    Hit hit = lineHit(matcher, line.toString(), lineNumber++);
                    if (hit != null) return hit;
                    line.setLength(0);
                } else {
                    if (line.length() == MAX_LINE) throw new IOException("文本单行超过 1048576 字符");
                    line.append(c);
                }
            }
        }
        return line.length() == 0 && lineNumber > 1 ? null : lineHit(matcher, line.toString(), lineNumber);
    }
    private void search(FileStore store, FileStore.Node root, Pattern pattern, boolean contents, boolean hidden) {
        ArrayDeque<FileStore.Node> queue = new ArrayDeque<>(); queue.add(root);
        Set<String> visited = new HashSet<>();
        try {
            SearchPattern matcher = new SearchPattern(pattern);
            while (!queue.isEmpty() && !stopped()) {
                FileStore.Node directory = queue.remove();
                List<FileStore.Node> children;
                try {
                    String key = directory.file != null ? directory.file.getCanonicalPath() : directory.key();
                    if (!visited.add(key)) continue;
                    children = store.list(directory, signal);
                } catch (IOException | SecurityException deniedDirectory) {
                    check();
                    synchronized (this) { denied++; }
                    publish(false); continue;
                }
                for (FileStore.Node node : children) {
                    check();
                    if (!hidden && node.name.startsWith(".")) continue;
                    if (node.directory) queue.add(node);
                    Hit hit = null;
                    if (!contents) {
                        try {
                            Match m = matcher.find(node.name);
                            if (m != null) hit = new Hit(0, node.name, m.start(), m.end());
                        } catch (MatchBudgetExceeded exceeded) { check(); synchronized (this) { denied++; } }
                    } else if (!node.directory && MainActivity.isText(node.name)) {
                        try {
                            hit = readContent(store, node, pattern);
                        } catch (IOException | SecurityException | MatchBudgetExceeded deniedFile) { check(); synchronized (this) { denied++; } }
                    }
                    check();
                    synchronized (this) {
                        if (cancelled.get()) break;
                        scanned++;
                        if (hit != null) { matches.add(node); hits.put(node.key(), hit); }
                        if (matches.size() >= LIMIT) { queue.clear(); break; }
                    }
                    publish(false);
                }
            }
        } catch (Exception failure) { synchronized (this) { if (!cancelled.get()) error = failure.getMessage(); } }
        finally { synchronized (this) { finished = true; } publish(true); }
    }
    /** The first matching line, trimmed around the match so it fits one result row. */
    static Hit contentHit(Pattern pattern, String text) {
        Match m = new SearchPattern(pattern).find(text);
        if (m == null) return null;
        int lineStart = text.lastIndexOf('\n', m.start() - 1) + 1;
        int lineEnd = text.indexOf('\n', m.start());
        if (lineEnd < 0) lineEnd = text.length();
        int line = 1;
        for (int i = 0; i < lineStart; i++) if (text.charAt(i) == '\n') line++;
        return snippet(text, line, lineStart, lineEnd, m.start(), m.end());
    }
    private static Hit lineHit(SearchPattern pattern, String line, int lineNumber) {
        Match match = pattern.find(line);
        return match == null ? null : snippet(line, lineNumber, 0, line.length(), match.start(), match.end());
    }
    record Match(int start, int end) { }
    static final class SearchPattern {
        private final com.google.re2j.Pattern linear;
        private final com.florianingerl.util.regex.Pattern advanced;
        SearchPattern(Pattern pattern) {
            int flags = pattern.flags();
            com.google.re2j.Pattern fast = null;
            int safeFlags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE | Pattern.DOTALL;
            if ((flags & ~safeFlags) == 0) {
                int reFlags = 0;
                if ((flags & Pattern.CASE_INSENSITIVE) != 0) reFlags |= com.google.re2j.Pattern.CASE_INSENSITIVE;
                if ((flags & Pattern.MULTILINE) != 0) reFlags |= com.google.re2j.Pattern.MULTILINE;
                if ((flags & Pattern.DOTALL) != 0) reFlags |= com.google.re2j.Pattern.DOTALL;
                try { fast = com.google.re2j.Pattern.compile(pattern.pattern(), reFlags); }
                catch (com.google.re2j.PatternSyntaxException unsupported) { }
            }
            linear = fast;
            if (linear == null) {
                RenameRegex.checkRepetitions(pattern.pattern());
                try { advanced = com.florianingerl.util.regex.Pattern.compile(pattern.pattern(), flags); }
                catch (StackOverflowError deep) { throw new MatchBudgetExceeded(); }
            } else advanced = null;
        }
        Match find(String text) {
            return find(text, 0);
        }
        Match find(String text, int start) {
            if (Thread.currentThread().isInterrupted()) throw new MatchBudgetExceeded();
            BoundedText input = new BoundedText(text, 0, text.length(), new MatchBudget());
            if (linear != null) {
                var match = linear.matcher(input);
                return match.find(start) ? new Match(match.start(), match.end()) : null;
            }
            try {
                var match = advanced.matcher(input);
                return match.find(start) ? new Match(match.start(), match.end()) : null;
            } catch (StackOverflowError deep) { throw new MatchBudgetExceeded(); }
        }
    }
    static final class MatchBudgetExceeded extends RuntimeException {
        MatchBudgetExceeded() { super("正则匹配超时或已停止"); }
    }
    private static final class MatchBudget {
        final long deadline = SystemClock.uptimeMillis() + 250;
        int checks;
    }
    private record BoundedText(String text, int from, int to, MatchBudget budget) implements CharSequence {
        @Override public int length() { return to - from; }
        @Override public char charAt(int index) {
            Objects.checkIndex(index, length());
            if ((++budget.checks & 1023) == 0 && (budget.checks > 4_000_000
                    || Thread.currentThread().isInterrupted() || SystemClock.uptimeMillis() > budget.deadline))
                throw new MatchBudgetExceeded();
            return text.charAt(from + index);
        }
        @Override public CharSequence subSequence(int start, int end) {
            Objects.checkFromToIndex(start, end, length());
            return new BoundedText(text, from + start, from + end, budget);
        }
        @Override public String toString() { return text.substring(from, to); }
    }
    private static Hit snippet(String text, int line, int lineStart, int lineEnd, int matchStart, int matchEnd) {
        int from = Math.max(lineStart, matchStart - 40);
        int to = Math.min(lineEnd, Math.min(from + 160, Math.max(matchEnd, matchStart + 1) + 60));
        String prefix = from > lineStart ? "…" : "", suffix = to < lineEnd ? "…" : "";
        StringBuilder snippet = new StringBuilder(prefix);
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            snippet.append(c < 32 || c == 0x7f ? ' ' : c);
        }
        snippet.append(suffix);
        int start = prefix.length() + (matchStart - from);
        int end = Math.min(snippet.length() - suffix.length(), prefix.length() + (Math.min(matchEnd, to) - from));
        return new Hit(line, snippet.toString(), start, Math.max(start, end));
    }
    private void publish(boolean immediate) {
        long now = SystemClock.uptimeMillis();
        if (!immediate && now - lastPublish < 100) return;
        lastPublish = now;
        if (!pending.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            pending.set(false);
            List<Runnable> observers;
            synchronized (this) { observers = new ArrayList<>(listeners); }
            for (Runnable observer : observers) observer.run();
        });
    }
}
