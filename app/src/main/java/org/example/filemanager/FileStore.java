package org.example.filemanager;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class FileStore {
    enum ConflictPolicy { KEEP_BOTH, SKIP, OVERWRITE }
    static final class Node {
        final String name;
        final File file;
        final Uri uri;
        final boolean directory, root;
        final long size, modified;
        final Node container;
        final String entry;
        volatile String identity, parentIdentity, directoryIdentity;
        boolean identityFollowsLinks = true;
        int unixMode=-1;
        private String cachedKey;
        Node(String name, File file, Uri uri, boolean directory, boolean root, long size, long modified) {
            this(name, file, uri, directory, root, size, modified, null, null);
        }
        Node(String name, File file, Uri uri, boolean directory, boolean root, long size, long modified,
             Node container, String entry) {
            this.name = name; this.file = file; this.uri = uri; this.directory = directory;
            this.root = root; this.size = size; this.modified = modified;
            this.container = container; this.entry = entry;
        }
        String key() {
            if (cachedKey != null) return cachedKey;
            if (container != null) return cachedKey = new Uri.Builder().scheme("zip").authority("entry")
                    .appendQueryParameter("source", container.key()).appendQueryParameter("entry", entry)
                    .appendQueryParameter("dir", directory ? "1" : "0").build().toString();
            return cachedKey = uri != null ? uri.toString() : (root ? "root:" : "") + file.getAbsolutePath();
        }
        String path() { return container != null ? container.path() + "!/" + entry : file != null ? file.getAbsolutePath()
                : DataDocumentsProvider.owns(uri) ? DataDocumentsProvider.path(uri) : name; }
        void put(Intent i) {
            if (container != null) { i.putExtra("archiveKey", key()).putExtra("name", name); return; }
            i.putExtra("name", name).putExtra("root", root);
            if (file != null) i.putExtra("path", file.getAbsolutePath());
            if (uri != null) i.putExtra("uri", uri.toString());
        }
    }
    private final Context context;
    private final Map<String, File> archiveSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<ArchiveReads> copyingArchives = new ThreadLocal<>();
    private final ThreadLocal<Boolean> freshArchiveReads = new ThreadLocal<>();
    FileStore(Context c) { context = c.getApplicationContext(); }
    Context context() { return context; }
    static Node local(File f) { return local(f, false); }
    static Node local(File f, boolean root) {
        Node node;
        try {
            java.nio.file.attribute.BasicFileAttributes attributes = Files.readAttributes(f.toPath(),
                    java.nio.file.attribute.BasicFileAttributes.class);
            node = new Node(f.getName().isEmpty() ? "/" : f.getName(), f, null, attributes.isDirectory(), root,
                    attributes.size(), attributes.lastModifiedTime().toMillis());
        } catch (IOException ignored) {
            node = new Node(f.getName().isEmpty() ? "/" : f.getName(), f, null,
                    f.isDirectory(), root, f.length(), f.lastModified());
        }
        identifyLocal(node);
        return node;
    }
    private static void identifyLocal(Node node) {
        try {
            android.system.StructStat stat;
            try { stat = android.system.Os.stat(node.path()); }
            catch (android.system.ErrnoException unavailable) {
                stat = android.system.Os.lstat(node.path()); node.identityFollowsLinks = false;
            }
            node.identity = fileIdentity(stat);
            node.unixMode=stat.st_mode;
            if (android.system.OsConstants.S_ISDIR(stat.st_mode)) node.directoryIdentity = node.identity;
            File parent = node.file.getParentFile();
            if (parent != null) node.parentIdentity = fileIdentity(android.system.Os.stat(parent.getAbsolutePath()));
        } catch (android.system.ErrnoException ignored) { }
    }
    static String fileIdentity(android.system.StructStat stat) { return stat.st_dev + ":" + stat.st_ino; }
    static Node directory(File f, boolean root) {
        Node node = new Node(f.getName(), f, null, true, root, 0, 0);
        identifyLocal(node);
        return node;
    }
    Node fromIntent(Intent i) throws IOException {
        if (i.hasExtra("archiveKey")) return fromKey(i.getStringExtra("archiveKey"));
        if (i.hasExtra("uri")) return document(Uri.parse(i.getStringExtra("uri")));
        File path = new File(Objects.requireNonNull(i.getStringExtra("path")));
        recoverSplitZip(path);
        return RootAccess.automatic(local(path, i.getBooleanExtra("root", false)));
    }
    Node fromKey(String key) throws IOException {
        if (key.startsWith("zip:")) {
            Uri value = Uri.parse(key);
            Node source = fromKey(Objects.requireNonNull(value.getQueryParameter("source")));
            String entry = Objects.requireNonNull(value.getQueryParameter("entry"));
            if (!entry.isEmpty()) ArchiveOps.validateEntry(entry);
            return archiveNode(source, entry, "1".equals(value.getQueryParameter("dir")), 0, 0);
        }
        if (key.startsWith("content:")) return document(Uri.parse(key));
        boolean root = key.startsWith("root:");
        File file = new File(root ? key.substring(5) : key);
        recoverSplitZip(file);
        return local(file, root);
    }
    private static void recoverSplitZip(File file) throws IOException {
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".zip") && file.getParentFile() != null)
            SplitZipEdits.recoverDirectory(file.getParentFile());
    }
    static Node archiveNode(Node source, String entry, boolean directory, long size, long modified) {
        String trimmed = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
        String name = entry.isEmpty() ? source.name : trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return new Node(name, null, null, directory, false, size, modified, source, entry);
    }
    Node parent(Node node) {
        if (node.container != null) {
            if (node.entry.isEmpty()) return parent(node.container);
            String value = node.entry.endsWith("/") ? node.entry.substring(0, node.entry.length() - 1) : node.entry;
            int slash = value.lastIndexOf('/');
            return archiveNode(node.container, slash < 0 ? "" : value.substring(0, slash + 1), true, 0, 0);
        }
        if (DataDocumentsProvider.owns(node.uri)) {
            File path = new File(DataDocumentsProvider.path(node.uri)), parent = path.getParentFile();
            if (AndroidDataAccess.path(parent) == null) return directory(parent, false);
            return new Node(parent.getName(), null, DataDocumentsProvider.uri(parent.getPath()), true, false, 0, 0);
        }
        if (node.file == null || node.file.getParentFile() == null) return null;
        Node parent = directory(node.file.getParentFile(), node.root);
        if (node.parentIdentity != null) { parent.identity = node.parentIdentity; parent.directoryIdentity = node.parentIdentity; }
        return parent;
    }
    Node document(Uri uri) throws IOException {
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            String name = uri.getLastPathSegment(), mime = context.getContentResolver().getType(uri);
            long size = 0, modified = 0;
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                int m = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int t = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                if (m >= 0 && !c.isNull(m)) mime = c.getString(m);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
                if (t >= 0 && !c.isNull(t)) modified = c.getLong(t);
            }
            return new Node(name == null || name.isBlank() ? "document" : name, null, uri,
                    DocumentsContract.Document.MIME_TYPE_DIR.equals(mime), false, size, modified);
        } catch (Exception e) { throw new IOException("目录授权失效或不可读取：" + e.getMessage(), e); }
    }
    List<Node> list(Node parent) throws IOException {
        return list(parent, null);
    }
    /** Conflict preflight needs names only; avoid stat and sorting every ordinary directory entry. */
    Set<String> listNames(Node requested) throws IOException {
        Node parent = RootAccess.automatic(requested);
        Set<String> names = new HashSet<>();
        if (parent.container == null && parent.uri != null) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, DocumentsContract.getDocumentId(parent.uri));
            try (Cursor cursor = context.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null) throw new IOException("存储提供方没有返回目录");
                while (cursor.moveToNext()) { Tasks.Progress.check(); names.add(cursor.getString(0)); }
            } catch (RuntimeException error) { throw new IOException("目录读取失败：" + error.getMessage(), error); }
        } else if (parent.container == null && !parent.root && parent.file != null) {
            try (var entries = Files.newDirectoryStream(parent.file.toPath())) {
                for (var entry : entries) { Tasks.Progress.check(); names.add(entry.getFileName().toString()); }
            }
        } else for (Node child : list(parent)) names.add(child.name);
        return names;
    }
    /** Small ordinary transfers probe only requested names; uncertain metadata falls back to listing. */
    Set<String> transferNames(Node requested, Collection<Node> sources) throws IOException {
        Node parent = RootAccess.automatic(requested);
        if (sources.size() > 8 || parent.root || parent.uri != null || parent.container != null || parent.file == null)
            return listNames(parent);
        for (Node source : sources) {
            if (source.name.contains("/") || source.name.equals("..") || source.name.equals(".")) return listNames(parent);
            try { android.system.Os.lstat(new File(parent.file, source.name).getPath()); return listNames(parent); }
            catch (android.system.ErrnoException error) {
                if (error.errno != android.system.OsConstants.ENOENT) return listNames(parent);
            }
        }
        return new HashSet<>();
    }
    List<Node> list(Node parent, android.os.CancellationSignal signal) throws IOException {
        if (signal != null) signal.throwIfCanceled();
        parent = RootAccess.automatic(parent);
        List<Node> out = new ArrayList<>();
        if (parent.container != null) {
            File archive = archiveView(parent.container);
            try {
                for (ArchiveOps.Item item : ArchiveOps.list(archive, parent.entry))
                    out.add(archiveNode(parent.container, item.path(), item.directory(), item.size(), item.modified()));
            } finally { release(parent.container, archive); }
        } else if (parent.uri != null) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri,
                    DocumentsContract.getDocumentId(parent.uri));
            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED};
            try (Cursor c = DataDocumentsProvider.owns(parent.uri)
                    ? DataDocumentsProvider.children(context, DocumentsContract.getDocumentId(parent.uri), projection, signal)
                    : context.getContentResolver().query(children, projection, null, null, null, signal)) {
                if (c == null) throw new IOException("存储提供方没有返回目录");
                while (c.moveToNext()) {
                    if (signal != null) signal.throwIfCanceled();
                    out.add(new Node(c.getString(1), null,
                        DocumentsContract.buildDocumentUriUsingTree(parent.uri, c.getString(0)),
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)),
                        false, c.isNull(3) ? 0 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4)));
                }
            } catch (Exception e) { throw new IOException("目录读取失败：" + e.getMessage(), e); }
        } else if (parent.root) {
            RootDirectoryReader.Listing snapshot = RootDirectoryReader.snapshot(parent.path());
            parent.directoryIdentity = snapshot.identity();
            for (Node child : parseRootListing(snapshot.output())) {
                if (!child.file.getAbsoluteFile().getParentFile().toPath().normalize().equals(
                        parent.file.getAbsoluteFile().toPath().normalize()))
                    throw new IOException("ROOT 目录条目超出当前目录，请检查文件名");
                child.parentIdentity = snapshot.identity();
                out.add(child);
            }
        } else {
            File[] files = parent.file.listFiles();
            if (files == null) {
                if (StorageNavigation.storageContainer(parent.file)) out.add(StorageNavigation.knownStorageChild(parent.file));
                else throw new IOException("无法读取 " + parent.path() + "，请检查存储权限");
            } else {
                if (SplitZipEdits.recoverForListing(parent.file, files)) {
                    files = parent.file.listFiles();
                    if (files == null) throw new IOException("无法读取 " + parent.path() + "，请检查存储权限");
                }
                for (File f : files) {
                    if (signal != null) signal.throwIfCanceled();
                    out.add(local(f));
                }
            }
        }
        if (signal != null) signal.throwIfCanceled();
        out.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : NaturalOrder.compare(a.name, b.name));
        return out;
    }
    static String rootListCommand(String path) { return rootListCommand(path, true); }
    static String rootListCommand(String path, boolean followLinks) {
        String stat = "/system/bin/toybox stat " + (followLinks ? "-L " : "") + "-c '%f|%s|%Y|%d|%i|%n' \"$@\"";
        String batch = followLinks ? "if ! " + stat + " 2>/dev/null; then "
                + "for entry do if [ -L \"$entry\" ] && [ ! -e \"$entry\" ]; then "
                + "/system/bin/toybox stat -c '%f|%s|%Y|%d|%i|%n' \"$entry\" 2>/dev/null; fi; done; fi; exit 0" : stat;
        String quoted = quote(path);
        // Do not exit the persistent reader shell on a bad path, and never interpolate a path into shell code.
        return "if [ -d " + quoted + " ]; then /system/bin/toybox find -H " + quoted
                + " -mindepth 1 -maxdepth 1 -exec /system/bin/sh -c " + quote(batch) + " sh {} +; else "
                + "printf '%s\\n' " + quote("not a directory: " + path) + " >&2; false; fi";
    }
    List<Node> listForStats(Node requested) throws IOException {
        Node node = RootAccess.automatic(requested);
        if (node.file == null) return list(node);
        if (node.root) return parseRootListing(RootDirectoryReader.list(node.path(), false), true);
        List<Node> children = new ArrayList<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = Files.newDirectoryStream(node.file.toPath())) {
            for (java.nio.file.Path entry : stream) if (!Files.isSymbolicLink(entry)) children.add(local(entry.toFile()));
        }
        return children;
    }
    static List<Node> parseRootListing(String output) throws IOException { return parseRootListing(output, false); }
    private static List<Node> parseRootListing(String output, boolean skipLinks) throws IOException {
        List<Node> out = new ArrayList<>();
        for (String line : output.split("\n")) {
            String[] fields = line.split("\\|", 6);
            if (fields.length != 6) continue;
            try {
                int mode = Integer.parseInt(fields[0], 16);
                if (skipLinks && (mode & 0xf000) == 0xa000) continue;
                File f = new File(fields[5]);
                Node node = new Node(f.getName(), f, null, (mode & 0xf000) == 0x4000,
                        true, Long.parseLong(fields[1]), Long.parseLong(fields[2]) * 1000);
                node.identity = Long.parseLong(fields[3]) + ":" + Long.parseLong(fields[4]);
                node.unixMode=mode;
                node.identityFollowsLinks = (mode & 0xf000) != 0xa000;
                if (node.directory) node.directoryIdentity = node.identity;
                out.add(node);
            } catch (NumberFormatException e) { throw new IOException("ROOT 目录解析失败", e); }
        }
        return out;
    }
    InputStream read(Node requested) throws IOException {
        return read(requested, null);
    }
    InputStream readForSearch(Node requested, android.os.CancellationSignal signal) throws IOException {
        signal.throwIfCanceled();
        Node node = RootAccess.automatic(requested);
        if (node.container != null || node.uri == null) return read(node);
        android.content.res.AssetFileDescriptor descriptor = context.getContentResolver()
                .openAssetFileDescriptor(node.uri, "r", signal);
        if (descriptor == null) throw new IOException("无法打开文件");
        try {
            signal.throwIfCanceled();
            return descriptor.createInputStream();
        } catch (IOException | RuntimeException error) {
            try { descriptor.close(); } catch (IOException ignored) { }
            throw error;
        }
    }
    private InputStream read(Node requested, String rootIdentity) throws IOException {
        Node node = RootAccess.automatic(requested);
        if (node.container != null) {
            ArchiveReads copying = copyingArchives.get();
            if (copying != null) return copying.reader(node.container).open(node.entry);
            File archive = archiveView(node.container);
            try {
                return new FilterInputStream(ArchiveOps.openEntry(archive, node.entry)) {
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { release(node.container, archive); }
                    }
                };
            } catch (IOException e) {
                release(node.container, archive); throw e;
            }
        }
        if (node.uri != null) {
            InputStream in = context.getContentResolver().openInputStream(node.uri);
            if (in == null) throw new IOException("无法打开文件");
            return in;
        }
        if (!node.root) return new BufferedInputStream(new FileInputStream(node.file));
        // A readable app-side descriptor is usable only when it names the same inode in ROOT's view.
        // Keep ROOT-only and mount-namespace-specific files on the ROOT stream path.
        try {
            FileInputStream local = new FileInputStream(node.file);
            try {
                String expected = rootIdentity == null ? identity(node) : rootIdentity;
                android.system.StructStat opened = android.system.Os.fstat(local.getFD());
                String dataIdentity = fileIdentity(opened) + ":" + opened.st_size + ":" + opened.st_mtime;
                // FUSE can expose different mode/owner mappings in the app and ROOT views.
                // Data must still belong to the same inode and version. fingerprint() separately
                // rechecks ROOT's complete metadata before and after hashing that data.
                if (expected.startsWith(dataIdentity + ":"))
                    return new BufferedInputStream(local);
            } catch (android.system.ErrnoException | IOException error) {
                local.close(); throw error;
            }
            local.close();
        } catch (android.system.ErrnoException | IOException unavailable) { }
        Process p = RootShell.command("cat " + quote(node.path())).start();
        return new FilterInputStream(p.getInputStream()) {
            @Override public void close() throws IOException {
                super.close();
                try {
                    if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("ROOT 读取超时"); }
                    if (p.exitValue() != 0) throw new IOException("ROOT 读取失败");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        };
    }
    void checkWritable(Node node) throws IOException {
        while (node.container != null) node = node.container;
        node = RootAccess.automatic(node);
        if (node.file == null) return;
        if (node.root) {
            int mode = Integer.parseUnsignedInt(RootDirectoryReader.stat(node.path(), "%f", false), 16);
            if (android.system.OsConstants.S_ISLNK(mode)) throw new IOException("不能直接保存符号链接，请打开真实文件");
            if (!android.system.OsConstants.S_ISREG(mode)) throw new IOException("保存目标不是普通文件");
        } else FileState.metadata(node.file);
    }
    String fingerprint(Node requested) throws IOException {
        try (Tasks.Progress.Phase stage = Tasks.Progress.stage("校验来源")) {
            Node node = requested;
            while (node.container != null) node = node.container;
            node = RootAccess.automatic(node);
            if (SplitZipEdits.canEdit(node)) return SplitZipEdits.fingerprint(node.file);
            String before = identity(node);
            String digest;
            try (InputStream input = node.file != null && !node.root
                    ? new FileInputStream(node.file) : read(node, before)) { digest = digest(input); }
            if (!before.equals(identity(node))) throw new IOException("读取期间原文件已变化，请重新打开");
            return before + ":" + digest;
        }
    }
    String identity(Node node) throws IOException {
        if (node.container != null) return identity(node.container) + "!/" + node.entry;
        if (node.file != null) {
            if (!node.root) {
                try { return statIdentity(android.system.Os.stat(node.path())); }
                catch (android.system.ErrnoException error) { throw new IOException(error); }
            }
            return RootDirectoryReader.stat(node.path(), "%d:%i:%s:%Y:%a:%u:%g", true);
        }
        Node current = document(node.uri);
        return node.uri + ":" + current.size + ":" + current.modified;
    }
    static String statIdentity(android.system.StructStat stat) {
        return stat.st_dev + ":" + stat.st_ino + ":" + stat.st_size + ":" + stat.st_mtime + ":"
                + Integer.toOctalString(stat.st_mode & 07777) + ":" + stat.st_uid + ":" + stat.st_gid;
    }
    static String digest(InputStream input) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        byte[] buffer = new byte[128 * 1024]; int count;
        while ((count = input.read(buffer)) != -1) if (count > 0) { Tasks.Progress.check(); digest.update(buffer, 0, count); }
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest.digest()) {
            value.append("0123456789abcdef".charAt((b & 255) >>> 4));
            value.append("0123456789abcdef".charAt(b & 15));
        }
        return value.toString();
    }
    void checkRevision(Node node, String expected) throws IOException {
        if (expected != null && !expected.equals(fingerprint(node)))
            throw new IOException("原文件已在外部改变，请刷新或重新打开后合并修改");
    }
    void write(Node node, File content) throws IOException { write(node, content, null); }
    String write(Node node, File content, String expected) throws IOException {
        // Publication checks must read the new archive instead of a copy operation's pinned view.
        ArchiveReads copying = copyingArchives.get();
        Boolean fresh = freshArchiveReads.get();
        copyingArchives.remove();
        freshArchiveReads.set(true);
        try {
            try (Tasks.Progress.Phase stage = Tasks.Progress.stage("提交修改")) {
                Tasks.Progress.check();
                checkWritable(node);
                checkRevision(node, expected);
                node = RootAccess.automatic(node);
                if (node.container != null) {
                    editArchive(node.container, null, null, Map.of(node.entry, content), expected);
                } else if (node.uri != null) {
                    SafRecovery.write(context, node, content, expected);
                } else if (node.root) {
                    applyRoot(new FileInputStream(content), content.length(), node, expected);
                } else {
                    FileState before = FileState.read(node.file);
                    if (expected != null && !expected.equals(before.fingerprint()))
                        throw new IOException("原文件已在外部改变，请重新打开");
                    File tmp = File.createTempFile(".dualstack-", ".tmp", node.file.getParentFile());
                    try {
                        Files.copy(content.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        Tasks.Progress.check();
                        publish(tmp, node.file, before);
                    } finally { Files.deleteIfExists(tmp.toPath()); }
                }
                if (node.file != null) ArchiveOps.forget(node.file);
                String revision = fingerprint(node);
                String expectedDigest;
                try (InputStream input = new FileInputStream(content)) { expectedDigest = digest(input); }
                String actualDigest = revision.substring(revision.lastIndexOf(':') + 1);
                if (node.container != null) {
                    try (InputStream input = read(node)) { actualDigest = digest(input); }
                    checkRevision(node, revision);
                }
                if (!expectedDigest.equals(actualDigest)) throw new IOException("写入后的来源内容与待保存内容不一致，请重新核对来源文件");
                return revision;
            }

        } finally {
            if (copying != null) copyingArchives.set(copying);
            if (fresh == null) freshArchiveReads.remove(); else freshArchiveReads.set(fresh);
        }
    }
    private String applyRoot(InputStream payload, long size, Node node, String expected) throws IOException {
        if (expected != null && size >= 0) {
            try (InputStream input = payload) { return RootMutations.replace(context, node, input, size, expected); }
        }
        String temporary = node.path() + ".dualstack-" + UUID.randomUUID();
        String command = rootWriteCommand(context.getApplicationInfo().sourceDir, node.path(), temporary, size, expected);
        try (InputStream input = payload) {
            ShellRunner.Result result = new ShellRunner().run(command, null, true, 300000, null, true, input);
            if (result.timedOut()) throw new IOException("ROOT 写入超时");
            if (result.exitCode() != 0) throw new IOException("ROOT 写入失败\n" + result.output());
            String[] lines = result.output().trim().split("\n");
            return lines[lines.length - 1].trim();
        }
    }

    Node create(Node parent, String name, boolean directory) throws IOException {
        parent = RootAccess.automatic(parent);
        checkName(name);
        for (Node n : list(parent)) if (name.equals(n.name)) throw new IOException("同名文件已存在");
        return createAbsent(parent, name, directory);
    }
    private Node createAbsent(Node parent, String name, boolean directory) throws IOException {
        checkName(name);
        if (parent.container != null) {
            String entry = parent.entry + name + (directory ? "/" : "");
            Map<String, File> additions = new HashMap<>(); additions.put(entry, null);
            editArchive(parent.container, null, null, additions);
            return archiveNode(parent.container, entry, directory, 0, System.currentTimeMillis());
        }
        if (parent.uri != null) {
            Uri u = DocumentsContract.createDocument(context.getContentResolver(), parent.uri,
                    directory ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream", name);
            if (u == null) throw new IOException("无法创建");
            return document(u);
        }
        File f = new File(parent.file, name);
        if (parent.root) try {
            return RootMutations.create(context, parent, name, directory);
        } catch (IOException error) {
            throw new IOException("无法在目标目录创建" + (directory ? "文件夹" : "文件") + "：" + f.getPath()
                    + "\n" + error.getMessage(), error);
        }
        if (!(directory ? f.mkdir() : f.createNewFile())) throw new IOException("无法创建 " + name);
        return local(f);
    }
    Node rename(Node node, String name) throws IOException {
        Tasks.Progress.check();
        checkRemoval(node);
        node = RootAccess.automatic(node);
        checkName(name);
        if (node.container != null) {
            Node parent = parent(node);
            for (Node sibling : list(parent)) if (sibling.name.equals(name)) throw new IOException("同名文件已存在");
            String entry = parent.entry + name + (node.directory ? "/" : "");
            editArchive(node.container, node.entry, entry, Collections.emptyMap());
            return archiveNode(node.container, entry, node.directory, node.size, node.modified);
        }
        if (node.uri != null) {
            Uri u = DocumentsContract.renameDocument(context.getContentResolver(), node.uri, name);
            if (u == null) throw new IOException("无法重命名");
            return document(u);
        }
        File to = new File(node.file.getParentFile(), name);
        if (node.root) return RootMutations.rename(context, node, parent(node), name);
        if (to.exists()) throw new IOException("同名文件已存在");
        Files.move(node.file.toPath(), to.toPath());
        return local(to);
    }
    List<Node> renameArchiveBatch(Map<Node, String> requests, String expected) throws IOException {
        if (requests.isEmpty()) return List.of();
        Node container = requests.keySet().iterator().next().container;
        synchronized (archiveLock(container)) {
            checkWritable(container); checkRevision(container, expected);
            Map<String, String> renames = new LinkedHashMap<>(); List<Node> outputs = new ArrayList<>();
            for (var request : requests.entrySet()) {
                Node source = request.getKey(); checkName(request.getValue()); checkRemoval(source);
                if (source.container == null || !container.key().equals(source.container.key()) || source.entry.isEmpty()) throw new IOException("归档来源不一致");
                String entry = parent(source).entry + request.getValue() + (source.directory ? "/" : "");
                renames.put(source.entry, entry); outputs.add(archiveNode(container, entry, source.directory, source.size, source.modified));
            }
            Node direct = RootAccess.automatic(container);
            if (SplitZipEdits.canEdit(direct)) {
                SplitZipEdits.rewrite(direct.file, expected, renames, Collections.emptyMap(), Collections.emptySet());
                return outputs;
            }
            File archive = File.createTempFile("archive-renames-", "-" + container.name.replaceAll("[^a-zA-Z0-9.]", "_"), context.getCacheDir());
            try {
                try (InputStream in = read(container); OutputStream out = new FileOutputStream(archive)) { pump(in, out); }
                if (container.file != null) ArchiveOps.copyPassword(container.file, archive);
                checkRevision(container, expected); ArchiveOps.renameAll(archive, renames); write(container, archive, expected);
                return outputs;
            } finally { release(container, archive); }
        }
    }
    void delete(Node node) throws IOException {
        Tasks.Progress.check();
        checkRemoval(node);
        node = RootAccess.automatic(node);
        if (node.container != null) {
            if (node.entry.isEmpty()) throw new IOException("不能删除归档根目录");
            Tasks.Progress.step(node.name);
            editArchive(node.container, node.entry, null, Collections.emptyMap());
        } else if (node.uri != null) {
            Tasks.Progress.step(node.name);
            if (!DocumentsContract.deleteDocument(context.getContentResolver(), node.uri)) throw new IOException("删除失败");
        } else if (node.root) {
            RootMutations.delete(context, List.of(node));
        } else {
            deleteLocal(node.file.toPath());
        }
    }
    void deleteAll(Collection<Node> requested) throws IOException {
        Tasks.Progress.check();
        for (Node node : requested) {
            checkRemoval(node);
            if (node.container != null && node.entry.isEmpty()) throw new IOException("不能删除归档根目录");
        }
        Map<String, Node> roots = new LinkedHashMap<>();
        Map<String, List<Node>> archives = new LinkedHashMap<>();
        List<Node> ordinary = new ArrayList<>();
        List<Node> localFiles = new ArrayList<>();
        for (Node original : requested) {
            Node node = RootAccess.automatic(original);
            if (node.container != null) archives.computeIfAbsent(node.container.key(), key -> new ArrayList<>()).add(node);
            else if (node.root) roots.put(node.path(), node);
            else if (node.file != null && !node.directory) localFiles.add(node);
            else ordinary.add(node);
        }
        if (!roots.isEmpty()) RootMutations.delete(context, roots.values());
        for (List<Node> entries : archives.values()) removeArchiveEntries(entries.get(0).container, entries, null);
        for (Node node : localFiles) {
            Tasks.Progress.step(node.name);
            Files.deleteIfExists(node.file.toPath());
        }
        for (Node node : ordinary) delete(node);
    }
    /** Resolves a path outside the app's ordinary view of the filesystem; falls back to the plain path. */
    static String physical(File file) {
        String fallback;
        try { fallback = file.getCanonicalPath(); }
        catch (IOException ignored) { fallback = file.getAbsolutePath(); }
        if (!RootAccess.known()) return fallback;
        try {
            ShellRunner.Result result = new ShellRunner().run("/system/bin/toybox realpath "
                    + quote(file.getAbsolutePath()), null, true, 5000, null, false);
            if (!result.timedOut() && result.exitCode() == 0 && !result.output().isBlank()) return result.output().trim();
        } catch (IOException ignored) { }
        return fallback;
    }
    private static void deleteLocal(java.nio.file.Path path) throws IOException {
        // Walk without following links or enumerating/sorting metadata for display.
        Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Tasks.Progress.step(file.getFileName().toString());
                Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path directory,
                    IOException error) throws IOException {
                if (error != null) throw error;
                Tasks.Progress.step(directory.getFileName().toString());
                Files.delete(directory); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
    void validateCopy(Node source, Node target) throws IOException { checkCopy(source, target); }
    List<Node> executePlan(TransferPlan plan, boolean move) throws IOException {
        Tasks.Progress.plan(plan.items.stream().filter(item -> !plan.published.containsKey(item.source().key())).map(TransferPlan.Item::source).toList(), move ? "移动文件" : "复制文件");
        try (ArchiveReads reads = new ArchiveReads()) {
            for (TransferPlan.Item item : plan.items) {
                if (plan.settled.contains(item.source().key())) continue;
                if (move) checkRemoval(item.source());
                checkCopy(item.source(), item.parent());
                if (plan.published.containsKey(item.source().key())) {
                    // Member cleanup validates each remaining file against its target. The source
                    // directory may already be partly consumed, so it cannot be hashed as a whole.
                    if (!plan.moveMembers.containsKey(item.source().key())) {
                        OutputVersion version = plan.versions.get(item.source().key());
                        OutputVersion current = outputVersion(plan.published.get(item.source().key()));
                        if (version == null && contentSignature(item.source(), new HashSet<>()).equals(current.content)) {
                            plan.versions.put(item.source().key(), current); version = current;
                        }
                        if (version == null || !version.equals(current))
                            throw new IOException("已写入的文件发生变化，请检查后重新操作：" + item.source().name);
                    }
                } else if (item.existing() != null && !Objects.equals(item.expected(), identity(item.existing())))
                    throw new IOException("目标在确认后发生变化，请重新选择：" + item.existing().name);
                if (move && item.source().container != null && !plan.removedArchives.contains(item.source().container.key())) {
                    String key = item.source().container.key();
                    if (!plan.archiveRevisions.containsKey(key)) plan.archiveRevisions.put(key, fingerprint(item.source().container));
                }
            }
            Map<String, List<TransferPlan.Item>> targetArchives = new LinkedHashMap<>();
            Map<String, Set<String>> names = new HashMap<>();
            plan.directoryNames.forEach((key, value) -> names.put(key, new HashSet<>(value)));
            for (TransferPlan.Item item : plan.items) {
                if (plan.published.containsKey(item.source().key())) continue;
                if (item.parent().container != null) { targetArchives.computeIfAbsent(item.parent().container.key(), k -> new ArrayList<>()).add(item); continue; }
                Node output = copy(item.source(), item.parent(), move && item.source().container == null, names, item.policy(), item.existing(), item.expected(), null, node -> plan.retainedSourceDirectories++,
                        (published, members) -> {
                            // Preserve the completed copy before source cleanup can fail or be cancelled.
                            plan.published.put(item.source().key(), published);
                            plan.moveMembers.put(item.source().key(), members);
                        });
                if (output != null) {
                    plan.published.put(item.source().key(), output);
                    if (move && item.source().container != null) plan.versions.put(item.source().key(), outputVersion(output));
                    else plan.settled.add(item.source().key());
                }
            }
            // Publication invalidates cached ZIP readers. Release them before replacing any archive.
            reads.close();
            for (List<TransferPlan.Item> group : targetArchives.values()) {
                Map<String, Node> outputs = copyPlanIntoArchive(group, move, plan);
                // Same-archive publication already removed its sources atomically. Record that
                // before any later verification can fail, so retries never re-copy those entries.
                plan.published.putAll(outputs);
                for (TransferPlan.Item item : group) {
                    boolean same = item.source().container != null && item.source().container.key().equals(item.parent().container.key());
                    if (!move || same) { plan.settled.add(item.source().key()); if (move) plan.removedSources.add(item.source().key()); }
                    else plan.versions.put(item.source().key(), outputVersion(outputs.get(item.source().key())));
                }
            }
            if (move) {
                plan.retainedEmptyDirectories = 0;
                Map<String, List<Node>> removals = new LinkedHashMap<>();
                for (TransferPlan.Item item : plan.items) {
                    Node source = item.source(); Node output = plan.published.get(source.key());
                    MoveMembers members = plan.moveMembers.get(source.key());
                    boolean deferred = source.container != null || item.parent().container != null || members != null;
                    if (!deferred || output == null || plan.settled.contains(source.key()) || plan.removedSources.contains(source.key()) || source.container != null && plan.removedArchives.contains(source.container.key())) continue;
                    OutputVersion version = plan.versions.get(source.key());
                    if (members == null && (!version.equals(outputVersion(output)) || !contentSignature(source, new HashSet<>()).equals(version.content)))
                        throw new IOException("移动输出或来源内容发生变化，来源保留：" + source.name);
                    if (source.container == null) {
                        if (members == null) throw new IOException("缺少来源清理记录，请检查已复制的文件：" + source.name);
                        members.remove(node -> plan.retainedSourceDirectories++);
                        plan.removedSources.add(source.key()); plan.settled.add(source.key());
                    }
                    else removals.computeIfAbsent(source.container.key(), k -> new ArrayList<>()).add(source);
                }
                for (List<Node> entries : removals.values()) {
                    Node container = entries.get(0).container;
                    removeArchiveEntries(container, entries, plan.archiveRevisions.get(container.key())); plan.removedArchives.add(container.key());
                    for (Node entry : entries) plan.settled.add(entry.key());
                }
                // Keep a merged source directory whenever skipped or newly added children remain.
                List<Node> directories = new ArrayList<>(plan.merged); Collections.reverse(directories);
                Map<String, List<Node>> emptyArchiveDirs = new LinkedHashMap<>();
                Map<String, String> emptyArchiveRevisions = new HashMap<>();
                for (Node directory : directories) {
                    if (plan.removedSources.contains(directory.key())) continue;
                    if (directory.container != null && !emptyArchiveRevisions.containsKey(directory.container.key()))
                        emptyArchiveRevisions.put(directory.container.key(), fingerprint(directory.container));
                    if (!list(directory).isEmpty()) continue;
                    if (directory.container != null) emptyArchiveDirs.computeIfAbsent(directory.container.key(), k -> new ArrayList<>()).add(directory);
                    else if (directory.file != null && !directory.root) {
                        // delete() fails if another writer added a child after the check.
                        Files.delete(directory.file.toPath()); plan.removedSources.add(directory.key());
                    }
                    else if (directory.root) { RootMutations.removeEmpty(context, directory); plan.removedSources.add(directory.key()); }
                    else plan.retainedEmptyDirectories++;
                }
                for (var empty : emptyArchiveDirs.values()) {
                    removeArchiveEntries(empty.get(0).container, empty, emptyArchiveRevisions.get(empty.get(0).container.key()));
                    for (Node directory : empty) plan.removedSources.add(directory.key());
                }
            }
            return new ArrayList<>(plan.published.values());
        } catch (IOException | RuntimeException error) {
            if (!plan.published.isEmpty()) throw new TransferPlan.Incomplete(plan, error);
            throw error;
        }
    }
    private Map<String, Node> copyPlanIntoArchive(List<TransferPlan.Item> items, boolean move, TransferPlan plan) throws IOException {
        Node container = items.get(0).parent().container;
        synchronized (archiveLock(container)) {
            String baseline = fingerprint(container);
            Map<String, Set<String>> names = new HashMap<>(); Map<String, File> additions = new LinkedHashMap<>();
            Map<Node, File> staged = new LinkedHashMap<>(); Set<String> removals = new LinkedHashSet<>();
            Map<String, Node> outputs = new LinkedHashMap<>();
            try {
                for (TransferPlan.Item item : items) {
                    Node source = item.source(), parent = item.parent(); Tasks.Progress.check(); checkCopy(source, parent);
                    Set<String> used = names.get(parent.key());
                    if (used == null) { used = new HashSet<>(listNames(parent)); names.put(parent.key(), used); }
                    boolean overwrite = item.policy() == ConflictPolicy.OVERWRITE && used.contains(source.name);
                    if (overwrite) {
                        if (item.existing() == null || source.directory || item.existing().directory) throw new IOException("只能覆盖同名普通文件");
                        checkContainerOverwrite(source, item.existing());
                    }
                    String name = overwrite ? source.name : unique(used, source.name); used.add(name);
                    String entry = parent.entry + name + (source.directory ? "/" : "");
                    MoveMembers members = move && source.container == null ? new MoveMembers() : null;
                    archiveAdditions(source, entry, additions, staged, new HashSet<>(), container, members);
                    if (members != null) plan.moveMembers.put(source.key(), members);
                    if (move && source.container != null && source.container.key().equals(container.key())) removals.add(source.entry);
                    outputs.put(source.key(), archiveNode(container, entry, source.directory, source.size, source.modified));
                }
                for (String removal : removals) for (String addition : additions.keySet())
                    if (addition.equals(removal) || removal.endsWith("/") && addition.startsWith(removal)) throw new IOException("移动目标位于待移除的归档目录内");
                editArchive(container, null, null, additions, baseline, removals);
                return outputs;
            } finally { for (var entry : staged.entrySet()) release(entry.getKey(), entry.getValue()); }
        }
    }
    Node copy(Node source, Node parent, boolean move) throws IOException {
        try (ArchiveReads reads = move ? null : new ArchiveReads()) {
            return copy(source, parent, move, new HashMap<>());

        }
    }
    List<Node> copyAll(Collection<Node> sources, Node parent, boolean move) throws IOException {
        return copyAll(sources, parent, move, ConflictPolicy.KEEP_BOTH, null);
    }
    List<Node> copyAll(Collection<Node> sources, Node parent, boolean move, ConflictPolicy conflicts,
                       Set<String> existingNames) throws IOException {
        return copyAll(sources, parent, move, conflicts, existingNames, null);
    }
    List<Node> copyAll(Collection<Node> sources, Node parent, boolean move, ConflictPolicy conflicts,
                       Set<String> existingNames, java.util.function.Consumer<Node> retained) throws IOException {
        Tasks.Progress.plan(sources, move ? "移动文件" : "复制文件");
        existingNames = existingNames == null ? null : new HashSet<>(existingNames);
        try (ArchiveReads reads = move ? null : new ArchiveReads()) {
            Node first = null; boolean archiveSources = true;
            if (move) for (Node source : sources) {
                checkRemoval(source);
                if (first == null) first = source;
                if (source.container == null || first.container == null || !source.container.key().equals(first.container.key())) archiveSources = false;
            }
            if (move && conflicts == ConflictPolicy.KEEP_BOTH && first != null && archiveSources && canBatchArchiveMove(first, parent))
                return moveArchiveBatch(new ArrayList<>(sources), parent, null, existingNames, node -> {});
            if (parent.container != null && (!move || sources.stream().allMatch(source ->
                    source.container != null && source.container.key().equals(parent.container.key()))))
                return copyIntoArchive(sources, parent, move, conflicts, existingNames);
            Map<String, Set<String>> names = new HashMap<>();
            if (existingNames != null) names.put(RootAccess.automatic(parent).key(), new HashSet<>(existingNames));
            List<Node> items = new ArrayList<>(sources), copied = new ArrayList<>();
            TransferPlan recovery = move ? new TransferPlan() : null;
            if (recovery != null) for (Node source : items) recovery.items.add(new TransferPlan.Item(source, parent, conflicts, null, null));
            for (int index = 0; index < items.size(); index++) {
                Node source = items.get(index);
                try {
                    Node output = copy(source, parent, move, names, conflicts, null, null, null, retained, recovery == null ? null : (published, members) -> {
                        recovery.published.put(source.key(), published); recovery.moveMembers.put(source.key(), members);
                    });
                    if (output != null) {
                        copied.add(output);
                        if (recovery != null) { recovery.published.put(source.key(), output); recovery.settled.add(source.key()); }
                    }
                }
                catch (IOException | RuntimeException error) {
                    if (recovery != null && recovery.moveMembers.containsKey(source.key())) {
                        names.forEach((key, value) -> recovery.directoryNames.put(key, new HashSet<>(value)));
                        throw new TransferPlan.Incomplete(recovery, error);
                    }
                    if (!(error instanceof Tasks.CancelledException) && !copied.isEmpty())
                        throw new Tasks.PartialTransferException(copied, items.subList(index, items.size()), error);
                    throw error;
                }
            }
            return copied;

        }
    }
    Node copy(Node source, Node parent, boolean move, Map<String, Set<String>> names) throws IOException {
        return copy(source, parent, move, names, ConflictPolicy.KEEP_BOTH);
    }
    private Node copy(Node source, Node parent, boolean move, Map<String, Set<String>> names,
                      ConflictPolicy conflicts) throws IOException {
        return copy(source, parent, move, names, conflicts, null, null);
    }
    private Node copy(Node source, Node parent, boolean move, Map<String, Set<String>> names,
                       ConflictPolicy conflicts, Node knownTarget, String confirmedIdentity) throws IOException {
        return copy(source, parent, move, names, conflicts, knownTarget, confirmedIdentity, null);
    }
    private Node copy(Node source, Node parent, boolean move, Map<String, Set<String>> names,
                      ConflictPolicy conflicts, Node knownTarget, String confirmedIdentity, MessageDigest digest) throws IOException {
        return copy(source, parent, move, names, conflicts, knownTarget, confirmedIdentity, digest, null);
    }
    private Node copy(Node source, Node parent, boolean move, Map<String, Set<String>> names,
                      ConflictPolicy conflicts, Node knownTarget, String confirmedIdentity, MessageDigest digest,
                      java.util.function.Consumer<Node> retained) throws IOException {
        return copy(source, parent, move, names, conflicts, knownTarget, confirmedIdentity, digest, retained, null);
    }
    private interface MovePublication { void published(Node output, MoveMembers members) throws IOException; }
    private Node copy(Node source, Node parent, boolean move, Map<String, Set<String>> names,
                      ConflictPolicy conflicts, Node knownTarget, String confirmedIdentity, MessageDigest digest,
                      java.util.function.Consumer<Node> retained, MovePublication publication) throws IOException {
        Tasks.Progress.check();
        if (move) checkRemoval(source);
        source = RootAccess.automatic(source);
        parent = RootAccess.automatic(parent);
        checkCopy(source, parent);
        if (move && source.file != null && parent.file != null
                && source.file.getParentFile().getCanonicalFile().equals(parent.file.getCanonicalFile())) return source;
        Set<String> used = names.get(parent.key());
        if (used == null) {
            used = new HashSet<>();
            used.addAll(listNames(parent));
            names.put(parent.key(), used);
        }
        if (parent.container != null) {
            boolean sameArchive = source.container != null && source.container.key().equals(parent.container.key());
            String sourceVersion = !move || sameArchive ? null : source.container == null ? identity(source) : fingerprint(source);
            String sourceContent = !move || sameArchive ? null : contentSignature(source, new HashSet<>());
            MoveMembers members = move && source.container == null ? new MoveMembers() : null;
            List<Node> output = copyIntoArchive(List.of(source), parent, move && sameArchive, null, conflicts, used, members);
            if (move && !sameArchive && !output.isEmpty()) {
                OutputVersion targetVersion = outputVersion(output.get(0));
                if (!sourceContent.equals(targetVersion.content) || !sourceContent.equals(contentSignature(source, new HashSet<>())))
                    throw new IOException("移动输出或来源内容发生变化，来源保留");
                String current = source.container == null ? identity(source) : fingerprint(source);
                if (!sourceVersion.equals(current) || !targetVersion.equals(outputVersion(output.get(0))))
                    throw new IOException("移动输出或来源版本发生变化，来源保留");
                if (source.container != null) removeArchiveEntries(source.container, List.of(source), sourceVersion);
                else {
                    if (publication != null) publication.published(output.get(0), members);
                    members.remove(retained);
                }
            }
            return output.isEmpty() ? null : output.get(0);
        }
        if (conflicts == ConflictPolicy.SKIP && used.contains(source.name)) return null;
        if (conflicts == ConflictPolicy.OVERWRITE && used.contains(source.name)) {
            Node existing = knownTarget;
            if (existing == null) for (Node child : list(parent)) if (child.name.equals(source.name)) { existing = child; break; }
            if (existing == null) throw new IOException("目标项目已变化，请刷新后重试");
            if (confirmedIdentity != null && !confirmedIdentity.equals(identity(existing)))
                throw new IOException("目标在确认后发生变化，请重新选择：" + existing.name);
            if (source.directory || existing.directory) throw new IOException("只能覆盖同名普通文件：" + source.name);
            checkContainerOverwrite(source, existing);
            if (sameStorageObject(source, existing)) return existing;
            // A cloud/pipe provider may hide its physical identity. A local payload must never
            // be truncated by opening an alias for writing. A move also cannot safely unlink it.
            boolean opaqueTarget = source.container == null && (source.uri != null || existing.uri != null)
                    && (storageObjectIdentity(source) == null || storageObjectIdentity(existing) == null);
            if (opaqueTarget && move)
                throw new IOException("提供方无法确认来源与目标是不同文件，请先另存一份");
            String sourceVersion = move ? fingerprint(source) : null;
            File staged = materialize(source);
            if (opaqueTarget && staged.equals(source.file)) {
                File snapshot = File.createTempFile("overwrite-", ".tmp", context.getCacheDir());
                try { Files.copy(staged.toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException | RuntimeException e) { Files.deleteIfExists(snapshot.toPath()); throw e; }
                staged = snapshot;
            }
            String outputVersion;
            try {
                if (move) checkRevision(source, sourceVersion);
                String targetRevision = fingerprint(existing);
                if (confirmedIdentity != null && !targetRevision.startsWith(confirmedIdentity + ":"))
                    throw new IOException("目标在确认后发生变化，请重新选择：" + existing.name);
                outputVersion = write(existing, staged, targetRevision);
            }
            finally { release(source, staged); }
            if (move) {
                checkRevision(existing, outputVersion);
                checkRevision(source, sourceVersion);
                if (source.container != null) removeArchiveEntries(source.container, List.of(source), sourceVersion);
                else {
                    MoveMembers members = new MoveMembers(); members.record(source, existing);
                    if (publication != null) publication.published(existing, members);
                    members.remove(retained);
                }
            }
            FileTimes.copied(context,source,existing);
            return existing;
        }
        String name = unique(used, source.name);
        used.add(name);
        if (move && source.file != null && parent.file != null) {
            File destination = new File(parent.file, name);
            if (source.root || parent.root) {
                Node moved = StorageNavigation.appManagedStorage(source.file.getParentFile()) && StorageNavigation.appManagedStorage(parent.file)
                        ? RootMutations.moveShared(source, parent, name) : RootMutations.moveIfSameFileSystem(context, source, parent, name);
                if (moved != null) return moved;
            } else try {
                if (android.system.Os.stat(source.path()).st_dev == android.system.Os.stat(parent.path()).st_dev) {
                    Tasks.Progress.step(source.name);
                    Files.move(source.file.toPath(), destination.toPath());
                    return local(destination);
                }
            } catch (android.system.ErrnoException error) { throw new IOException(error); }
        }
        Node made = createAbsent(parent, name, source.directory);
        used.add(made.name);
        MoveMembers members = move && source.container == null ? new MoveMembers() : null;
        CopyLedger ledger = new CopyLedger();
        try {
            ledger.record(made);
            copyContent(source, made, new HashSet<>(), ledger, digest, members);
        } catch (IOException | RuntimeException e) {
            ledger.rollback(e);
            throw e;
        }
        if (members != null) {
            if (publication != null) publication.published(made, members);
            members.remove(retained);
        }
        else if (move) delete(source);
        return made;
    }
    /** One private rebuild and one guarded publication for a target archive. */
    private List<Node> copyIntoArchive(Collection<Node> sources, Node parent, boolean move) throws IOException {
        return copyIntoArchive(sources, parent, move, null);
    }
    private List<Node> copyIntoArchive(Collection<Node> sources, Node parent, boolean move, String expected) throws IOException {
        return copyIntoArchive(sources, parent, move, expected, ConflictPolicy.KEEP_BOTH, null);
    }
    private List<Node> copyIntoArchive(Collection<Node> sources, Node parent, boolean move, ConflictPolicy conflicts,
                                       Set<String> existingNames) throws IOException {
        return copyIntoArchive(sources, parent, move, null, conflicts, existingNames);
    }
    private List<Node> copyIntoArchive(Collection<Node> sources, Node parent, boolean move, String expected,
                                       ConflictPolicy conflicts, Set<String> existingNames) throws IOException {
        return copyIntoArchive(sources, parent, move, expected, conflicts, existingNames, null);
    }
    private List<Node> copyIntoArchive(Collection<Node> sources, Node parent, boolean move, String expected,
                                       ConflictPolicy conflicts, Set<String> existingNames, MoveMembers members) throws IOException {
        synchronized (archiveLock(parent.container)) {
            String baseline = expected == null ? fingerprint(parent.container) : expected;
            checkRevision(parent.container, baseline);
            Set<String> used = new HashSet<>(), removals = new LinkedHashSet<>();
            Map<String, Node> existing = new HashMap<>();
            if (existingNames != null) used.addAll(existingNames);
            for (Node child : list(parent)) { used.add(child.name); existing.put(child.name, child); }
            Map<String, File> additions = new LinkedHashMap<>();
            Map<Node, File> staged = new LinkedHashMap<>();
            Map<String, ArchiveSnapshot> views = new LinkedHashMap<>();
            List<Node> outputs = new ArrayList<>();
            try {
                for (Node source : sources) if (source.container != null && !views.containsKey(source.container.key())
                        && !archiveSnapshots.containsKey(source.container.key()))
                    views.put(source.container.key(), new ArchiveSnapshot(source.container,
                            source.container.key().equals(parent.container.key()) ? baseline : null));
                for (Node original : sources) {
                    Node source = RootAccess.automatic(original);
                    Tasks.Progress.check(); checkCopy(source, parent);
                    if (conflicts == ConflictPolicy.SKIP && used.contains(source.name)) continue;
                    boolean overwrite = conflicts == ConflictPolicy.OVERWRITE && used.contains(source.name);
                    Node previous = overwrite ? existing.get(source.name) : null;
                    if (overwrite && (previous == null || source.directory || previous.directory))
                        throw new IOException("只能覆盖同名普通文件：" + source.name);
                    if (overwrite) checkContainerOverwrite(source, previous);
                    String name = overwrite ? source.name : unique(used, source.name); used.add(name);
                    String entry = parent.entry + name + (source.directory ? "/" : "");
                    archiveAdditions(source, entry, additions, staged, new HashSet<>(), parent.container, members);
                    if (move) removals.add(source.entry);
                    outputs.add(archiveNode(parent.container, entry, source.directory, source.size, source.modified));
                }
                // A selected parent and child must not silently erase one another's newly copied output.
                for (String removal : removals) for (String addition : additions.keySet())
                    if (addition.equals(removal) || removal.endsWith("/") && addition.startsWith(removal))
                        throw new IOException("移动目标位于待移除的归档目录内");
                if (additions.isEmpty() && removals.isEmpty()) return outputs;
                for (ArchiveSnapshot view : views.values()) view.verify();
                editArchive(parent.container, null, null, additions, baseline, removals);
                if (existingNames != null) { existingNames.clear(); existingNames.addAll(used); }
                return outputs;
            } finally {
                for (Map.Entry<Node, File> item : staged.entrySet()) release(item.getKey(), item.getValue());
                for (ArchiveSnapshot view : views.values()) view.close();
            }
        }
    }
    void removeArchiveEntries(Node container, Collection<Node> entries, String expected) throws IOException {
        Tasks.Progress.phase("重建归档");
        editArchive(container, null, null, Collections.emptyMap(), expected, archiveRemovals(container, entries));
    }
    private Set<String> archiveRemovals(Node container, Collection<Node> entries) throws IOException {
        Set<String> removals = new LinkedHashSet<>();
        for (Node entry : entries) {
            checkRemoval(entry);
            if (entry.container == null || !entry.container.key().equals(container.key()) || entry.entry.isEmpty())
                throw new IOException("归档批量删除来源无效");
            removals.add(entry.entry);
        }
        return removals;
    }
    interface Copied { void published(Node node) throws IOException; }
    void copyArchiveBatch(List<Node> sources, Node target, String expected, Copied copied) throws IOException {
        try (ArchiveSnapshot snapshot = new ArchiveSnapshot(sources.get(0).container, expected)) {
            Tasks.Progress.phase("传输文件");
            Map<String, Set<String>> names = new HashMap<>();
            for (Node source : sources) copied.published(copy(source, target, false, names));
        }
    }
    static boolean canBatchArchiveMove(Node source, Node target) {
        if (source.container == null || target == null) return false;
        if (target.container == null || target.container.key().equals(source.container.key())) return true;
        Node a = source.container, b = target.container;
        while (a.container != null) a = a.container;
        while (b.container != null) b = b.container;
        return !a.key().equals(b.key());
    }
    List<Node> moveArchiveBatch(List<Node> sources, Node target, Copied copied) throws IOException {
        return moveArchiveBatch(sources, target, null, copied);
    }
    List<Node> moveArchiveBatch(List<Node> sources, Node target, String expected, Copied copied) throws IOException {
        return moveArchiveBatch(sources, target, expected, null, copied);
    }
    private List<Node> moveArchiveBatch(List<Node> sources, Node target, String expected, Set<String> existingNames,
                                        Copied copied) throws IOException {
        return moveArchiveBatch(new ArchiveMoveRecovery(sources, target, expected), existingNames, copied);
    }
    static final class ArchiveMoveRecovery {
        final List<Node> sources;
        final Node target;
        String expected;
        final Map<String, Node> published = new LinkedHashMap<>();
        ArchiveMoveRecovery(List<Node> sources, Node target, String expected) {
            this.sources = List.copyOf(sources); this.target = target; this.expected = expected;
        }
    }
    List<Node> resumeArchiveMove(ArchiveMoveRecovery recovery) throws IOException {
        return moveArchiveBatch(recovery, null, node -> {});
    }
    private List<Node> moveArchiveBatch(ArchiveMoveRecovery recovery, Set<String> existingNames, Copied copied) throws IOException {
        List<Node> sources = recovery.sources; Node target = recovery.target;
        if (sources.isEmpty()) return List.of();
        for (Node source : sources) checkRemoval(source);
        Node container = sources.get(0).container;
        if (target.container != null && target.container.key().equals(container.key())) {
            List<Node> outputs = copyIntoArchive(sources, target, true, recovery.expected, ConflictPolicy.KEEP_BOTH, existingNames);
            for (Node output : outputs) copied.published(output);
            return outputs;
        }
        synchronized (archiveLock(container)) {
        boolean removed = false;
        try (ArchiveSnapshot snapshot = new ArchiveSnapshot(container, recovery.expected, target.container == null);
                    ArchiveReads reads = target.container == null ? new ArchiveReads() : null) {
                if (ArchiveOps.isSplitZip(snapshot.copy) && (!snapshot.directSplit || ArchiveOps.isEncrypted(snapshot.copy)))
                    throw new IOException("此分卷 ZIP 请先合并为单卷再移动内部文件");
                recovery.expected = snapshot.revision;
                Tasks.Progress.phase("传输文件");
                List<Node> outputs = new ArrayList<>();
                Map<Node, OutputVersion> versions = new LinkedHashMap<>();
                Map<Node, String> verifiedLocal = new LinkedHashMap<>();
                if (target.container != null) {
                    // A target archive publishes the complete batch in one rewrite.
                    if (recovery.published.isEmpty()) {
                        outputs.addAll(copyIntoArchive(sources, target, false, ConflictPolicy.KEEP_BOTH, existingNames));
                        for (int i = 0; i < sources.size(); i++) recovery.published.put(sources.get(i).key(), outputs.get(i));
                    } else for (Node source : sources) outputs.add(recovery.published.get(source.key()));
                } else {
                    Map<String, Set<String>> names = new HashMap<>();
                    if (existingNames != null) names.put(RootAccess.automatic(target).key(), new HashSet<>(existingNames));
                    for (Node source : sources) {
                        Tasks.Progress.phase("传输文件");
                        Node output = recovery.published.get(source.key());
                        MessageDigest copiedDigest = output == null && !source.directory ? sha256Digest() : null;
                        if (output == null) {
                            output = copy(source, target, false, names, ConflictPolicy.KEEP_BOTH, null, null, copiedDigest);
                            recovery.published.put(source.key(), output);
                        }
                        outputs.add(output);
                        Tasks.Progress.phase("校验移动输出");
                        String before = stableLocalVersion(output);
                        OutputVersion version = outputVersion(output);
                        String expected = copiedDigest == null ? contentSignature(source, new HashSet<>())
                                : "file:" + hexDigest(copiedDigest.digest());
                        if (!expected.equals(version.content)) throw new IOException("移动输出内容校验失败，来源保留");
                        if (before != null) {
                            if (!before.equals(stableLocalVersion(output))) throw new IOException("校验期间目标文件已变化，来源保留");
                            verifiedLocal.put(output, before);
                        }
                        versions.put(output, version); copied.published(output);
                    }
                }
                try (ArchiveSnapshot targetView = target.container == null ? null : new ArchiveSnapshot(target.container, null)) {
                    Tasks.Progress.phase("校验移动输出");
                    if (targetView != null) {
                        for (int i = 0; i < sources.size(); i++) {
                            if (!contentSignature(sources.get(i), new HashSet<>()).equals(contentSignature(outputs.get(i), new HashSet<>())))
                                throw new IOException("移动输出内容校验失败，来源保留");
                            copied.published(outputs.get(i));
                        }
                        targetView.verify();
                    } else for (Map.Entry<Node, OutputVersion> version : versions.entrySet()) {
                        Node output = version.getKey();
                        String stamp = verifiedLocal.get(output);
                        boolean unchanged = stamp != null ? stamp.equals(stableLocalVersion(output))
                                : version.getValue().equals(outputVersion(output));
                        if (!unchanged) throw new IOException("移动输出已被修改或替换，来源保留");
                    }
                    // removeEntries publishes against snapshot.revision, checking the live source before replacement.
                    snapshot.removeEntries(sources);
                    removed = true;
                }
                return outputs;
            } catch (IOException | RuntimeException error) {
                if (!removed && !recovery.published.isEmpty())
                    throw new Tasks.PartialTransferException(recovery, error);
                throw error;
            }
        }
    }
    record OutputVersion(String identity, String content) { }
    private static String stableLocalVersion(Node node) throws IOException {
        if (node.directory || node.file == null || node.root || Build.VERSION.SDK_INT < 27) return null;
        return PropertyChecksums.regularIdentity(node);
    }
    private void checkContainerOverwrite(Node source, Node target) throws IOException {
        for (Node ancestor = source.container; ancestor != null; ancestor = ancestor.container)
            if (sameStorageObject(ancestor, target))
                throw new IOException("不能覆盖来源文件所在的压缩包，请选择另存一份");
    }
    private boolean sameStorageObject(Node a, Node b) throws IOException {
        if (a.key().equals(b.key())) return true;
        if (a.container != null || b.container != null)
            return a.container != null && b.container != null && a.entry.equals(b.entry)
                    && sameStorageObject(a.container, b.container);
        File af = a.file != null ? a.file : DataDocumentsProvider.owns(a.uri) ? new File(DataDocumentsProvider.path(a.uri)) : null;
        File bf = b.file != null ? b.file : DataDocumentsProvider.owns(b.uri) ? new File(DataDocumentsProvider.path(b.uri)) : null;
        if (af != null && bf != null) {
            if (physical(af).equals(physical(bf))) return true;
            try { return fileIdentity(android.system.Os.stat(af.getPath())).equals(fileIdentity(android.system.Os.stat(bf.getPath()))); }
            catch (android.system.ErrnoException ignored) { }
        }
        if (a.uri != null && b.uri != null && Objects.equals(a.uri.getAuthority(), b.uri.getAuthority())) {
            try { if (DocumentsContract.getDocumentId(a.uri).equals(DocumentsContract.getDocumentId(b.uri))) return true; }
            catch (IllegalArgumentException ignored) { }
        }
        if (a.uri != null || b.uri != null) {
            String ai = storageObjectIdentity(a), bi = storageObjectIdentity(b);
            if (ai != null && ai.equals(bi)) return true;
        }
        return false;
    }
    private String storageObjectIdentity(Node node) {
        try {
            if (node.file != null) return node.root ? RootDirectoryReader.stat(node.path(), "%d:%i", true)
                    : fileIdentity(android.system.Os.stat(node.path()));
            try (android.os.ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(node.uri, "r")) {
                if (descriptor == null) return null;
                android.system.StructStat stat = android.system.Os.fstat(descriptor.getFileDescriptor());
                return android.system.OsConstants.S_ISREG(stat.st_mode) ? fileIdentity(stat) : null;
            }
        } catch (IOException | android.system.ErrnoException | RuntimeException ignored) { return null; }
    }
    OutputVersion outputVersion(Node node) throws IOException {
        String before = identity(node), content = contentSignature(node, new HashSet<>());
        if (!before.equals(identity(node))) throw new IOException("校验期间移动输出发生变化，来源保留");
        return new OutputVersion(before, content);
    }
    String contentSignature(Node node, Set<String> seen) throws IOException {
        Tasks.Progress.phase("校验文件");
        Tasks.Progress.check();
        if (!node.directory) try (InputStream input = read(node)) { return "file:" + digest(input); }
        if (!seen.add(node.key())) throw new IOException("目录包含循环引用");
        StringBuilder members = new StringBuilder();
        try {
            List<Node> children = list(node);
            children.sort(Comparator.comparing(child -> child.name));
            for (Node child : children) {
                checkName(child.name);
                members.append(child.name.length()).append(':').append(child.name).append(':').append(contentSignature(child, seen)).append('\n');
            }
            try (InputStream input = new ByteArrayInputStream(members.toString().getBytes(StandardCharsets.UTF_8))) { return "dir:" + digest(input); }
        } finally { seen.remove(node.key()); }
    }
    void checkCopy(Node source, Node parent) throws IOException {
        if (source.key().equals(parent.key())) throw new IOException("不能复制到自身");
        for (Node ancestor = parent; ancestor != null; ancestor = ancestor.container) {
            if (source.key().equals(ancestor.key())) throw new IOException("不能将归档放入自身");
            if (source.container != null && ancestor.container != null && source.directory
                    && source.container.key().equals(ancestor.container.key()) && ancestor.entry.startsWith(source.entry))
                throw new IOException("不能复制到自己的子目录或其包含的内层归档");
        }
        if (parent.container != null && source.file != null) {
            Node outer = parent.container;
            while (outer.container != null) outer = outer.container;
            // Compare by the physical path: a ROOT handle cannot be canonicalised by the app, but /storage/emulated/0
            // and /sdcard can still name the same archive.
            if (outer.file != null) {
                String archivePath = physical(outer.file), sourcePath = physical(source.file);
                if (archivePath.equals(sourcePath)) throw new IOException("不能将归档复制到自身");
                if (source.directory && archivePath.startsWith(sourcePath + "/"))
                    throw new IOException("不能将目录复制到其内部的归档");
            }
        }
        if (source.file != null && parent.file != null && source.directory) {
            String a = source.file.getCanonicalPath(), b = parent.file.getCanonicalPath();
            if (b.equals(a) || b.startsWith(a + "/")) throw new IOException("不能复制到自己的子目录");
        }
        if (source.uri != null && parent.uri != null && source.directory) {
            String a = DocumentsContract.getDocumentId(source.uri);
            String b = DocumentsContract.getDocumentId(parent.uri);
            boolean child = source.uri.getAuthority().equals(parent.uri.getAuthority())
                    && (b.equals(a) || b.startsWith(a + "/"));
            if (Build.VERSION.SDK_INT >= 29 && !child)
                child = DocumentsContract.isChildDocument(context.getContentResolver(), source.uri, parent.uri);
            if (child) throw new IOException("不能复制到自己的子目录");
        }
    }
    private void archiveAdditions(Node source, String entry, Map<String, File> additions, Map<Node, File> staged,
                                  Set<String> seen, Node container, MoveMembers members) throws IOException {
        if (members != null) members.record(source, archiveNode(container, entry, source.directory, source.size, source.modified));
        Tasks.Progress.step(source.name);
        String key = source.file != null ? source.file.getCanonicalPath() : source.key();
        if (!seen.add(key)) throw new IOException("目录包含循环引用");
        if (source.directory) {
            additions.put(entry, null);
            for (Node child : list(source)) archiveAdditions(child, entry + child.name + (child.directory ? "/" : ""), additions, staged, seen, container, members);
        } else {
            File payload = materialize(source); staged.put(source, payload); additions.put(entry, payload);
        }
        seen.remove(key);
    }
    /** Files eligible for deletion come from the enumeration made for this copy. A content
     * provider has no nonrecursive rmdir contract, so its empty source folders must be retained. */
    final class MoveMembers {
        private record Member(Node source, Node target, String version) { }
        private final List<Member> entries = new ArrayList<>();
        private final Set<String> completed = new HashSet<>();

        void record(Node source, Node target) throws IOException {
            if (source.container != null) throw new IOException("归档成员必须通过归档写回移除");
            if (source.file != null && (source.root
                    ? !RootDirectoryReader.stat(source.path(), "%f", false).matches(source.directory ? "4[0-9a-fA-F]{3}" : "8[0-9a-fA-F]{3}")
                    : Files.isSymbolicLink(source.file.toPath())))
                throw new IOException("跨存储移动不跟随符号链接：" + source.name);
            entries.add(new Member(source, target, source.directory ? directoryIdentity(source) : identity(source)));
        }
        private String directoryIdentity(Node node) throws IOException {
            if (node.uri != null) {
                if (!document(node.uri).directory) throw new IOException("来源目录已改变：" + node.name);
                return node.key();
            }
            if (node.root) return RootDirectoryReader.stat(node.path(), "%d:%i", false);
            try {
                var stat = android.system.Os.lstat(node.path());
                if (!android.system.OsConstants.S_ISDIR(stat.st_mode)) throw new IOException("来源目录已改变：" + node.name);
                return fileIdentity(stat);
            } catch (android.system.ErrnoException error) { throw new IOException(error); }
        }
        void remove(java.util.function.Consumer<Node> retained) throws IOException {
            Tasks.Progress.phase("删除来源");
            for (int i = entries.size() - 1; i >= 0; i--) {
                Member entry = entries.get(i);
                Node source = entry.source;
                if (completed.contains(source.key())) continue;
                Tasks.Progress.check();
                if (source.directory) {
                    if (!entry.version.equals(directoryIdentity(source))) throw new IOException("来源目录已改变：" + source.name);
                    if (source.uri != null) {
                        // Never call deleteDocument on a directory: it can recursively delete a late arrival.
                        if (retained != null) retained.accept(source);
                    } else {
                        try { removeSingleSource(source); }
                        catch (java.nio.file.DirectoryNotEmptyException kept) {
                            if (retained != null) retained.accept(source);
                        }
                    }
                } else {
                    // Re-read both ends before destructive cleanup (providers can silently truncate writes).
                    if (!entry.version.equals(identity(source))
                            || !contentSignature(source, new HashSet<>()).equals(contentSignature(entry.target, new HashSet<>()))
                            || !entry.version.equals(identity(source)))
                        throw new IOException("来源或目标文件已变化，已保留来源：" + source.name);
                    removeSingleSource(source);
                }
                completed.add(source.key());
            }
        }
    }
    private void removeSingleSource(Node source) throws IOException {
        Tasks.Progress.phase("删除来源");
        checkRemoval(source);
        Tasks.Progress.check();
        if (source.root) RootMutations.removeEmpty(context, source);
        else if (source.file != null) Files.delete(source.file.toPath());
        else if (source.directory || document(source.uri).directory
                || !DocumentsContract.deleteDocument(context.getContentResolver(), source.uri))
            throw new IOException("未能安全移除来源：" + source.name);
    }
    final class CopyLedger {
        private static final class Owned {
            String identity;
            MessageDigest written;
            private String digest;
            Owned(String identity, MessageDigest written) { this.identity = identity; this.written = written; }
            String digest() {
                if (digest == null) digest = hexDigest(written.digest());
                return digest;
            }
        }
        private final Map<Node, Owned> created = new LinkedHashMap<>();
        void record(Node node) throws IOException {
            // A document ID can name a replaced file. SAF ownership is recorded from the actual
            // writable descriptor below, avoiding another provider open on the ordinary copy path.
            String owned = node.uri != null ? null
                    : node.root && node.identity != null ? node.identity : identity(node);
            created.put(node, new Owned(owned, null));
        }
        private String identity(Node node) throws IOException {
            if (node.uri != null) return storageObjectIdentity(node);
            if (node.root) return RootDirectoryReader.stat(node.path(), "%d:%i", false);
            try {
                android.system.StructStat stat = android.system.Os.lstat(node.path());
                return fileIdentity(stat);
            } catch (android.system.ErrnoException error) { throw new IOException(error); }
        }
        void rollback(Exception failure) {
            List<Node> nodes = new ArrayList<>(created.keySet());
            Collections.reverse(nodes);
            for (Node node : nodes) try { Tasks.Progress.cleanup(() -> {
                // deleteDocument(directory) is recursive, including on a newly created directory.
                // SAF has no atomic nonrecursive rmdir, so preserve every directory at every level.
                if (node.uri != null && node.directory) return;
                Owned owned = created.get(node);
                if (owned.identity == null || !owned.identity.equals(identity(node))) return;
                if (node.uri != null) {
                    // Preserve a document replaced by a directory or edited by another writer.
                    if (document(node.uri).directory || !contentSignature(node, new HashSet<>()).equals("file:" + owned.digest())
                            || !owned.identity.equals(identity(node))) return;
                    if (!DocumentsContract.deleteDocument(context.getContentResolver(), node.uri))
                        throw new IOException("无法清理未完成的复制：" + node.name);
                } else if (node.root) {
                    RootMutations.removeEmpty(context, node);
                } else Files.deleteIfExists(node.file.toPath());
            });
            } catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        }
        OutputStream recording(Node node, OutputStream output) {
            Owned owned = created.get(node);
            if (owned == null || node.uri == null || node.directory || !(output instanceof FileOutputStream file)) return output;
            try {
                android.system.StructStat stat = android.system.Os.fstat(file.getFD());
                if (!android.system.OsConstants.S_ISREG(stat.st_mode)) return output;
                owned.identity = fileIdentity(stat); owned.written = sha256Digest();
                return new java.security.DigestOutputStream(output, owned.written);
            } catch (IOException | android.system.ErrnoException unavailable) { return output; }
        }
    }
    private InputStream readCopy(Node source, MessageDigest digest) throws IOException {
        InputStream input = read(source);
        return digest == null ? input : new java.security.DigestInputStream(input, digest);
    }
    private void copyContent(Node source, Node target, Set<String> seen, CopyLedger ledger, MessageDigest digest, MoveMembers members) throws IOException {
        Tasks.Progress.phase("复制文件");
        if (members != null) members.record(source, target);
        Tasks.Progress.step(source.name);
        String key = source.file != null ? source.file.getCanonicalPath() : source.key();
        if (!seen.add(key)) throw new IOException("目录包含循环引用");
        if (source.directory) {
            List<Node> children = list(source);
            Tasks.Progress.discovered(source, children);
            for (Node n : children) {
                Node made = createAbsent(target, n.name, n.directory);
                ledger.record(made);
                copyContent(n, made, seen, ledger, null, members);
            }
        } else {
            // Targets are newly created; direct streaming needs no second complete cache copy.
            if (target.root) {
                if (copyCreatedFileLocally(source, target, digest)) {
                    // The newly created inode remains the ledger's owned inode.
                } else {
                    long size = copySourceSize(source);
                    try (InputStream in = readCopy(source, digest)) { RootMutations.writeEmpty(context, target, in, size); }
                }
            } else try (InputStream in = readCopy(source, digest);
                        OutputStream out = target.uri != null
                                ? context.getContentResolver().openOutputStream(target.uri, "wt")
                                : new BufferedOutputStream(new FileOutputStream(target.file))) {
                if (out == null) throw new IOException("目标目录只读");
                pump(in, ledger.recording(target, out));
            }
        }
        FileTimes.copied(context,source,target);
        seen.remove(key);
    }
    /** Fast path for a newly created ROOT target also writable by the app, with ROOT inode guards. */
    private boolean copyCreatedFileLocally(Node source, Node target, MessageDigest digest) throws IOException {
        if (source.directory || target.file == null
                || target.identity == null || target.parentIdentity == null) return false;
        java.io.FileDescriptor descriptor;
        try {
            descriptor = openEmptyCopyTarget(target);
        } catch (android.system.ErrnoException unavailable) { return false; }
        if (descriptor == null) return false;
        try (FileOutputStream output = SafRecovery.ownedOutput(descriptor)) {
            android.system.StructStat opened = android.system.Os.fstat(output.getFD());
            if (!android.system.OsConstants.S_ISREG(opened.st_mode) || opened.st_size != 0 || opened.st_nlink != 1
                    || !target.identity.equals(fileIdentity(opened))
                    || !target.parentIdentity.equals(fileIdentity(android.system.Os.stat(target.file.getParent())))) return false;
            if (!target.identity.equals(RootDirectoryReader.stat(target.path(), "%d:%i", true)))
                throw new IOException("新建目标身份已变化，复制已停止");
            long expectedSize = copySourceSize(source);
            try (InputStream input = readCopy(source, digest)) { pump(input, output); }
            output.flush(); output.getFD().sync();
            if (expectedSize >= 0 && android.system.Os.fstat(output.getFD()).st_size != expectedSize)
                throw new IOException("来源长度在复制期间发生变化");
            String[] current = RootDirectoryReader.identities(target.path(), target.file.getParent());
            if (!target.identity.equals(current[0]) || !target.parentIdentity.equals(current[1]))
                throw new IOException("复制期间目标位置已变化");
            return true;
        } catch (android.system.ErrnoException error) { throw new IOException(error); }
    }
    private java.io.FileDescriptor openEmptyCopyTarget(Node target) throws IOException, android.system.ErrnoException {
        java.io.FileDescriptor raw = android.system.Os.open(target.path(), RootFileOps.O_PATH
                | android.system.OsConstants.O_NOFOLLOW | android.system.OsConstants.O_NONBLOCK, 0);
        try (android.os.ParcelFileDescriptor pinned = android.os.ParcelFileDescriptor.dup(raw)) {
            android.system.StructStat observed = android.system.Os.fstat(pinned.getFileDescriptor());
            if (!android.system.OsConstants.S_ISREG(observed.st_mode) || observed.st_size != 0 || observed.st_nlink != 1
                    || !target.identity.equals(fileIdentity(observed))) return null;
            return android.system.Os.open("/proc/self/fd/" + pinned.getFd(),
                    android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_NONBLOCK, 0);
        } finally { android.system.Os.close(raw); }
    }
    private long copySourceSize(Node source) throws IOException {
        if (source.container != null) {
            ArchiveReads copying = copyingArchives.get();
            if (copying != null) return copying.reader(source.container).size(source.entry);
            File archive = archiveView(source.container);
            try (ArchiveOps.ReadSession reader = new ArchiveOps.ReadSession(archive)) { return reader.size(source.entry); }
            finally { release(source.container, archive); }
        }
        if (source.file == null) return -1;
        return source.root ? Long.parseLong(RootDirectoryReader.stat(source.path(), "%s", true)) : source.file.length();
    }
    Node extract(List<Node> sources, Node parent, String folderName) throws IOException {
        Tasks.Progress.budget("准备解压", -1);
        if (sources.isEmpty()) throw new IOException("请选择压缩包或包内条目");
        checkName(folderName);
        if (parent.container != null) throw new IOException("请选择压缩包以外的解压目录");
        parent = RootAccess.automatic(parent);
        Node output = create(parent, unique(parent, folderName), true);
        File staged = null;
        try (FileTimes.Scope times=new FileTimes.Scope(context)) {
            if (sources.size() == 1 && sources.get(0).container == null) {
                Node source = sources.get(0);
                File archive = archiveView(source);
                try {
                    if (output.file != null && !output.root) ArchiveOps.extract(archive, "", output.file);
                    else {
                        staged = Files.createTempDirectory(context.getCacheDir().toPath(), "extract-").toFile();
                        ArchiveOps.extract(archive, "", staged);
                        List<Node> stagedChildren = list(local(staged));
                        Tasks.Progress.replan(stagedChildren, "写入解压目录");
                        copyAll(stagedChildren, output, false);
                    }
                } finally { release(source, archive); }
            } else {
                try (ArchiveReads reads = new ArchiveReads()) {
                    for (Node source : sources) {
                        if (source.container == null) throw new IOException("请选择同一压缩包内的条目");
                        // Reuse each private archive snapshot for the whole extraction.
                        File archive = archiveView(source.container);
                        if (staged == null) staged = Files.createTempDirectory(context.getCacheDir().toPath(), "extract-").toFile();
                        ArchiveOps.extract(archive, source.entry, staged, true);
                    }
                }
                List<Node> stagedChildren = list(local(staged));
                Tasks.Progress.replan(stagedChildren, "写入解压目录");
                copyAll(stagedChildren, output, false);
            }
            return output;
        } catch (IOException | RuntimeException error) {
            // This directory is user-visible while extraction runs. A late arrival or edit
            // must survive cancellation/failure; only the private staging area is disposable.
            throw error;
        } finally {
            if (staged != null) {
                File temporary = staged;
                Tasks.Progress.cleanup(() -> delete(local(temporary)));
            }
        }
    }
    String unique(Node parent, String original) throws IOException {
        Set<String> names = new HashSet<>();
        for (Node n : list(parent)) names.add(n.name);
        return unique(names, original);
    }
    private static String unique(Set<String> names, String original) {
        if (!names.contains(original)) return original;
        int dot = original.lastIndexOf('.');
        String base = dot > 0 ? original.substring(0, dot) : original;
        String ext = dot > 0 ? original.substring(dot) : "";
        for (int i = 1;; i++) {
            String n = base + " (" + i + ")" + ext;
            if (!names.contains(n)) return n;
        }
    }
    File materialize(Node n) throws IOException {
        n = RootAccess.automatic(n);
        if (n.file != null && !n.root) return n.file;
        File f = File.createTempFile("import-", "-" + n.name.replaceAll("[^a-zA-Z0-9.]", "_"), context.getCacheDir());
        try (Tasks.Progress.Phase stage = Tasks.Progress.stage("准备来源");
             InputStream in = read(n); OutputStream out = new FileOutputStream(f)) { pump(in, out); }
        catch (IOException | RuntimeException e) { Files.deleteIfExists(f.toPath()); throw e; }
        if (n.file != null) ArchiveOps.copyPassword(n.file, f);
        return f;
    }
    void release(Node source, File file) throws IOException {
        ArchiveReads copying = copyingArchives.get();
        if (copying != null && copying.retains(file)) return;
        if (file.equals(archiveSnapshots.get(source.key()))) return;
        File volumes=splitStaging.remove(file);
        if(volumes!=null){try(var paths=Files.walk(volumes.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}ArchiveOps.clearPassword(file);ArchiveOps.forget(file);return;}
        if (source.file == null || !source.file.equals(file)) {
            ArchiveOps.clearPassword(file);
            ArchiveOps.forget(file);
            Files.deleteIfExists(file.toPath());
        }
    }
    File archiveView(Node container) throws IOException {
        ArchiveReads copying = copyingArchives.get();
        return copying == null ? uncachedArchiveView(container) : copying.view(container).file;
    }
    private final Map<File,File> splitStaging=new java.util.concurrent.ConcurrentHashMap<>();
    private File uncachedArchiveView(Node container) throws IOException {
        try (Tasks.Progress.Phase stage = Tasks.Progress.stage("准备归档")) {
        File snapshot = Boolean.TRUE.equals(freshArchiveReads.get()) ? null : archiveSnapshots.get(container.key());
        if (snapshot != null) return snapshot;
        Node node = RootAccess.automatic(container);
        if (node.file != null && node.container == null && node.file.isFile() && node.file.canRead()) return node.file;
        // Protected and provider-backed inputs keep private lifetimes; metadata alone cannot prove their freshness.
        File staged=materialize(node);
        if(staged.equals(node.file))return staged;
        if(!ArchiveOps.isSplitZip(staged))return staged;
        checkName(node.name);
        File folder=Files.createTempDirectory(context.getCacheDir().toPath(),"split-archive-").toFile();
        File main=new File(folder,node.name);
        try{
            Files.move(staged.toPath(),main.toPath());ArchiveOps.copyPassword(staged,main);ArchiveOps.clearPassword(staged);ArchiveOps.forget(staged);
            Map<String,Node> siblings=new HashMap<>();Node parent=parent(node);if(parent==null)throw new IOException("无法定位 ZIP 分卷所在目录");for(Node sibling:list(parent))siblings.put(sibling.name,sibling);
            try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(main)){
                for(File part:zip.getSplitZipFiles()){if(part.equals(main))continue;Node sibling=siblings.get(part.getName());if(sibling==null||sibling.directory)throw new IOException("缺少 ZIP 分卷："+part.getName());
                    try(InputStream in=read(sibling);OutputStream out=new FileOutputStream(part)){pump(in,out);}}
            }
            splitStaging.put(main,folder);return main;
        }catch(IOException|RuntimeException error){try(var paths=Files.walk(folder.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}Files.deleteIfExists(staged.toPath());throw error;}
        }
    }
    final class ArchiveReads implements AutoCloseable {
        private final boolean owner = copyingArchives.get() == null;
        private final Map<String, Source> sources = new LinkedHashMap<>();
        private final class Source {
            final Node node;
            final File file;
            ArchiveOps.ReadSession reader;
            Source(Node node, File file) { this.node = node; this.file = file; }
        }
        ArchiveReads() { if (owner) copyingArchives.set(this); }
        Source view(Node node) throws IOException {
            Source source = sources.get(node.key());
            if (source == null) {
                source = new Source(node, uncachedArchiveView(node));
                sources.put(node.key(), source);
            }
            return source;
        }
        ArchiveOps.ReadSession reader(Node node) throws IOException {
            Source source = view(node);
            if (source.reader == null) source.reader = new ArchiveOps.ReadSession(source.file);
            return source.reader;
        }
        boolean retains(File file) {
            for (Source source : sources.values()) if (source.file.equals(file)) return true;
            return false;
        }
        @Override public void close() throws IOException {
            if (!owner) return;
            copyingArchives.remove();
            IOException failure = null;
            List<Source> reverse = new ArrayList<>(sources.values()); Collections.reverse(reverse);
            for (Source source : reverse) {
                try { if (source.reader != null) source.reader.close(); }
                catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
                try { release(source.node, source.file); }
                catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            sources.clear();
            if (failure != null) throw failure;
        }
    }
    /** A bounded-lifetime, verified source view. Real-source fingerprint checks bypass this map. */
    final class ArchiveSnapshot implements AutoCloseable {
        final Node source;
        final String revision;
        private File copy;
        private final FileState directState;
        private final String directVersion;
        private final boolean directSplit;
        ArchiveSnapshot(Node source, String expected) throws IOException {
            this(source, expected, false);
        }
        ArchiveSnapshot(Node source, String expected, boolean allowDirectZip) throws IOException {
            try (Tasks.Progress.Phase stage = Tasks.Progress.stage("准备归档")) {
            this.source = source;
            Node direct = RootAccess.automatic(source);
            directSplit = SplitZipEdits.canEdit(direct);
            if (directSplit) {
                directState = null;
                directVersion = null;
                copy = direct.file;
                revision = SplitZipEdits.fingerprint(copy);
                if (expected != null && !expected.equals(revision)) throw new IOException("归档分卷已变化，请重新选择");
                return;
            }
            if (allowDirectZip && source.file != null && !source.root && Build.VERSION.SDK_INT >= 27
                    && source.name.toLowerCase(Locale.ROOT).endsWith(".zip")
                    && !Files.isSymbolicLink(source.file.toPath()) && source.file.getParentFile().canWrite()
                    && !ArchiveOps.isEncrypted(source.file) && !ArchiveOps.isSplitZip(source.file)) {
                directVersion = PropertyChecksums.regularIdentity(source);
                directState = FileState.read(source.file);
                revision = directState.fingerprint();
                verifyDirect();
                if (expected != null && !expected.equals(revision)) throw new IOException("归档来源已变化，请刷新后重新选择");
                copy = source.file;
                return;
            }
            directState = null;
            directVersion = null;
            Node identitySource = source;
            while (identitySource.container != null) identitySource = identitySource.container;
            identitySource = RootAccess.automatic(identitySource);
            String before = identity(identitySource);
            String nestedRevision = source.container == null ? null : fingerprint(source);
            copy = File.createTempFile("archive-snapshot-", "-" + source.name.replaceAll("[^a-zA-Z0-9.]", "_"), context.getCacheDir());
            try {
                String copied;
                try (InputStream in = read(source); OutputStream out = new FileOutputStream(copy)) { copied = pumpAndDigest(in, out); }
                if (!before.equals(identity(identitySource))) throw new IOException("读取期间归档来源已变化");
                revision = nestedRevision == null ? before + ":" + copied : nestedRevision;
                if (nestedRevision != null) checkRevision(source, nestedRevision);
                if (expected != null && !expected.equals(revision)) throw new IOException("归档来源已变化，请刷新后重新选择");
                if (source.file != null) ArchiveOps.copyPassword(source.file, copy);
                if(ArchiveOps.isSplitZip(copy)) {
                    checkName(source.name);File folder=Files.createTempDirectory(context.getCacheDir().toPath(),"split-snapshot-").toFile();
                    File main=new File(folder,source.name);
                    try {
                        String completeRevision=ArchiveCopies.copy(FileStore.this,source,main,()->false);
                        if(!revision.equals(completeRevision))throw new IOException("读取期间归档来源已变化");
                        release(source,copy);copy=main;splitStaging.put(main,folder);
                    }catch(IOException|RuntimeException error){try(var paths=Files.walk(folder.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}catch(IOException cleanup){error.addSuppressed(cleanup);}throw error;}
                }
                archiveSnapshots.put(source.key(), copy);
            } catch (IOException | RuntimeException e) { release(source, copy); throw e; }
            }
        }
        void verify() throws IOException { checkRevision(source, revision); }
        private void verifyDirect() throws IOException {
            if (!directVersion.equals(PropertyChecksums.regularIdentity(source)))
                throw new IOException("归档来源已变化，请刷新后重新选择");
        }
        void removeEntries(Collection<Node> entries) throws IOException {
            Set<String> removals = archiveRemovals(source, entries);
            if (directSplit) {
                SplitZipEdits.rewrite(copy, revision, Collections.emptyMap(), Collections.emptyMap(), removals);
                return;
            }
            if (directState != null) {
                ArchiveOps.removeVerified(copy, removals, directState, this::verifyDirect);
                return;
            }
            // Rewrite the verified private snapshot in place; write() still checks the live
            // source revision before publishing it, including same-size/same-time edits.
            ArchiveOps.rewriteBatch(copy, Collections.emptyMap(), removals);
            write(source, copy, revision);
        }
        public void close() throws IOException {
            if (directState == null && !directSplit) { archiveSnapshots.remove(source.key()); release(source, copy); }
        }
    }
    static void checkRemoval(Node node) throws IOException {
        if (node.file != null && RootAccess.protectedStorageDirectory(node.file))
            throw new IOException("不能删除、移动或重命名存储根目录及 Android/data、Android/obb");
    }
    private Object archiveLock(Node node) throws IOException {
        while (node.container != null) node = node.container;
        return node.file != null ? ArchiveOps.lock(node.file) : ArchiveOps.lock(node.key());
    }
    private void editArchive(Node source, String remove, String rename, Map<String, File> additions) throws IOException {
        editArchive(source, remove, rename, additions, null);
    }
    private void editArchive(Node source, String remove, String rename, Map<String, File> additions, String expected) throws IOException {
        editArchive(source, remove, rename, additions, expected, Collections.emptySet());
    }
    private void editArchive(Node source, String remove, String rename, Map<String, File> additions, String expected,
                             Set<String> removals) throws IOException {
        try (Tasks.Progress.Phase stage = Tasks.Progress.stage("准备归档")) {
            synchronized (archiveLock(source)) {
                checkWritable(source);
                source = RootAccess.automatic(source);
                String baseline = expected == null ? fingerprint(source) : expected;
                checkRevision(source, baseline);
                if (SplitZipEdits.canEdit(source)) {
                    Map<String, String> renames = rename == null ? Collections.emptyMap() : Map.of(remove, rename);
                    Set<String> deleted = new HashSet<>(removals);
                    if (remove != null && rename == null) deleted.add(remove);
                    SplitZipEdits.rewrite(source.file, baseline, renames, additions, deleted);
                    return;
                }
                File archive = File.createTempFile("archive-edit-", "-" + source.name.replaceAll("[^a-zA-Z0-9.]", "_"), context.getCacheDir());
                try {
                    try (InputStream input = read(source); OutputStream output = new FileOutputStream(archive)) { pump(input, output); }
                    if (source.file != null) ArchiveOps.copyPassword(source.file, archive);
                    checkRevision(source, baseline);
                    Tasks.Progress.phase("重建归档");
                    if (removals.isEmpty()) ArchiveOps.rewrite(archive, remove, rename, additions);
                    else ArchiveOps.rewriteBatch(archive, additions, removals);
                    write(source, archive, baseline);
                } finally { release(source, archive); }
            }
        }
    }
    static void pump(InputStream in, OutputStream out) throws IOException {
        pump(in, out, -1);
    }
    static void pump(InputStream in, OutputStream out, long expectedSize) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        int n; long total = 0;
        while (true) {
            Tasks.Progress.check();
            n = in.read(buffer); if (n == -1) break; if (n == 0) continue;
            Tasks.Progress.check();
            if (expectedSize >= 0 && n > expectedSize - total) throw new IOException("来源超过声明长度");
            out.write(buffer, 0, n); Tasks.Progress.bytes(n); total += n;
        }
        out.flush();
        if (expectedSize >= 0 && total != expectedSize) throw new IOException("写入内容长度不完整");
    }
    private static String pumpAndDigest(InputStream in, OutputStream out) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[128 * 1024]; int count;
        while ((count = in.read(buffer)) != -1) if (count > 0) {
            Tasks.Progress.check(); out.write(buffer, 0, count); digest.update(buffer, 0, count); Tasks.Progress.bytes(count);
        }
        out.flush(); return hexDigest(digest.digest());
    }
    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hexDigest(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int b = value & 255; hex.append("0123456789abcdef".charAt(b >>> 4)).append("0123456789abcdef".charAt(b & 15));
        }
        return hex.toString();
    }
    static byte[] readBytes(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 128 * 1024));
        byte[] buffer = new byte[Math.min(128 * 1024, Math.max(1, limit))];
        int total = 0;
        while (total < limit) {
            int n = in.read(buffer, 0, Math.min(buffer.length, limit - total));
            if (n < 0) break;
            if (n == 0) continue;
            out.write(buffer, 0, n); total += n;
        }
        return out.toByteArray();
    }
    static void skip(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped > 0) { left -= skipped; continue; }
            if (in.read() < 0) throw new EOFException("文件已到末尾");
            left--;
        }
    }
    record FileState(long device, long inode, long size, long modified, int mode, int uid, int gid, String digest) {
        private static FileState metadata(File file) throws IOException {
            try {
                android.system.StructStat stat = android.system.Os.lstat(file.getAbsolutePath());
                if (android.system.OsConstants.S_ISLNK(stat.st_mode))
                    throw new IOException("不能直接保存符号链接，请打开真实文件");
                if (!android.system.OsConstants.S_ISREG(stat.st_mode)) throw new IOException("保存目标不是普通文件");
                return new FileState(stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime,
                        stat.st_mode & 07777, stat.st_uid, stat.st_gid, null);
            } catch (android.system.ErrnoException error) { throw new IOException(error); }
        }
        static FileState read(File file) throws IOException {
            FileState before = metadata(file);
            String digest;
            try (InputStream input = new FileInputStream(file)) { digest = FileStore.digest(input); }
            if (!before.equals(metadata(file))) throw new IOException("读取期间文件已变化");
            return new FileState(before.device, before.inode, before.size, before.modified,
                    before.mode, before.uid, before.gid, digest);
        }
        String fingerprint() {
            return device + ":" + inode + ":" + size + ":" + modified + ":" + Integer.toOctalString(mode)
                    + ":" + uid + ":" + gid + ":" + digest;
        }
    }
    static void publish(File tmp, File target, FileState before) throws IOException {
        try {
            FileState staged = FileState.metadata(tmp);
            if ((staged.mode & 0200) == 0) android.system.Os.chmod(tmp.getAbsolutePath(), staged.mode | 0200);
            try (RandomAccessFile output = new RandomAccessFile(tmp, "rw")) { output.getFD().sync(); }
            if (staged.uid != before.uid || staged.gid != before.gid)
                android.system.Os.chown(tmp.getAbsolutePath(), before.uid, before.gid);
            if (FileState.metadata(tmp).mode != before.mode) android.system.Os.chmod(tmp.getAbsolutePath(), before.mode);
            FileState ready = FileState.metadata(tmp);
            if (ready.uid != before.uid || ready.gid != before.gid || ready.mode != before.mode)
                throw new IOException("无法保留原文件权限，原文件未替换");
            if (!before.equals(FileState.read(target))) throw new IOException("保存目标已在外部改变，请重新打开");
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (android.system.ErrnoException error) { throw new IOException(error); }
    }
    static void replace(File tmp, File to) throws IOException {
        try { Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static void checkName(String name) throws IOException {
        if (name.trim().isEmpty() || name.equals(".") || name.equals("..") ||
                name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new IOException("文件名无效");
    }
    static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    static String rootWriteCommand(String apk, String path, String temporary, long size, String expected) {
        return "CLASSPATH=" + quote(apk) + " /system/bin/app_process /system/bin " + RootWriter.class.getName() + " "
                + quote(path) + " " + quote(temporary) + " " + size + " " + quote(expected == null ? "" : expected);
    }
    static String shell(String command, boolean root) throws IOException {
        ShellRunner.Result result = new ShellRunner().run(command, null, root, 45000, null, false);
        if (result.timedOut()) throw new IOException("命令超过 45 秒，已停止");
        if (result.exitCode() != 0) throw new IOException("exit " + result.exitCode() + "\n" + result.output());
        return result.output();
    }
    private static void await(Process p) throws IOException {
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("ROOT 写入超时"); }
            if (p.exitValue() != 0) throw new IOException("ROOT 写入失败");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    static String size(long n) {
        if (n < 0) return "?";
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.US, "%.1f K", n / 1024.0);
        if (n < 1073741824L) return String.format(Locale.US, "%.1f M", n / 1048576.0);
        return String.format(Locale.US, "%.1f G", n / 1073741824.0);
    }
}
