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
    private static final int LITERAL_BLOCK = 32 * 1024;
    static final int MAX_MULTILINE_CHARS = 8 * 1024 * 1024;
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
    private String error, notice;
    private long lastPublish;
    /** Where a match sits: {@code line} is 1-based for content hits and 0 for name hits; start/end index {@code text}. */
    record Hit(int line, String text, int start, int end) {}
    record Snapshot(List<FileStore.Node> matches, int scanned, int denied, boolean finished,
                    boolean cancelled, String error, String notice) { }
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
        return new Snapshot(new ArrayList<>(matches), scanned, denied, finished, cancelled.get(), error, notice);
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
        String literal = literalText(pattern);
        if (sample.length < ENCODING_SAMPLE) {
            String decoded = TextCodec.decode(sample, null).text();
            return literal == null ? contentHit(pattern, decoded) : findLiteralStream(new StringReader(decoded), pattern, literal);
        }
        TextCodec.Decoded encoding = TextCodec.decodePrefix(sample, null);
        buffered.reset();
        if (buffered.skip(encoding.bom().length) != encoding.bom().length) throw new EOFException("无法读取文本编码标记");
        Reader reader = new InputStreamReader(buffered, encoding.charset());
        if (literal != null) return findLiteralStream(reader, pattern, literal);
        // Other line-local regexes retain a visible line-size bound. Expressions which
        // can cross a line keep document semantics instead of changing at 64 KiB.
        if (!lineLocal(pattern)) {
            StringBuilder document = new StringBuilder();
            char[] blockChars = new char[32 * 1024];
            for (int count; (count = reader.read(blockChars)) != -1;) {
                Tasks.Progress.check();
                if (document.length() + count > MAX_MULTILINE_CHARS)
                    throw new ContentLimitException();
                document.append(blockChars, 0, count);
            }
            return contentHit(pattern, document.toString());
        }
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
                    if (line.length() == MAX_LINE) throw new ContentLimitException(
                            "部分文件单行超过 1 Mi 字符，当前正则无法继续匹配，已跳过；可改用普通关键词搜索完整文件");
                    line.append(c);
                }
            }
        }
        return line.length() == 0 && lineNumber > 1 ? null : lineHit(matcher, line.toString(), lineNumber);
    }
    /** Pure literal concatenations have a finite overlap, including Pattern.quote()'s escaped \E form. */
    private static String literalText(Pattern pattern) {
        if ((pattern.flags() & Pattern.CANON_EQ) != 0) return null;
        String expression = pattern.pattern();
        if ((pattern.flags() & Pattern.LITERAL) != 0) return expression;
        if ((pattern.flags() & Pattern.COMMENTS) != 0) return null;
        StringBuilder text = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < expression.length() && expression.charAt(i + 1) == 'E') {
                    quoted = false; i++;
                } else text.append(c);
            } else if (c == '\\') {
                if (++i == expression.length()) return null;
                char escaped = expression.charAt(i);
                if (escaped == 'Q') quoted = true;
                else if (escaped == 'n') text.append('\n');
                else if (escaped == 'r') text.append('\r');
                else if (escaped == 't') text.append('\t');
                else if (escaped == 'f') text.append('\f');
                else if (escaped == 'a') text.append((char) 7);
                else if (escaped == 'e') text.append((char) 27);
                else if (Character.isLetterOrDigit(escaped)) return null;
                else text.append(escaped);
            } else {
                if (".^$*+?()[]{}|".indexOf(c) >= 0) return null;
                text.append(c);
            }
        }
        return text.toString();
    }
    private static Hit findLiteralStream(Reader reader, Pattern original, String literal) throws IOException {
        Pattern pattern = Pattern.compile(literal, original.flags() | Pattern.LITERAL);
        StringBuilder window = new StringBuilder();
        char[] block = new char[LITERAL_BLOCK];
        // Keep the entire possible boundary match plus enough leading context for its result row.
        int overlap = literal.length() > Integer.MAX_VALUE - 66 ? Integer.MAX_VALUE : literal.length() + 66;
        LinePosition base = new LinePosition();
        for (;;) {
            Tasks.Progress.check();
            int count = reader.read(block);
            boolean end = count < 0;
            if (count > 0) window.append(block, 0, count);
            String text = window.toString();
            try {
                var match = pattern.matcher(new LiteralInput(text));
                if (!end && !text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1)))
                    match.region(0, text.length() - 1);
                if (match.find()) {
                    Tasks.Progress.check();
                    return literalHit(text, match.start(), match.end(), base, end);
                }
            } catch (LiteralStopped stopped) { throw stopped.cause; }
            if (end) return null;
            int discard = window.length() - overlap;
            if (discard > 0) { base.advance(text, discard); window.delete(0, discard); }
        }
    }
    /** State at the start of a retained window; a CR is resolved with the following character. */
    private static final class LinePosition {
        int line = 1;
        boolean afterCr, truncated;
        void advance(String text, int until) {
            for (int i = 0; i < until; i++) {
                char c = text.charAt(i);
                if (afterCr && c != '\n') { line++; truncated = false; }
                if (c == '\n') { line++; afterCr = false; truncated = false; }
                else if (c == '\r') afterCr = true;
                else { afterCr = false; truncated = true; }
            }
        }
    }
    private static Hit literalHit(String text, int start, int end, LinePosition base, boolean complete) {
        int line = base.line, lineStart = 0;
        boolean afterCr = base.afterCr, truncated = base.truncated;
        for (int i = 0; i < start; i++) {
            char c = text.charAt(i);
            if (afterCr && c != '\n') { line++; lineStart = i; truncated = false; }
            if (c == '\n') { line++; lineStart = i + 1; afterCr = false; truncated = false; }
            else afterCr = c == '\r';
        }
        if (afterCr && (start == text.length() || text.charAt(start) != '\n')) {
            line++; lineStart = start; truncated = false;
        }
        int lineEnd = lineStart;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\r' && text.charAt(lineEnd) != '\n') lineEnd++;
        Hit hit = snippet(text, line, lineStart, lineEnd, Math.min(start, lineEnd), end);
        boolean prefix = truncated && !hit.text().startsWith("…");
        String suffix = !complete && lineEnd == text.length() && !hit.text().endsWith("…") ? "…" : "";
        return new Hit(line, (prefix ? "…" : "") + hit.text() + suffix,
                hit.start() + (prefix ? 1 : 0), hit.end() + (prefix ? 1 : 0));
    }
    private static final class LiteralStopped extends RuntimeException {
        final Tasks.CancelledException cause;
        LiteralStopped(Tasks.CancelledException cause) { this.cause = cause; }
    }
    private static final class LiteralInput implements CharSequence {
        private final String text;
        private int checks;
        LiteralInput(String text) { this.text = text; }
        public int length() { return text.length(); }
        public char charAt(int index) {
            if ((++checks & 1023) == 0) {
                try { Tasks.Progress.check(); } catch (Tasks.CancelledException stopped) { throw new LiteralStopped(stopped); }
            }
            return text.charAt(index);
        }
        public CharSequence subSequence(int start, int end) { return new LiteralInput(text.substring(start, end)); }
        public String toString() { return text; }
    }
    /** A conservative classification: unknown constructs retain full-document matching. */
    private static boolean lineLocal(Pattern pattern) {
        String expression = pattern.pattern();
        if ((pattern.flags() & Pattern.LITERAL) != 0)
            return expression.indexOf('\r') < 0 && expression.indexOf('\n') < 0;
        if ((pattern.flags() & (Pattern.DOTALL | Pattern.COMMENTS | Pattern.UNIX_LINES)) != 0) return false;
        boolean quoted = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\r' || c == '\n') return false;
            if (c == '\\' && i + 1 < expression.length()) {
                char escaped = expression.charAt(++i);
                if (escaped == '\r' || escaped == '\n') return false;
                if (quoted) { if (escaped == 'E') quoted = false; else i--; continue; }
                if (escaped == 'Q') { quoted = true; continue; }
                if (escaped != 'd' && escaped != 'w' && escaped != 'b' && escaped != 'B' && escaped != 't'
                        && Character.isLetterOrDigit(escaped)) return false;
                continue;
            }
            if (!quoted && (c == '[' || c == '^' || c == '$' || c == '(' && i + 1 < expression.length()
                    && expression.charAt(i + 1) == '?')) return false;
        }
        return true;
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
                        } catch (ContentLimitException overLimit) {
                            check(); synchronized (this) { denied++; notice = overLimit.getMessage(); }
                        } catch (MatchBudgetExceeded exceeded) {
                            check(); synchronized (this) { denied++; notice = "部分文件的正则匹配超过时间或复杂度上限，已跳过；可简化表达式或改用普通关键词"; }
                        } catch (IOException | SecurityException deniedFile) { check(); synchronized (this) { denied++; } }
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
        int line = 1, lineStart = 0;
        for (int i = 0; i < m.start(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    if (i + 1 == m.start()) break;
                    i++;
                }
                line++; lineStart = i + 1;
            } else if (c == '\n') { line++; lineStart = i + 1; }
        }
        int lineEnd = lineStart;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\r' && text.charAt(lineEnd) != '\n') lineEnd++;
        return snippet(text, line, lineStart, lineEnd, Math.min(m.start(), lineEnd), m.end());
    }
    private static final class ContentLimitException extends IOException {
        ContentLimitException() { super("部分文件超过跨行或复杂正则的 8 Mi 字符上限，已跳过；可改用普通关键词继续搜索"); }
        ContentLimitException(String message) { super(message); }
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
            MatchBudget budget = new MatchBudget(text.length(), linear == null);
            budget.check();
            BoundedText input = new BoundedText(text, 0, text.length(), budget);
            if (linear != null) {
                var match = linear.matcher(input);
                boolean found = match.find(start); budget.check();
                return found ? new Match(match.start(), match.end()) : null;
            }
            try {
                var match = advanced.matcher(input);
                boolean found = match.find(start); budget.check();
                return found ? new Match(match.start(), match.end()) : null;
            } catch (StackOverflowError deep) { throw new MatchBudgetExceeded(); }
        }
    }
    static final class MatchBudgetExceeded extends RuntimeException {
        MatchBudgetExceeded() { super("正则匹配超时或已停止"); }
    }
    private static final class MatchBudget {
        final boolean bounded;
        final long deadline, maximumReads;
        long reads;
        MatchBudget(int length, boolean bounded) {
            this.bounded = bounded;
            // RE2 cannot backtrack exponentially. Its full-document pass needs cancellation
            // checks, not a fixed character/time cap which rejects normal large files.
            // The compatible engine still has finite work and time bounds, scaled for
            // the linear prefix scan needed by ordinary lookbehind expressions.
            deadline = SystemClock.uptimeMillis() + Math.min(8000L, 1000L + (length + 65535L) / 65536L * 250L);
            maximumReads = Math.min(64_000_000L, Math.max(4_000_000L, (long) length * 32L));
        }
        void read() { if ((++reads & 1023) == 0) check(); }
        void check() {
            try { Tasks.Progress.check(); } catch (Tasks.CancelledException cancelled) { throw new MatchBudgetExceeded(); }
            if (bounded && (reads > maximumReads || SystemClock.uptimeMillis() > deadline))
                throw new MatchBudgetExceeded();
        }
    }
    private record BoundedText(String text, int from, int to, MatchBudget budget) implements CharSequence {
        @Override public int length() { return to - from; }
        @Override public char charAt(int index) {
            Objects.checkIndex(index, length());
            budget.read();
            return text.charAt(from + index);
        }
        @Override public CharSequence subSequence(int start, int end) {
            budget.check();
            Objects.checkFromToIndex(start, end, length());
            return new BoundedText(text, from + start, from + end, budget);
        }
        @Override public String toString() { budget.check(); return text.substring(from, to); }
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
