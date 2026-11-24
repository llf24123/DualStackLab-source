package org.example.filemanager;

import net.lingala.zip4j.model.FileHeader;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

final class ArchiveOps {
    private static final Object CACHE_LOCK = new Object();
    private static final Map<String, java.lang.ref.WeakReference<Object>> locks = new HashMap<>();
    static Object lock(String key) {
        synchronized (locks) {
            if (locks.size() > 256) locks.entrySet().removeIf(e -> e.getValue().get() == null);
            var reference = locks.get(key); Object monitor = reference == null ? null : reference.get();
            if (monitor == null) { monitor = new Object(); locks.put(key, new java.lang.ref.WeakReference<>(monitor)); }
            return monitor;
        }
    }
    static Object lock(File file) throws IOException { return lock(file.getCanonicalPath()); }

    interface RewriteBoundary { void beforeRewrite(File archive) throws IOException; }
    static volatile RewriteBoundary testRewriteBoundary;
    record Item(String path, String name, boolean directory, long size, long modified) {}
    private record Stamp(long size, long modified, long device, long inode) {}
    private record Index(Stamp stamp, Map<String, List<Item>> folders) {}
    private static Stamp stamp(File file) throws IOException {
        try {
            android.system.StructStat stat = android.system.Os.stat(file.getPath());
            return new Stamp(stat.st_size, file.lastModified(), stat.st_dev, stat.st_ino);
        } catch (android.system.ErrnoException error) { throw new IOException(error); }
    }
    private static final Map<String, Index> indexes = new LinkedHashMap<>(8, .75f, true);
    private static final Map<String, char[]> passwords = new HashMap<>();
    static final class PasswordRequired extends IOException {
        final File file;
        PasswordRequired(File file) { super("此归档已加密，请输入正确密码"); this.file = file; }
    }
    static synchronized void setPassword(File file, char[] password) {
        try { passwords.put(file.getCanonicalPath(), password.clone()); } catch (IOException ignored) { }
    }
    static synchronized void copyPassword(File source, File staged) {
        char[] value = password(source);
        if (value != null) setPassword(staged, value);
    }
    static synchronized void clearPassword(File file) {
        try {
            char[] removed = passwords.remove(file.getCanonicalPath());
            if (removed != null) Arrays.fill(removed, '\0');
        } catch (IOException ignored) { }
    }
    static synchronized char[] password(File file) {
        try { return passwords.get(file.getCanonicalPath()); } catch (IOException ignored) { return null; }
    }
    private static boolean encrypted(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("encrypted");
    }
    static boolean isEncrypted(File file) {
        if (isRar(file)) return RarArchiveOps.encrypted(file);
        if (ArchiveFormats.sevenZ(file)) return ArchiveFormats.encryptedSevenZ(file);
        return isEncryptedZip(file);
    }
    private record SplitStamp(Stamp stamp,boolean split){}
    private static final Map<String,SplitStamp> splitChecks=new LinkedHashMap<>(8,.75f,true);
    static boolean isSplitZip(File file)throws IOException {
        String key=file.getCanonicalPath();Stamp current=stamp(file);
        synchronized(CACHE_LOCK){SplitStamp checked=splitChecks.get(key);if(checked!=null&&checked.stamp.equals(current))return checked.split;}
        boolean split=false;
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            int length=(int)Math.min(in.length(),65557);byte[] tail=new byte[length];in.seek(in.length()-length);in.readFully(tail);
            for(int i=length-22;i>=0;i--)if(tail[i]==0x50&&tail[i+1]==0x4b&&tail[i+2]==5&&tail[i+3]==6){
                int comment=(tail[i+20]&255)|((tail[i+21]&255)<<8);if(i+22+comment!=length)continue;
                split=tail[i+4]!=0||tail[i+5]!=0||tail[i+6]!=0||tail[i+7]!=0;break;
            }
        }
        synchronized(CACHE_LOCK){splitChecks.put(key,new SplitStamp(current,split));while(splitChecks.size()>8)splitChecks.remove(splitChecks.keySet().iterator().next());}return split;
    }
    static synchronized boolean hasPassword(File file) { return password(file) != null; }
    private static boolean isEncryptedZip(File file) {
        if (isRar(file) || ArchiveFormats.supported(file)) return false;
        try {
            String key = file.getCanonicalPath(); Stamp current = stamp(file);
            Fallback checked; synchronized (CACHE_LOCK) { checked = encryptionChecks.get(key); }
            if (checked != null && checked.stamp.equals(current)) return checked.encrypted;
            try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(file)) {
                boolean encrypted = zip.isEncrypted();
                synchronized (CACHE_LOCK) {
                    encryptionChecks.put(key, new Fallback(current, encrypted));
                    while (encryptionChecks.size() > CACHE_LIMIT) encryptionChecks.remove(encryptionChecks.keySet().iterator().next());
                }
                return encrypted;
            }
        }
        catch (Exception ignored) { return false; }
    }
    private static void cachePassword(File archive, char[] password) {
        if (password != null && password.length > 0) setPassword(archive, password);
    }
    static List<Item> list(File file, String prefix) throws IOException {
        synchronized (lock(file)) { return listLocked(file, prefix); }
    }
    private static List<Item> listLocked(File file, String prefix) throws IOException {
        if (isRar(file)) return cachedFolder(file, prefix, () -> RarArchiveOps.list(file, prefix));
        if (ArchiveFormats.splitSevenZ(file)) return ArchiveFormats.list(file, prefix);
        if (ArchiveFormats.supported(file)) return cachedFolder(file, prefix, () -> ArchiveFormats.list(file, prefix));
        if(isSplitZip(file))return cachedFolder(file,prefix,()->listZip4j(file,prefix));
        String key = file.getCanonicalPath();
        Stamp current = stamp(file);
        Fallback fallback;
        synchronized (CACHE_LOCK) {
            Index cached = indexes.get(key);
            if (cached != null && cached.stamp.equals(current))
                return new ArrayList<>(cached.folders.getOrDefault(prefix, Collections.emptyList()));
            if (cached != null) { indexes.remove(key); indexItems.remove(key); }
            fallback = fallbacks.get(key);
            if (fallback != null && !fallback.stamp.equals(current)) { fallbacks.remove(key); fallback = null; }
        }
        final Fallback selectedFallback = fallback;
        if (fallback != null && fallback.stamp.equals(current))
            return cachedFolder(file, prefix, () -> selectedFallback.encrypted ? listZip4j(file, prefix) : listPrefix(file, prefix));
        Map<String, Map<String, Item>> folders = new HashMap<>();
        Set<String> names = new HashSet<>();
        int indexed = 0;
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = zipPath(entry.getName());
                if (!names.add(path)) throw new IOException("归档存在规范化后重名的条目：" + path);
                String parent = "";
                String[] pieces = path.split("/");
                for (int i = 0; i < pieces.length; i++) {
                    String name = pieces[i]; if (name.isEmpty()) throw new IOException("归档路径包含空目录名");
                    boolean folder = i < pieces.length - 1 || path.endsWith("/");
                    String child = parent + name + (folder ? "/" : "");
                    Map<String, Item> children = folders.computeIfAbsent(parent, p -> new LinkedHashMap<>());
                    if (!children.containsKey(child)) {
                        children.put(child, new Item(child, name, folder, folder ? 0 : entry.getSize(), entry.getTime()));
                        if (++indexed > INDEX_LIMIT) {
                            rememberFallback(key, current, false);
                            return cachedFolder(file, prefix, () -> listPrefix(file, prefix));
                        }
                    }
                    parent = child;
                }
            }
        } catch (ZipException error) {
            if (encrypted(error.getMessage())) {
                rememberFallback(key, current, true);
                return cachedFolder(file, prefix, () -> listZip4j(file, prefix));
            }
            throw error;
        }
        Map<String, List<Item>> lists = new HashMap<>();
        folders.forEach((parent, items) -> {
            List<Item> children = new ArrayList<>(items.values());
            children.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : NaturalOrder.compare(a.name, b.name));
            lists.put(parent, children);
        });
        int total = lists.values().stream().mapToInt(List::size).sum();
        if (total <= INDEX_LIMIT) {
            synchronized (CACHE_LOCK) {
                indexes.put(key, new Index(current, lists)); indexItems.put(key, total); trimIndexes();
            }
        }
        return new ArrayList<>(lists.getOrDefault(prefix, Collections.emptyList()));
    }
    /** Whole-archive ZIP indexes up to this many entries; one APK of this size costs a few MiB of heap. */
    private static final int INDEX_LIMIT = 60000, INDEX_BUDGET = 120000, FOLDER_BUDGET = 30000;
    private static final int CACHE_LIMIT = 8, PREFIX_LIMIT = 32;
    private static final Map<String, Integer> indexItems = new HashMap<>();
    private record Folders(Stamp stamp, Map<String, List<Item>> lists) {}
    private record Fallback(Stamp stamp, boolean encrypted) {}
    private static final Map<String, Fallback> fallbacks = new LinkedHashMap<>(8, .75f, true);
    private static final Map<String, Fallback> encryptionChecks = new LinkedHashMap<>(8, .75f, true);
    private static final Map<String, Folders> folderCache = new LinkedHashMap<>(8, .75f, true);
    private interface Lister { List<Item> list() throws IOException; }
    private static void rememberFallback(String key, Stamp stamp, boolean encrypted) {
        synchronized (CACHE_LOCK) {
            fallbacks.put(key, new Fallback(stamp, encrypted));
            while (fallbacks.size() > CACHE_LIMIT) fallbacks.remove(fallbacks.keySet().iterator().next());
        }
    }
    private static List<Item> cachedFolder(File file, String prefix, Lister lister) throws IOException {
        String key = file.getCanonicalPath();
        Stamp current = stamp(file);
        synchronized (CACHE_LOCK) {
            Folders cached = folderCache.get(key);
            if (cached != null && cached.stamp.equals(current)) {
                List<Item> known = cached.lists.get(prefix); if (known != null) return new ArrayList<>(known);
            }
        }
        List<Item> known = lister.list();
        synchronized (CACHE_LOCK) {
            Folders folders = folderCache.get(key);
            if (folders == null || !folders.stamp.equals(current)) {
                folders = new Folders(current, new LinkedHashMap<>(8, .75f, true)); folderCache.put(key, folders);
            }
            if (known.size() <= FOLDER_BUDGET) {
                folders.lists.put(prefix, known);
                while (folders.lists.size() > PREFIX_LIMIT) folders.lists.remove(folders.lists.keySet().iterator().next());
            }
            trimFolders();
        }
        return new ArrayList<>(known);
    }
    private static void trimIndexes() {
        int total = 0; for (int count : indexItems.values()) total += count;
        Iterator<String> oldest = indexes.keySet().iterator();
        while ((total > INDEX_BUDGET || indexes.size() > CACHE_LIMIT) && oldest.hasNext()) {
            String key = oldest.next(); Integer removed = indexItems.remove(key);
            total -= removed == null ? 0 : removed; oldest.remove();
        }
    }
    private static void trimFolders() {
        int total = 0;
        for (Folders folders : folderCache.values()) for (List<Item> items : folders.lists.values()) total += items.size();
        Iterator<Folders> oldest = folderCache.values().iterator();
        while ((total > FOLDER_BUDGET || folderCache.size() > CACHE_LIMIT) && oldest.hasNext()) {
            for (List<Item> items : oldest.next().lists.values()) total -= items.size();
            oldest.remove();
        }
    }
    /** Drops cached listings after an archive is rewritten in place. */
    static synchronized void forget(File archive) {
        try {
            String key = archive.getCanonicalPath();
            synchronized (CACHE_LOCK) {
                indexes.remove(key); indexItems.remove(key); folderCache.remove(key); fallbacks.remove(key); encryptionChecks.remove(key);splitChecks.remove(key);
            }
        } catch (IOException ignored) { }
    }
    private static List<Item> listZip4j(File file, String prefix) throws IOException {
        Map<String, Map<String, Item>> folders = new HashMap<>();
        Set<String> names = new HashSet<>();
        try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(file)) {
            for (FileHeader header : zip.getFileHeaders()) {
                String path = zipPath(header.getFileName());
                if (!names.add(path)) throw new IOException("归档存在规范化后重名的条目：" + path);
                boolean directory = header.isDirectory() || path.endsWith("/");
                validateEntry(directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
                String[] pieces = path.split("/");
                String parent = "";
                for (int i = 0; i < pieces.length; i++) {
                    if (pieces[i].isEmpty()) throw new IOException("归档路径包含空目录名");
                    boolean folder = i < pieces.length - 1 || directory;
                    String child = parent + pieces[i] + (folder ? "/" : "");
                    folders.computeIfAbsent(parent, ignored -> new LinkedHashMap<>()).putIfAbsent(child,
                            new Item(child, pieces[i], folder, folder ? 0 : Math.max(0, header.getUncompressedSize()),
                                    Math.max(0, header.getLastModifiedTime())));
                    parent = child;
                }
            }
        } catch (Exception error) {
            throw new IOException("无法读取 ZIP 目录：" + error.getMessage(), error);
        }
        List<Item> result = new ArrayList<>(folders.getOrDefault(prefix, Collections.emptyMap()).values());
        result.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : NaturalOrder.compare(a.name, b.name));
        return result;
    }
    private static List<Item> listPrefix(File file, String prefix) throws IOException {
        Map<String, Item> items = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement(); String path = zipPath(entry.getName());
                if (!path.startsWith(prefix) || path.equals(prefix)) continue;
                String suffix = path.substring(prefix.length()); int slash = suffix.indexOf('/');
                boolean folder = slash >= 0 || path.endsWith("/");
                if (!folder && !names.add(path)) throw new IOException("归档存在规范化后重名的条目：" + path);
                String name = slash >= 0 ? suffix.substring(0, slash) : suffix;
                if (name.isEmpty()) continue;
                String key = prefix + name + (folder ? "/" : "");
                items.putIfAbsent(key, new Item(key, name, folder, folder ? 0 : entry.getSize(), entry.getTime()));
            }
        }
        List<Item> result = new ArrayList<>(items.values());
        result.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : NaturalOrder.compare(a.name, b.name));
        return result;
    }
    static long entrySize(File file, String entry) throws IOException {
        if (ArchiveFormats.supported(file)) {
            for (Item item : list(file, entry.contains("/") ? entry.substring(0, entry.lastIndexOf('/') + 1) : ""))
                if (item.path().equals(entry)) return item.size();
            return -1;
        }
        if (isRar(file)) return RarArchiveOps.entrySize(file, entry);
        try (ReadSession reader = new ReadSession(file)) { return reader.size(entry); }
        catch (MissingEntry missing) { return -1; }
    }
    static boolean containsEntry(File file, String entry) throws IOException {
        if(ArchiveFormats.single(file))return list(file,"").stream().anyMatch(item->item.path().equals(entry));
        return entrySize(file, entry) >= 0;
    }
    static List<String> entryNames(File file) throws IOException {
        if (ArchiveFormats.supported(file)) {
            List<String> names = new ArrayList<>();
            ArrayDeque<String> pending = new ArrayDeque<>(); pending.add("");
            while (!pending.isEmpty()) for (Item item : list(file, pending.remove())) {
                names.add(item.path()); if (item.directory()) pending.add(item.path());
            }
            return names;
        }
        if (isRar(file)) return RarArchiveOps.entryNames(file);
        List<String> names = new ArrayList<>();
        if(isSplitZip(file)||isEncryptedZip(file)){try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(file)){for(FileHeader header:zip.getFileHeaders())names.add(zipPath(header.getFileName()));}return names;}
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) names.add(zipPath(entries.nextElement().getName()));
        }
        return names;
    }
    static byte[] bytes(File file, String entry, int limit) throws IOException {
        try (InputStream in = openEntry(file, entry)) {
            byte[] data = FileStore.readBytes(in, limit + 1);
            if (data.length > limit) throw new IOException("条目超过编辑上限 " + FileStore.size(limit));
            return data;
        }
    }
    static InputStream openEntry(File file, String entry) throws IOException {
        if (isRar(file)) return RarArchiveOps.openEntry(file, entry);
        if (ArchiveFormats.supported(file)) return ArchiveFormats.openEntry(file, entry);
        ReadSession reader = new ReadSession(file);
        try {
            return new FilterInputStream(reader.open(entry)) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { reader.close(); }
                }
            };
        } catch (IOException error) {
            try { reader.close(); } catch (IOException close) { error.addSuppressed(close); }
            throw error;
        }
    }
    private static final class MissingEntry extends IOException {
        MissingEntry() { super("归档条目不存在"); }
    }
    /** One copy operation owns this reader; entry streams close independently of its archive. */
    static final class ReadSession implements AutoCloseable {
        private final File file;
        private ZipFile zip;
        private net.lingala.zip4j.ZipFile encryptedZip;
        private final Map<String, Long> sizes = new HashMap<>();
        private final Set<String> sizeFolders = new HashSet<>();
        private boolean namesScanned;
        private Map<String, String> rawNames;

        ReadSession(File file) throws IOException {
            this.file = file;
            if (isRar(file) || ArchiveFormats.supported(file)) return;
            if(isSplitZip(file)){openEncrypted();return;}
            try { zip = new ZipFile(file); }
            catch (ZipException error) {
                if (!encrypted(error.getMessage())) throw error;
                openEncrypted();
            }
        }
        private void openEncrypted() throws IOException {
            char[] secret = password(file);
            if (secret == null && isEncryptedZip(file)) throw new PasswordRequired(file);
            encryptedZip = new net.lingala.zip4j.ZipFile(file, secret);
        }
        InputStream open(String entry) throws IOException {
            Tasks.Progress.check(); entry = zipPath(entry);
            if (zip != null) {
                String raw = rawName(entry); ZipEntry item = raw == null ? null : zip.getEntry(raw);
                if (item == null || entry.endsWith("/")) throw new MissingEntry();
                return zip.getInputStream(item);
            }
            if (encryptedZip != null) {
                String raw = rawName(entry); FileHeader header = raw == null ? null : encryptedZip.getFileHeader(raw);
                if (header == null || header.isDirectory() || entry.endsWith("/")) throw new MissingEntry();
                return encryptedZip.getInputStream(header);
            }
            return openEntry(file, entry);
        }
        long size(String entry) throws IOException {
            entry = zipPath(entry);
            if (zip != null) {
                String raw = rawName(entry); ZipEntry item = raw == null ? null : zip.getEntry(raw);
                if (item == null || entry.endsWith("/")) throw new MissingEntry();
                return item.getSize();
            }
            if (encryptedZip != null) {
                String raw = rawName(entry); FileHeader item = raw == null ? null : encryptedZip.getFileHeader(raw);
                if (item == null || item.isDirectory() || entry.endsWith("/")) throw new MissingEntry();
                return item.getUncompressedSize();
            }
            String prefix = entry.substring(0, entry.lastIndexOf('/') + 1);
            if (sizeFolders.add(prefix))
                for (Item item : list(file, prefix)) sizes.put(item.path(), item.size());
            Long size = sizes.get(entry);
            if (size == null) throw new MissingEntry();
            return size;
        }
        private java.util.stream.Stream<String> names() throws IOException {
            return zip != null ? zip.stream().map(ZipEntry::getName)
                    : encryptedZip.getFileHeaders().stream().map(FileHeader::getFileName);
        }
        private String rawName(String entry) throws IOException {
            if (!entry.contains("/")) return entry;
            if (!namesScanned) {
                rawNames = new HashMap<>();
                try (java.util.stream.Stream<String> names = names()) {
                    Iterator<String> iterator = names.iterator();
                    while (iterator.hasNext()) {
                        Tasks.Progress.check(); String raw = iterator.next(), path = zipPath(raw);
                        if (rawNames.putIfAbsent(path, raw) != null) throw new IOException("归档存在规范化后重名的条目：" + path);
                        if (rawNames.size() > INDEX_LIMIT) { rawNames = null; break; }
                    }
                }
                namesScanned = true;
            }
            if (rawNames != null) return rawNames.get(entry);
            String result = null;
            try (java.util.stream.Stream<String> names = names()) {
                Iterator<String> iterator = names.iterator();
                while (iterator.hasNext()) {
                    Tasks.Progress.check(); String raw = iterator.next();
                    if (!zipPath(raw).equals(entry)) continue;
                    if (result != null) throw new IOException("归档存在规范化后重名的条目：" + entry);
                    result = raw;
                }
            }
            return result;
        }
        @Override public void close() throws IOException {
            if (zip != null) zip.close();
            if (encryptedZip != null) encryptedZip.close();
        }
    }
    static boolean isRar(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".rar")) return true;
        if (file.length() < 7) return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] signature = new byte[8]; int count = in.read(signature);
            return count >= 7 && signature[0] == 'R' && signature[1] == 'a' && signature[2] == 'r'
                    && signature[3] == '!' && signature[4] == 0x1a && signature[5] == 0x07
                    && (signature[6] == 0x00 || (count >= 8 && signature[6] == 0x01 && signature[7] == 0x00));
        } catch (IOException ignored) { return false; }
    }
    static File extractOne(File archive, String entry, File target) throws IOException {
        if (isRar(archive) || ArchiveFormats.supported(archive) || isEncryptedZip(archive) || isSplitZip(archive)) {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建输出目录");
            if (target.exists()) throw new IOException("解压目标已存在：" + target.getName());
            File tmp = File.createTempFile(".rar-entry-", ".tmp", parent);
            try {
                try (InputStream in = openEntry(archive, entry); OutputStream out = new FileOutputStream(tmp)) {
                    copyBounded(in, out, 4L * 1024 * 1024 * 1024);
                }
                Files.move(tmp.toPath(), target.toPath());
            } finally { Files.deleteIfExists(tmp.toPath()); }
            return target;
        }
        try (InputStream in = openEntry(archive, entry); OutputStream out = new FileOutputStream(target)) {
            FileStore.pump(in, out);
            return target;
        }
    }
    static File safeTarget(File root, String path) throws IOException {
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("\\") || path.indexOf('\0') >= 0)
            throw new IOException("归档路径无效：" + path);
        File target = new File(root, path);
        if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("归档条目越过输出目录：" + path);
        return target;
    }
    static void extract(File archive, String prefix, File output) throws IOException {
        extract(archive, prefix, output, false);
    }
    static void extract(File archive, String prefix, File output, boolean selected) throws IOException {
        if (ArchiveFormats.supported(archive)) { ArchiveFormats.extract(archive, prefix, output, selected); return; }
        if (isRar(archive)) { RarArchiveOps.extract(archive, prefix, output, selected); return; }
        if(isSplitZip(archive)){char[] secret=password(archive);if(secret==null&&isEncryptedZip(archive))throw new PasswordRequired(archive);extractZip4j(archive,prefix,output,selected,secret);return;}
        if (!output.isDirectory() && !output.mkdirs()) throw new IOException("无法创建输出目录");
        String selectedName = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        selectedName = selectedName.substring(selectedName.lastIndexOf('/') + 1);
        long total = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement(); String path = zipPath(e.getName());
                if (!prefix.isEmpty() && !path.equals(prefix) && !(prefix.endsWith("/") && path.startsWith(prefix))) continue;
                Tasks.Progress.step(path);
                String relative = prefix.endsWith("/") ? path.substring(prefix.length()) : path;
                if (selected && !prefix.isEmpty()) relative = prefix.endsWith("/") ? selectedName + "/" + relative : selectedName;
                if (relative.isEmpty()) continue;
                validateEntry(path);
                File target = safeTarget(output, relative);
                if (path.endsWith("/")) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("创建目录失败");
                    continue;
                }
                if (target.exists()) throw new IOException("解压目标已存在：" + relative);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("创建目录失败");
                File tmp = File.createTempFile(".extract-", ".tmp", parent);
                try {
                    try (InputStream in = zip.getInputStream(e); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buffer = new byte[131072]; int n;
                        while (true) {
                            Tasks.Progress.check();n=in.read(buffer);if(n<0)break;if(n==0)continue;
                            total += n;
                            if (total > 4L * 1024 * 1024 * 1024) throw new IOException("本次解压超过 4 GiB 上限");
                            out.write(buffer, 0, n);
                            Tasks.Progress.bytes(n);
                        }
                    }
                    FileTimes.extracted(tmp,e.getTime());
                    Files.move(tmp.toPath(), target.toPath());
                } finally { Files.deleteIfExists(tmp.toPath()); }
            }
        } catch (ZipException error) {
            if (!encrypted(error.getMessage())) throw error;
            char[] password = password(archive);
            if (password == null) throw new PasswordRequired(archive);
            extractZip4j(archive, prefix, output, selected, password);
        }
    }
    private static void extractZip4j(File archive, String prefix, File output, boolean selected, char[] password) throws IOException {
        if(!output.isDirectory()&&!output.mkdirs())throw new IOException("无法创建输出目录");
        String selectedName = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        selectedName = selectedName.substring(selectedName.lastIndexOf('/') + 1);
        long total = 0;
        net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(archive, password);
        try {
            for (FileHeader header : zip.getFileHeaders()) {
                String path = zipPath(header.getFileName());
                boolean directory = header.isDirectory() || path.endsWith("/");
                String normalized = directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                validateEntry(normalized);
                if (!prefix.isEmpty() && !normalized.equals(prefix) && !(prefix.endsWith("/") && path.startsWith(prefix))) continue;
                String relative = prefix.endsWith("/") ? path.substring(prefix.length()) : path;
                if (selected && !prefix.isEmpty()) relative = prefix.endsWith("/") ? selectedName + "/" + relative : selectedName;
                if (relative.isEmpty()) continue;
                File target = safeTarget(output, relative);
                if (directory) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("创建目录失败");
                    continue;
                }
                if (target.exists()) throw new IOException("解压目标已存在：" + relative);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("创建目录失败");
                File tmp = File.createTempFile(".extract-", ".tmp", parent);
                try {
                    try (InputStream in = zip.getInputStream(header); OutputStream out = new FileOutputStream(tmp)) {
                        total += copyBounded(in, out, 4L * 1024 * 1024 * 1024 - total);
                    }
                    FileTimes.extracted(tmp,header.getLastModifiedTime());
                    Files.move(tmp.toPath(), target.toPath());
                } finally { Files.deleteIfExists(tmp.toPath()); }
            }
        } catch (IOException | RuntimeException error) {
            throw error instanceof IOException value ? value : new IOException(error.getMessage(), error);
        } finally {
            try { zip.close(); } catch (IOException ignored) { }
        }
    }
    // Rebuild beside the archive and commit only after every entry has been written.
    static void rewrite(File archive, String remove, String rename, Map<String, File> additions) throws IOException {
        rewrite(archive, remove, rename, additions, Collections.emptySet());
    }
    static void removeAll(File archive, Set<String> removals) throws IOException {
        rewriteBatch(archive, Collections.emptyMap(), removals);
    }
    static void rewriteBatch(File archive, Map<String, File> additions, Set<String> removals) throws IOException {
        for (String name : removals) { validateEntry(name); if (name.isEmpty()) throw new IOException("不能删除归档根目录"); }
        rewrite(archive, null, null, additions, removals);
    }
    static void renameAll(File archive, Map<String, String> renames) throws IOException {
        synchronized (lock(archive)) { renameAllLocked(archive, renames); }
    }
    private static void renameAllLocked(File archive, Map<String, String> renames) throws IOException {
        if (isRar(archive) || ArchiveFormats.supported(archive) || isEncryptedZip(archive) || isSplitZip(archive))
            throw new IOException("此格式暂不支持直接重命名，请先解压");
        for (Map.Entry<String, String> item : renames.entrySet()) { validateEntry(item.getKey()); validateEntry(item.getValue()); }
        FileStore.FileState before = FileStore.FileState.read(archive);
        File temp = File.createTempFile(".archive-renames-", ".zip", archive.getParentFile());
        try {
            Set<String> written = new HashSet<>();
            try (org.apache.commons.compress.archivers.zip.ZipFile zip = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get();
                 org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream out = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(temp)) {
                Enumeration<org.apache.commons.compress.archivers.zip.ZipArchiveEntry> entries = zip.getEntriesInPhysicalOrder();
                while (entries.hasMoreElements()) {
                    Tasks.Progress.check();
                    var entry = entries.nextElement(); String name = zipPath(entry.getName()), target = name;
                    if (ApkInstall.isApkName(archive.getName()) && isSignature(name)) continue;
                    for (var rename : renames.entrySet()) if (name.equals(rename.getKey()) || rename.getKey().endsWith("/") && name.startsWith(rename.getKey())) {
                        target = rename.getValue() + name.substring(rename.getKey().length()); break;
                    }
                    if (!written.add(target)) throw new IOException("重命名后归档条目冲突：" + target);
                    var next = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(target);
                    next.setMethod(entry.getMethod()); next.setSize(entry.getSize()); next.setCompressedSize(entry.getCompressedSize()); next.setCrc(entry.getCrc());
                    if (entry.getTime() >= 0) next.setTime(entry.getTime());
                    if (entry.getComment() != null) next.setComment(entry.getComment());
                    next.setUnixMode(entry.getUnixMode()); next.setInternalAttributes(entry.getInternalAttributes()); next.setExternalAttributes(entry.getExternalAttributes());
                    InputStream raw = zip.getRawInputStream(entry);
                    if (raw == null) throw new IOException("无法读取压缩数据：" + name);
                    try (InputStream in = new FilterInputStream(raw) {
                        @Override public int read(byte[] b, int off, int len) throws IOException { Tasks.Progress.check(); return super.read(b, off, len); }
                        @Override public int read() throws IOException { Tasks.Progress.check(); return super.read(); }
                    }) { out.addRawArchiveEntry(next, in); }
                }
            }
            Tasks.Progress.check(); FileStore.publish(temp, archive, before); forget(archive);
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    private static void rewrite(File archive, String remove, String rename, Map<String, File> additions,
                                Set<String> removals) throws IOException {
        synchronized (lock(archive)) { rewriteLocked(archive, remove, rename, additions, removals); }
    }
    private static void rewriteLocked(File archive, String remove, String rename, Map<String, File> additions,
                                      Set<String> removals) throws IOException {
        rewriteLocked(archive, remove, rename, additions, removals, null, null);
    }
    interface ArchiveGuard { void verify() throws IOException; }
    static void removeVerified(File archive, Set<String> removals, FileStore.FileState state, ArchiveGuard guard) throws IOException {
        synchronized (lock(archive)) {
            rewriteLocked(archive, null, null, Collections.emptyMap(), removals, state, guard);
        }
    }
    private static void rewriteLocked(File archive, String remove, String rename, Map<String, File> additions,
                                      Set<String> removals, FileStore.FileState known, ArchiveGuard guard) throws IOException {
        try (Tasks.Progress.Phase stage = Tasks.Progress.stage("重建归档")) {
            RewriteBoundary boundary = testRewriteBoundary; if (boundary != null) boundary.beforeRewrite(archive);
            Tasks.Progress.check();
            if (guard != null) guard.verify();
            FileStore.FileState before = known == null ? FileStore.FileState.read(archive) : known;
            if (isRar(archive) || ArchiveFormats.supported(archive) || isEncryptedZip(archive) || isSplitZip(archive))
                throw new IOException("此归档格式暂不支持直接修改，请先解压后编辑");
            for (String name : additions.keySet()) validateEntry(name);
            if (rename != null) validateEntry(rename);
            File tmp = File.createTempFile(".archive-", ".zip", archive.getParentFile());
            boolean apk = ApkInstall.isApkName(archive.getName());
            try {
                rewriteCompressed(archive, tmp, remove, rename, additions, removals, apk);
                Tasks.Progress.check();
                if (guard != null) guard.verify();
                FileStore.publish(tmp, archive, before);
                forget(archive);
            } finally { Files.deleteIfExists(tmp.toPath()); }
        }
    }
    private static void rewriteCompressed(File archive, File output, String remove, String rename, Map<String, File> additions, Set<String> removals, boolean apk) throws IOException {
        try (ZipFile metadata = new ZipFile(archive);
                org.apache.commons.compress.archivers.zip.ZipFile zip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                        .setUseUnicodeExtraFields(false).setFile(archive).get();
                org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream out = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(output)) {
            if (metadata.getComment() != null) out.setComment(metadata.getComment());
            Set<String> deleted = new HashSet<>(removals);
            if (remove != null && rename == null) deleted.add(remove);
            rewriteCompressedEntries(zip, out, rename == null ? Map.of() : Map.of(remove, rename), additions, deleted, apk);
        }
    }

    static void rewriteSplitCompressed(File archive, java.nio.file.Path[] volumes, File output, long volumeSize, Map<String, String> renames,
                                       Map<String, File> additions, Set<String> removals) throws IOException {
        for (var item : renames.entrySet()) { validateEntry(item.getKey()); validateEntry(item.getValue()); }
        writeSplitZipResult(archive, volumes, output, volumeSize, renames, additions, removals);
        if (!isSplitZip(output)) {
            // Commons Compress leaves a split signature even when the result fits into one disk.
            // Re-emit the small result as a standard ZIP so Android and other ZIP readers accept it.
            Files.delete(output.toPath());
            forget(output);
            writeSplitZipResult(archive, volumes, output, 0, renames, additions, removals);
        }
    }

    private static void writeSplitZipResult(File archive, java.nio.file.Path[] volumes, File output, long volumeSize,
                                            Map<String, String> renames, Map<String, File> additions, Set<String> removals) throws IOException {
        try (var metadata = new net.lingala.zip4j.ZipFile(archive);
             var channel = org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel.forPaths(volumes);
             var zip = org.apache.commons.compress.archivers.zip.ZipFile.builder().setUseUnicodeExtraFields(false)
                     .setSeekableByteChannel(channel).get();
             var out = volumeSize == 0 ? new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(output)
                     : new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(output, volumeSize)) {
            if (metadata.getComment() != null) out.setComment(metadata.getComment());
            rewriteCompressedEntries(zip, out, renames, additions, removals, false);
        }
    }

    private static void rewriteCompressedEntries(org.apache.commons.compress.archivers.zip.ZipFile zip,
             org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream out, Map<String, String> renames,
             Map<String, File> additions, Set<String> removals, boolean apk) throws IOException {
            Set<String> names = new HashSet<>(), written = new HashSet<>();
            Enumeration<org.apache.commons.compress.archivers.zip.ZipArchiveEntry> entries = zip.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                Tasks.Progress.check();
                org.apache.commons.compress.archivers.zip.ZipArchiveEntry entry = entries.nextElement();
                String name = zipPath(entry.getName());
                if (!names.add(name)) throw new IOException("归档存在规范化后重名的条目：" + name);
                if (removals.contains(name) || removedParent(name, removals) || apk && isSignature(name)
                        || additions.containsKey(name)) continue;
                String target = name;
                for (var rename : renames.entrySet()) {
                    String old = rename.getKey();
                    if (name.equals(old) || old.endsWith("/") && name.startsWith(old)) {
                        target = rename.getValue() + name.substring(old.length()); break;
                    }
                }
                if (!written.add(target)) throw new IOException("归档存在重复条目：" + target);
                var next = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(entry);
                if (!target.equals(entry.getName())) {
                    next = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(target);
                    next.setMethod(entry.getMethod()); next.setSize(entry.getSize()); next.setCompressedSize(entry.getCompressedSize()); next.setCrc(entry.getCrc());
                    if (entry.getTime() >= 0) next.setTime(entry.getTime());
                    if (entry.getComment() != null) next.setComment(entry.getComment());
                    next.setUnixMode(entry.getUnixMode()); next.setInternalAttributes(entry.getInternalAttributes()); next.setExternalAttributes(entry.getExternalAttributes());
                }
                InputStream compressed = zip.getRawInputStream(entry);
                if (compressed == null) throw new IOException("无法读取归档成员的压缩数据：" + name);
                try (InputStream input = new FilterInputStream(compressed) {
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        Tasks.Progress.check(); int count = super.read(buffer, offset, length);
                        if (count > 0) Tasks.Progress.bytes(count); return count;
                    }
                    @Override public int read() throws IOException {
                        Tasks.Progress.check(); int value = super.read();
                        if (value >= 0) Tasks.Progress.bytes(1); return value;
                    }
                }) {
                    out.addRawArchiveEntry(next, input);
                }
            }
            for (Map.Entry<String, File> addition : additions.entrySet()) {
                Tasks.Progress.check();
                if (!written.add(addition.getKey())) throw new IOException("归档条目冲突：" + addition.getKey());
                var entry = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(addition.getKey());
                if (addition.getValue() != null) entry.setTime(addition.getValue().lastModified());
                out.putArchiveEntry(entry);
                if (addition.getValue() != null) try (InputStream input = new FileInputStream(addition.getValue())) { FileStore.pump(input, out); }
                out.closeArchiveEntry();
            }
    }

    private static boolean removedParent(String name, Set<String> removals) {
        for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1))
            if (removals.contains(name.substring(0, slash + 1))) return true;
        return false;
    }
    static boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF") || (upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-")));
    }
    static void validateEntry(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0
                || name.matches("(?s)^[A-Za-z]:.*"))
            throw new IOException("归档路径无效");
        String[] pieces = name.split("/", -1);
        for (int i = 0; i < pieces.length; i++)
            if (pieces[i].equals(".") || pieces[i].equals("..") || pieces[i].isEmpty() && i != pieces.length - 1)
                throw new IOException("归档路径无效");
    }
    private static String zipPath(String raw) throws IOException {
        String path = raw.replace('\\', '/'); validateEntry(path); return path;
    }
    private static long copyBounded(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[131072]; long total = 0; int n;
        while (true) {
            Tasks.Progress.check();n=in.read(buffer);if(n<0)break;if(n==0)continue;
            total += n;
            if (total > limit) throw new IOException("解压条目超过 4 GiB 上限");
            out.write(buffer, 0, n);
            Tasks.Progress.bytes(n);
        }
        return total;
    }
    static void pack(FileStore store, List<FileStore.Node> nodes, File output) throws IOException {
        pack(store, nodes, output, "zip", new char[0]);
    }
    static void pack(FileStore store, List<FileStore.Node> nodes, File output, String format, char[] password) throws IOException {
        pack(store, nodes, output, format, password, 6);
    }
    static void pack(FileStore store, List<FileStore.Node> nodes, File output, String format, char[] password, int level) throws IOException {
        if (level != 0 && level != 1 && level != 6 && level != 9) throw new IOException("压缩级别无效");
        try (Tasks.Progress.Phase stage = Tasks.Progress.stage("压缩文件")) {
            if (!format.equals("zip")) {
                if (password.length != 0 && !format.equals("7z")) throw new IOException("此格式不支持压缩密码，请使用 ZIP 或 7Z");
                ArchiveFormats.pack(store, nodes, output, format, level, password);
                if (password.length != 0) cachePassword(output, password);
                return;
            }
            if (password.length == 0 && level != 0) {
                try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
                    out.setLevel(level);
                    Set<String> seen = new HashSet<>();
                    for (FileStore.Node n : nodes) packNode(store, n, n.name, out, seen);
                }
            } else try (net.lingala.zip4j.io.outputstream.ZipOutputStream out = new net.lingala.zip4j.io.outputstream.ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(output)), password)) {
                Set<String> seen = new HashSet<>();
                for (FileStore.Node node : nodes) packEncrypted(store, node, node.name, out, seen, level, password.length > 0);
                if (password.length > 0) cachePassword(output, password);
            }
        }
    }
    /** Caller owns an empty staging directory; the .zip part is published last. */
    static List<File> packSplit(FileStore store,List<FileStore.Node> nodes,File output,char[] password,int level,long splitSize)throws IOException {
        if(splitSize<65536)throw new IOException("ZIP 分卷至少 64 KiB");
        if(level!=0&&level!=1&&level!=6&&level!=9)throw new IOException("压缩级别无效");
        try(net.lingala.zip4j.io.outputstream.SplitOutputStream sink=new net.lingala.zip4j.io.outputstream.SplitOutputStream(output,splitSize);
            net.lingala.zip4j.io.outputstream.ZipOutputStream out=new net.lingala.zip4j.io.outputstream.ZipOutputStream(sink,password)){
            Set<String> seen=new HashSet<>();for(FileStore.Node node:nodes)packEncrypted(store,node,node.name,out,seen,level,password.length>0);
        }
        Tasks.Progress.check();cachePassword(output,password);
        try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(output,password)){return new ArrayList<>(zip.getSplitZipFiles());}
    }
    private static void packEncrypted(FileStore store, FileStore.Node node, String path,
            net.lingala.zip4j.io.outputstream.ZipOutputStream out, Set<String> seen, int level, boolean encrypted) throws IOException {
        String key = node.file != null ? node.file.getCanonicalPath() : node.key();
        if (!seen.add(key)) throw new IOException("目录包含循环引用");
        Tasks.Progress.step(path);
        validateEntry(path);
        net.lingala.zip4j.model.ZipParameters parameters = new net.lingala.zip4j.model.ZipParameters();
        parameters.setFileNameInZip(path + (node.directory ? "/" : ""));
        parameters.setEncryptFiles(!node.directory && encrypted);
        parameters.setCompressionMethod(level == 0 ? net.lingala.zip4j.model.enums.CompressionMethod.STORE : net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE);
        parameters.setCompressionLevel(switch (level) {
            case 0 -> net.lingala.zip4j.model.enums.CompressionLevel.NO_COMPRESSION;
            case 1 -> net.lingala.zip4j.model.enums.CompressionLevel.FASTEST;
            case 9 -> net.lingala.zip4j.model.enums.CompressionLevel.ULTRA;
            default -> net.lingala.zip4j.model.enums.CompressionLevel.NORMAL;
        });
        parameters.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
        parameters.setAesKeyStrength(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256);
        if (node.modified > 0) parameters.setLastModifiedFileTime(node.modified);
        File payload = null;
        try {
            if (!node.directory && level == 0) { payload = store.materialize(node); parameters.setEntrySize(payload.length()); }
            else if (node.directory) parameters.setEntrySize(0);
            out.putNextEntry(parameters);
            if (!node.directory) try (InputStream input = payload == null ? store.read(node) : new BufferedInputStream(new FileInputStream(payload))) { FileStore.pump(input, out); }
            out.closeEntry();
        } finally { if (payload != null) store.release(node, payload); }
        if (node.directory) for (FileStore.Node child : store.list(node))
            packEncrypted(store, child, path + "/" + child.name, out, seen, level, encrypted);
        seen.remove(key);
    }
    private static void packNode(FileStore store, FileStore.Node node, String path, ZipOutputStream out,
                                 Set<String> seen) throws IOException {
        String canonical = node.file != null ? node.file.getCanonicalPath() : node.key();
        if (!seen.add(canonical)) throw new IOException("目录包含循环引用");
        Tasks.Progress.step(path);
        validateEntry(path);
        ZipEntry entry = new ZipEntry(path + (node.directory ? "/" : ""));
        if (node.modified > 0) entry.setTime(node.modified);
        out.putNextEntry(entry);
        if (!node.directory) try (InputStream in = store.read(node)) { FileStore.pump(in, out); }
        out.closeEntry();
        if (node.directory) for (FileStore.Node n : store.list(node)) packNode(store, n, path + "/" + n.name, out, seen);
        seen.remove(canonical);
    }
    private ArchiveOps() {}
}
