package org.example.filemanager;

import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Metadata-only logical sizes. Run on a worker; callbacks run on that same worker. */
final class FileStatistics {
    private static final int MAX_DEPTH = 256, MAX_NODES = 1_000_000, MAX_ERRORS = 100;
    record Snapshot(long bytes, long files, long directories, long unknownFiles, long skippedLinks,
                    long duplicateNodes, long errorCount, String current, boolean finished, boolean cancelled) {
        boolean complete() { return finished && !cancelled && unknownFiles == 0 && errorCount == 0; }
    }
    record Issue(String path, String message, int rootIndex) { }
    record Result(Snapshot snapshot, List<Issue> errors) { }
    enum Kind { FILE, DIRECTORY, LINK, SPECIAL }
    record Entry(FileStore.Node node, String identity, Kind kind, long bytes) { }
    record Visit(Entry entry, String relativePath, int rootIndex) { }
    private record Pending(FileStore.Node node, int depth, String relativePath, int rootIndex) { }
    private record Inspection(Pending pending, Entry entry, Exception error) { }
    interface Access extends Closeable {
        Entry inspect(FileStore.Node node) throws IOException;
        default Entry inspectConcurrently(FileStore.Node node) throws IOException { return inspect(node); }
        List<FileStore.Node> children(Entry directory) throws IOException;
        default boolean canInspectConcurrently(FileStore.Node node) { return false; }
        @Override default void close() throws IOException { }
    }
    private interface Factory { Access open() throws IOException; }

    static final class Scan {
        private static final int MAX_PARALLEL_INSPECTIONS = 4;
        private static final int INSPECTION_BATCH_SIZE = 64;
        private final int metadataWorkers;
        private final AtomicBoolean started = new AtomicBoolean();
        private final Tasks.Progress work = new Tasks.Progress();
        private final CancellationSignal signal = new CancellationSignal();
        private final List<Issue> errors = new ArrayList<>();
        private long bytes, files, directories, unknownFiles, skippedLinks, duplicateNodes, errorCount;
        private long lastUpdate;
        private int currentRoot = -1;
        private boolean finished;
        private ExecutorService inspectors;
        private String current = "准备统计";
        private Consumer<Snapshot> listener;
        private Consumer<Visit> visitor;
        private volatile Snapshot latest = new Snapshot(0, 0, 0, 0, 0, 0, 0, "准备统计", false, false);

        Scan() { this(MAX_PARALLEL_INSPECTIONS); }
        Scan(int metadataWorkers) { this.metadataWorkers = Math.max(1, Math.min(MAX_PARALLEL_INSPECTIONS, metadataWorkers)); }
        Snapshot snapshot() { return latest; }
        void cancel() {
            work.cancelled.set(true);
            // A provider cancellation hook can block. Never execute it on the UI thread.
            Tasks.CONTROL.execute(signal::cancel);
        }
        Result run(FileStore store, Collection<FileStore.Node> selected, Consumer<Snapshot> progress) throws IOException {
            return execute(() -> new StoreAccess(store, this), selected, progress);
        }
        Result run(FileStore store, Collection<FileStore.Node> selected) throws IOException {
            return run(store, selected, null);
        }
        Result run(FileStore store, Collection<FileStore.Node> selected, Consumer<Snapshot> progress,
                   Consumer<Visit> visited) throws IOException {
            visitor = visited;
            return run(store, selected, progress);
        }
        // The same traversal is exercised with failure/cycle metadata fixtures in instrumentation.
        Result run(Access access, Collection<FileStore.Node> selected, Consumer<Snapshot> progress) throws IOException {
            return execute(() -> access, selected, progress);
        }
        private Result execute(Factory factory, Collection<FileStore.Node> selected, Consumer<Snapshot> progress) throws IOException {
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("统计任务不能重复运行");
            List<FileStore.Node> roots = List.copyOf(selected);
            listener = progress; publish(true);
            try {
                work.run(() -> {
                    check();
                    try (Access access = factory.open()) {
                        try { walk(access, roots); }
                        finally { stopInspectors(); }
                    }
                    return null;
                });
            } catch (Exception error) {
                if (isCancellation(error)) work.cancelled.set(true);
                else issue(current, error);
            } finally {
                stopInspectors();
                finished = true; current = work.cancelled.get() ? "已停止统计" : "统计完成";
                publish(true); listener = null; visitor = null;
            }
            return new Result(latest, List.copyOf(errors));
        }
        private void walk(Access access, List<FileStore.Node> roots) throws IOException {
            Deque<Pending> pending = new ArrayDeque<>();
            for (int i = roots.size() - 1; i >= 0; i--)
                pending.push(new Pending(roots.get(i), 0, visitor == null ? null : roots.get(i).name, i));
            Set<String> visited = new HashSet<>();
            while (!pending.isEmpty()) {
                check();
                List<Pending> batch = new ArrayList<>();
                Pending first = pending.pop(); batch.add(first);
                if (metadataWorkers > 1 && access.canInspectConcurrently(first.node)) {
                    while (batch.size() < INSPECTION_BATCH_SIZE && !pending.isEmpty()
                            && access.canInspectConcurrently(pending.peek().node)) batch.add(pending.pop());
                }
                current = first.node.path(); currentRoot = first.rootIndex; publish(false);
                for (Inspection inspection : inspect(access, batch)) {
                    Pending next = inspection.pending; current = next.node.path(); currentRoot = next.rootIndex; publish(false);
                    try {
                        if (inspection.error != null) throw inspection.error;
                        Entry entry = inspection.entry; check();
                        if (visited.contains(entry.identity)) { duplicateNodes++; continue; }
                        if (visited.size() >= MAX_NODES) {
                            issue(current, new IOException("统计项目超过 1000000 项，结果不完整")); return;
                        }
                        visited.add(entry.identity);
                        if (visitor != null) visitor.accept(new Visit(entry, next.relativePath, next.rootIndex));
                        if (entry.kind == Kind.LINK) { skippedLinks++; continue; }
                        if (entry.kind == Kind.DIRECTORY) {
                            directories++;
                            if (next.depth >= MAX_DEPTH) throw new IOException("目录层级超过 256 层，未继续统计此目录");
                            List<FileStore.Node> children = access.children(entry); check();
                            for (int i = children.size() - 1; i >= 0; i--) {
                                FileStore.Node child = children.get(i);
                                pending.push(new Pending(child, next.depth + 1,
                                        visitor == null ? null : next.relativePath + "/" + child.name, next.rootIndex));
                            }
                        } else {
                            files++;
                            if (entry.kind == Kind.SPECIAL || entry.bytes < 0) unknownFiles++;
                            else {
                                try { bytes = Math.addExact(bytes, entry.bytes); }
                                catch (ArithmeticException overflow) {
                                    unknownFiles++; throw new IOException("总字节数超过可表示范围，结果不完整", overflow);
                                }
                            }
                            if (entry.kind == Kind.SPECIAL) throw new IOException("特殊文件不读取内容，大小未知");
                        }
                    } catch (Exception error) {
                        if (isCancellation(error)) throw new Tasks.CancelledException();
                        issue(current, error);
                    } finally { publish(false); }
                }
            }
            check();
        }
        private List<Inspection> inspect(Access access, List<Pending> batch) throws IOException {
            if (batch.size() < 2 || !access.canInspectConcurrently(batch.get(0).node)) {
                List<Inspection> result = new ArrayList<>(batch.size());
                for (Pending pending : batch) {
                    check();
                    try { result.add(new Inspection(pending, access.inspect(pending.node), null)); }
                    catch (Exception error) {
                        if (isCancellation(error)) throw new Tasks.CancelledException();
                        result.add(new Inspection(pending, null, error));
                    }
                }
                return result;
            }
            if (inspectors == null) inspectors = Executors.newFixedThreadPool(metadataWorkers, task -> {
                Thread thread = new Thread(task, "file-statistics-metadata"); thread.setDaemon(true); return thread;
            });
            List<Future<Entry>> futures = new ArrayList<>(batch.size());
            try {
                for (Pending pending : batch) futures.add(inspectors.submit(() -> {
                    if (work.cancelled.get() || Thread.currentThread().isInterrupted()) throw new Tasks.CancelledException();
                    return access.inspectConcurrently(pending.node);
                }));
                List<Inspection> result = new ArrayList<>(batch.size());
                for (int i = 0; i < futures.size(); i++) {
                    check();
                    try { result.add(new Inspection(batch.get(i), awaitInspection(futures.get(i)), null)); }
                    catch (InterruptedException error) {
                        Thread.currentThread().interrupt(); throw new Tasks.CancelledException();
                    } catch (ExecutionException error) {
                        Throwable cause = error.getCause();
                        if (isCancellation(cause)) throw new Tasks.CancelledException();
                        try { result.add(new Inspection(batch.get(i), access.inspect(batch.get(i).node), null)); }
                        catch (Exception fallbackError) {
                            if (isCancellation(fallbackError)) throw new Tasks.CancelledException();
                            result.add(new Inspection(batch.get(i), null, fallbackError));
                        }
                    }
                }
                return result;
            } finally {
                if (work.cancelled.get() || Thread.currentThread().isInterrupted())
                    for (Future<Entry> future : futures) future.cancel(true);
            }
        }
        private Entry awaitInspection(Future<Entry> future) throws InterruptedException, ExecutionException, Tasks.CancelledException {
            for (;;) {
                check();
                try { return future.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) { check(); }
            }
        }
        private void stopInspectors() {
            if (inspectors != null) { inspectors.shutdownNow(); inspectors = null; }
        }
        private boolean isCancellation(Throwable error) {
            if (work.cancelled.get() || Thread.currentThread().isInterrupted()) return true;
            for (Throwable cause = error; cause != null; cause = cause.getCause())
                if (cause instanceof Tasks.CancelledException || cause instanceof OperationCanceledException) return true;
            return false;
        }
        private void check() throws Tasks.CancelledException {
            if (work.cancelled.get() || signal.isCanceled()) throw new Tasks.CancelledException();
            Tasks.Progress.check();
        }
        private void issue(String path, Exception error) {
            errorCount++;
            if (errors.size() < MAX_ERRORS) errors.add(new Issue(bounded(path), bounded(
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()), currentRoot));
        }
        private void publish(boolean force) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (!force && now - lastUpdate < 100) return;
            lastUpdate = now;
            latest = new Snapshot(bytes, files, directories, unknownFiles, skippedLinks, duplicateNodes,
                    errorCount, current, finished, work.cancelled.get());
            if (listener != null) listener.accept(latest);
        }
    }

    private static final class StoreAccess implements Access {
        private static final String[] DOCUMENT_COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        private final FileStore store;
        private final Scan scan;
        private final FileStore.ArchiveReads archives;
        private final Map<FileStore.Node, Entry> listed = new IdentityHashMap<>();
        private final Map<String, String> archiveIds = new HashMap<>();

        StoreAccess(FileStore store, Scan scan) { this.store = store; this.scan = scan; archives = store.new ArchiveReads(); }
        @Override public Entry inspectConcurrently(FileStore.Node node) throws IOException {
            scan.check(); return localEntry(node);
        }
        @Override public Entry inspect(FileStore.Node requested) throws IOException {
            scan.check();
            Entry known = requested.root || requested.uri != null || requested.container != null
                    ? listed.remove(requested) : null;
            if (known != null) {
                if (known.kind == Kind.DIRECTORY && requested.root && isDirectSharedStoragePath(requested.path())) {
                    try {
                        Entry direct = localEntry(localNode(requested));
                        return preferVerifiedLocalEntry(known, direct);
                    } catch (IOException ignored) { }
                }
                return known;
            }
            if (requested.container != null) return archiveEntry(requested);
            if (requested.uri != null) {
                try (Cursor row = store.context().getContentResolver().query(requested.uri,
                        DOCUMENT_COLUMNS, null, null, null, scan.signal)) {
                    scan.check();
                    if (row == null || !row.moveToFirst()) throw new IOException("存储提供方没有返回文件信息");
                    return documentEntry(row, requested, requested.uri);
                } catch (RuntimeException error) { throw new IOException("文件信息读取失败：" + error.getMessage(), error); }
            }
            // Ordinary readable files need one lstat, not a su/access probe per item.
            if (!requested.root) {
                try { return localEntry(requested); }
                catch (IOException localFailure) {
                    FileStore.Node elevated = RootAccess.automatic(requested);
                    if (!elevated.root) throw localFailure;
                    return localEntry(elevated);
                }
            }
            Entry rootEntry = localEntry(requested);
            // Shared storage outside Android's filtered trees has the same app-visible
            // contents. Verify inode before switching off the per-directory ROOT channel.
            if (isDirectSharedStoragePath(requested.path())) {
                FileStore.Node local = localNode(requested);
                try {
                    Entry direct = localEntry(local);
                    return preferVerifiedLocalEntry(rootEntry, direct);
                } catch (IOException ignored) { }
            }
            return rootEntry;
        }
        private FileStore.Node localNode(FileStore.Node node) {
            return new FileStore.Node(node.name, node.file, null, node.directory, false, node.size, node.modified);
        }
        private Entry localEntry(FileStore.Node node) throws IOException {
            scan.check();
            if (node.root) {
                String[] fields = RootDirectoryReader.stat(node.path(), "%f|%s|%d|%i", false).split("\\|");
                if (fields.length != 4) throw new IOException("ROOT 文件信息不完整");
                try {
                    int mode = Integer.parseInt(fields[0], 16);
                    return new Entry(node, "inode:" + Long.parseLong(fields[2]) + ":" + Long.parseLong(fields[3]),
                            kind(mode), Long.parseLong(fields[1]));
                } catch (NumberFormatException error) { throw new IOException("ROOT 文件信息无效", error); }
            }
            try {
                StructStat stat = Os.lstat(node.path());
                return new Entry(node, "inode:" + FileStore.fileIdentity(stat), kind(stat.st_mode), stat.st_size);
            } catch (android.system.ErrnoException error) { throw new IOException(error.getMessage(), error); }
        }
        @Override public List<FileStore.Node> children(Entry directory) throws IOException {
            scan.check(); FileStore.Node node = directory.node;
            if (node.container != null) {
                List<FileStore.Node> children = store.list(node, scan.signal);
                String prefix = "archive:" + archiveId(node.container) + "!/";
                for (FileStore.Node child : children) {
                    scan.check();
                    // Some display listings coerce an unknown ZIP size to zero. Read file sizes
                    // from the shared reader instead, without opening/decompressing the payload.
                    if (child.directory) listed.put(child, new Entry(child, prefix + child.entry, Kind.DIRECTORY, 0));
                }
                return children;
            }
            if (node.uri != null) return documentChildren(node);
            requireDirectory(directory, localEntry(node));
            List<FileStore.Node> children = new ArrayList<>();
            if (node.root) {
                for (FileStore.Node child : parseRootListing(RootDirectoryReader.list(node.path(), false))) {
                    scan.check();
                    if (!child.file.getAbsoluteFile().getParentFile().toPath().normalize().equals(
                            node.file.getAbsoluteFile().toPath().normalize()))
                        throw new IOException("ROOT 目录条目超出当前目录");
                    listed.put(child, new Entry(child, "inode:" + child.identity, kind(child.unixMode), child.size));
                    children.add(child);
                }
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(node.file.toPath())) {
                    for (Path path : stream) {
                        scan.check();
                        children.add(new FileStore.Node(path.getFileName().toString(), path.toFile(), null, false, false, -1, 0));
                    }
                } catch (java.nio.file.AccessDeniedException denied) {
                    FileStore.Node elevated = RootAccess.automatic(node);
                    if (!elevated.root) throw denied;
                    Entry rootDirectory = localEntry(elevated);
                    requireDirectory(directory, rootDirectory);
                    return children(rootDirectory);
                }
            }
            requireDirectory(directory, localEntry(node));
            return children;
        }
        @Override public boolean canInspectConcurrently(FileStore.Node node) {
            return node.file != null && node.uri == null && node.container == null && !node.root;
        }
        private List<FileStore.Node> documentChildren(FileStore.Node parent) throws IOException {
            Uri children = DocumentsContract.isTreeUri(parent.uri)
                    ? DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, DocumentsContract.getDocumentId(parent.uri))
                    : DocumentsContract.buildChildDocumentsUri(parent.uri.getAuthority(), DocumentsContract.getDocumentId(parent.uri));
            List<FileStore.Node> out = new ArrayList<>();
            try (Cursor rows = store.context().getContentResolver().query(children,
                    DOCUMENT_COLUMNS, null, null, null, scan.signal)) {
                if (rows == null) throw new IOException("存储提供方没有返回目录");
                while (rows.moveToNext()) {
                    scan.check();
                    try {
                        String id = string(rows, DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                        if (id == null || id.isEmpty()) throw new IOException("存储提供方返回了无效文件标识");
                        Uri uri = DocumentsContract.isTreeUri(parent.uri) ? DocumentsContract.buildDocumentUriUsingTree(parent.uri, id)
                                : DocumentsContract.buildDocumentUri(parent.uri.getAuthority(), id);
                        Entry entry = documentEntry(rows, parent, uri);
                        listed.put(entry.node, entry); out.add(entry.node);
                    } catch (IOException | RuntimeException error) {
                        if (scan.isCancellation(error)) throw new Tasks.CancelledException();
                        String name = string(rows, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                        scan.issue(parent.path() + "/" + (name == null ? "未知条目" : name), error);
                    }
                }
            } catch (RuntimeException error) { throw new IOException("目录读取失败：" + error.getMessage(), error); }
            return out;
        }
        private Entry archiveEntry(FileStore.Node node) throws IOException {
            String identity = "archive:" + archiveId(node.container) + "!/" + node.entry;
            if (!node.directory) return new Entry(node, identity, Kind.FILE, archives.reader(node.container).size(node.entry));
            File file = store.archiveView(node.container);
            if (!node.entry.isEmpty()) {
                String trimmed = node.entry.endsWith("/") ? node.entry.substring(0, node.entry.length() - 1) : node.entry;
                String parent = trimmed.substring(0, trimmed.lastIndexOf('/') + 1);
                boolean found = false;
                for (ArchiveOps.Item item : ArchiveOps.list(file, parent))
                    if (item.directory() && item.path().equals(trimmed + "/")) { found = true; break; }
                if (!found) throw new IOException("归档目录不存在");
            }
            return new Entry(node, identity, Kind.DIRECTORY, 0);
        }
        private String archiveId(FileStore.Node source) throws IOException {
            String known = archiveIds.get(source.key()); if (known != null) return known;
            String identity;
            if (source.container != null) identity = "archive:" + archiveId(source.container) + "!/" + source.entry;
            else if (source.uri != null) identity = documentIdentity(source.uri);
            else {
                FileStore.Node node = RootAccess.automatic(source);
                if (node.root) identity = "inode:" + RootDirectoryReader.stat(node.path(), "%d:%i", true);
                else try { identity = "inode:" + FileStore.fileIdentity(Os.stat(node.path())); }
                catch (android.system.ErrnoException error) { throw new IOException(error.getMessage(), error); }
            }
            archiveIds.put(source.key(), identity); return identity;
        }
        @Override public void close() throws IOException { archives.close(); listed.clear(); }
    }

    static Entry documentEntry(Cursor row, FileStore.Node original, Uri uri) throws IOException {
        String name = string(row, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        String mime = string(row, DocumentsContract.Document.COLUMN_MIME_TYPE);
        if (mime == null || mime.isEmpty()) throw new IOException("存储提供方未提供文件类型");
        boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
        long size = number(row, DocumentsContract.Document.COLUMN_SIZE, -1);
        FileStore.Node node = new FileStore.Node(name == null ? original.name : name, null, uri, directory, false,
                size, number(row, DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0));
        return new Entry(node, documentIdentity(uri), directory ? Kind.DIRECTORY : Kind.FILE, size);
    }
    static boolean isDirectSharedStoragePath(String path) {
        if (path == null) return false;
        String normalized;
        try { normalized = Paths.get(path).toAbsolutePath().normalize().toString(); }
        catch (InvalidPathException error) { return false; }
        String root = "/storage/emulated/0";
        return normalized.startsWith(root + "/") && !normalized.equals(root + "/Android")
                && !normalized.startsWith(root + "/Android/");
    }
    static Entry preferVerifiedLocalEntry(Entry privileged, Entry local) {
        return privileged.identity.equals(local.identity) && privileged.kind == local.kind ? local : privileged;
    }
    static List<FileStore.Node> parseRootListing(String output) throws IOException {
        // Display listings tolerate unrelated lines; a total must not silently omit them.
        // Newlines in names cannot be represented by the existing line-based ROOT reader.
        for (String line : output.split("\n", -1)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\\|", 6);
            if (fields.length != 6 || fields[5].isEmpty() || !new File(fields[5]).isAbsolute())
                throw new IOException("ROOT 目录含无法完整解析的条目，可能含换行文件名，统计不完整");
        }
        return FileStore.parseRootListing(output);
    }
    private static String documentIdentity(Uri uri) {
        try { return "document:" + uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri); }
        catch (IllegalArgumentException ignored) { return "uri:" + uri.normalizeScheme(); }
    }
    private static String string(Cursor row, String name) {
        int column = row.getColumnIndex(name); return column < 0 || row.isNull(column) ? null : row.getString(column);
    }
    private static long number(Cursor row, String name, long fallback) {
        int column = row.getColumnIndex(name); return column < 0 || row.isNull(column) ? fallback : row.getLong(column);
    }
    private static Kind kind(int mode) {
        return OsConstants.S_ISLNK(mode) ? Kind.LINK : OsConstants.S_ISDIR(mode) ? Kind.DIRECTORY
                : OsConstants.S_ISREG(mode) ? Kind.FILE : Kind.SPECIAL;
    }
    private static void requireDirectory(Entry expected, Entry actual) throws IOException {
        if (actual.kind != Kind.DIRECTORY || !actual.identity.equals(expected.identity))
            throw new IOException("统计期间目录已被替换，请重新统计");
    }
    private static String bounded(String value) { return value.length() <= 2048 ? value : value.substring(0, 2048) + "…"; }
    private FileStatistics() { }
}
