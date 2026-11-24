package org.example.filemanager;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

final class ArchiveFormats {
    static final String[] PACK_FORMATS={"zip","tar","tar.gz","7z","tar.bz2","tar.xz","tar.lz4","tar.zst","gz","bz2","xz","lz4","zst"};
    private static final long MAX_EXTRACTED = 4L * 1024 * 1024 * 1024;

    static boolean supported(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz")
                || name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") || sevenZ(file)
                || name.endsWith(".tar.xz") || name.endsWith(".txz") || name.endsWith(".tar.lz4") || name.endsWith(".tar.zst")
                || single(file);
    }
    static boolean single(File file) {
        String name=file.getName().toLowerCase(Locale.ROOT);
        int extension = name.lastIndexOf('.');
        if (extension < 0 || (extension >= 4 && name.regionMatches(extension - 4, ".tar", 0, 4))) return false;
        return switch (name.substring(extension + 1)) {
            case "gz", "bz2", "xz", "lz4", "zst" -> true;
            default -> false;
        };
    }
    private static String streamName(File file)throws IOException {
        String name=file.getName().replaceFirst("(?i)\\.(gz|bz2|xz|lz4|zst)$","");
        if(name.isEmpty())name="content";ArchiveOps.validateEntry(name);return name;
    }
    static boolean sevenZ(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".7z") || splitSevenZ(file);
    }
    static boolean splitSevenZ(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        int digits = name.length() - dot - 1;
        if (dot < 3 || digits < 3 || digits > 5 || !name.regionMatches(true, dot - 3, ".7z", 0, 3)) return false;
        for (int i = dot + 1; i < name.length(); i++)
            if (name.charAt(i) < '0' || name.charAt(i) > '9') return false;
        return true;
    }
    static boolean encryptedSevenZ(File file) {
        try (SevenZFile archive = openSevenZ(file)) {
            // Plain headers can describe encrypted payloads. Probe the first stream without reading its contents.
            SevenZArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (entry.hasStream()) return encryptedSevenZEntry(entry);
            }
            return false;
        } catch (ArchiveOps.PasswordRequired required) { return true; }
        catch (IOException | RuntimeException error) { return false; }
    }
    private static boolean encryptedSevenZEntry(SevenZArchiveEntry entry) {
        if (entry.getContentMethods() == null) return false;
        for (var method : entry.getContentMethods()) {
            if (method.getMethod() == org.apache.commons.compress.archivers.sevenz.SevenZMethod.AES256SHA256) return true;
        }
        return false;
    }
    private static SevenZFile openSevenZ(File file) throws IOException {
        var builder = SevenZFile.builder().setMaxMemoryLimitKiB(256 * 1024);
        java.nio.channels.SeekableByteChannel channel = null;
        char[] value = ArchiveOps.password(file);
        char[] password = value == null ? null : value.clone();
        try {
            if (splitSevenZ(file)) {
                String name = file.getName(), base = name.substring(0, name.lastIndexOf('.') + 1);
                File parent = file.getAbsoluteFile().getParentFile();
                File[] siblings = parent.listFiles();
                if (siblings == null) throw new IOException("无法读取 7Z 分卷所在目录");
                Map<Integer, File> parts = new TreeMap<>();
                for (File sibling : siblings) {
                    String candidate = sibling.getName();
                    if (!candidate.startsWith(base)) continue;
                    String sequence = candidate.substring(base.length());
                    if (!sequence.matches("\\d{3,5}")) continue;
                    if (!Files.isRegularFile(sibling.toPath(), LinkOption.NOFOLLOW_LINKS)
                            || parts.putIfAbsent(Integer.parseInt(sequence), sibling) != null)
                        throw new IOException("7Z 分卷名称重复或无效");
                }
                if (parts.isEmpty() || parts.size() > 1024) throw new IOException("7Z 分卷缺失或超过 1024 卷");
                int expected = 1;
                for (int index : parts.keySet()) if (index != expected++) throw new IOException("缺少 7Z 分卷");
                channel = org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel.forFiles(parts.values().toArray(new File[0]));
                builder.setSeekableByteChannel(channel).setDefaultName(base);
            } else builder.setFile(file);
            if (password != null) builder.setPassword(password);
            return builder.get();
        } catch (IOException | RuntimeException error) {
            if (channel != null) try { channel.close(); } catch (IOException close) { error.addSuppressed(close); }
            if (error instanceof org.apache.commons.compress.PasswordRequiredException) throw new ArchiveOps.PasswordRequired(file);
            throw error;
        } finally { if (password != null) Arrays.fill(password, '\0'); }
    }

    static List<ArchiveOps.Item> list(File file, String prefix) throws IOException {
        if(single(file))return prefix.isEmpty()?List.of(new ArchiveOps.Item(streamName(file),streamName(file),false,-1,file.lastModified())):List.of();
        if (sevenZ(file)) return listSevenZ(file, prefix);
        String parent = selection(prefix);
        Map<String, ArchiveOps.Item> items = new LinkedHashMap<>();
        try (TarArchiveInputStream input = open(file)) {
            TarArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String path = path(entry, input);
                if (path.isEmpty() || !path.startsWith(parent) || path.equals(parent)) continue;
                String suffix = path.substring(parent.length());
                int slash = suffix.indexOf('/');
                boolean directory = slash >= 0 || entry.isDirectory();
                String name = slash >= 0 ? suffix.substring(0, slash) : suffix;
                String child = parent + name + (directory ? "/" : "");
                ArchiveOps.Item item = new ArchiveOps.Item(child, name, directory,
                        directory ? 0 : entry.getSize(), entry.getLastModifiedDate().getTime());
                items.putIfAbsent(child, item);
            }
        }
        List<ArchiveOps.Item> result = new ArrayList<>(items.values());
        result.sort((a, b) -> a.directory() != b.directory() ? (a.directory() ? -1 : 1)
                : a.name().compareToIgnoreCase(b.name()));
        return result;
    }

    static InputStream openEntry(File file, String entry) throws IOException {
        if(single(file)){if(!streamName(file).equals(entry))throw new IOException("压缩流条目不存在");return compressedInput(file);}
        if (sevenZ(file)) return openSevenZEntry(file, entry);
        String requested = selection(entry);
        TarArchiveInputStream input = open(file);
        try {
            TarArchiveEntry item;
            while ((item = input.getNextEntry()) != null) {
                String path = path(item, input);
                if (!path.equals(requested)) continue;
                if (item.isDirectory()) throw new IOException("请选择 TAR 内的文件");
                if (item.getSize() > MAX_EXTRACTED) throw new IOException("解压条目超过 4 GiB 上限");
                return input;
            }
            throw new IOException("TAR 条目不存在：" + entry);
        } catch (IOException | RuntimeException error) {
            try { input.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    static void extract(File file, String prefix, File output, boolean selected) throws IOException {
        if(single(file)){extractStream(file,prefix,output);return;}
        if (sevenZ(file)) { extractSevenZ(file, prefix, output, selected); return; }
        String requested = selection(prefix);
        String selectedName = requested.endsWith("/") ? requested.substring(0, requested.length() - 1) : requested;
        selectedName = selectedName.substring(selectedName.lastIndexOf('/') + 1);
        List<Path> created = new ArrayList<>();
        long total = 0;
        try {
            directories(output.toPath(), created);
            try (TarArchiveInputStream input = open(file)) {
                TarArchiveEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    String path = path(entry, input);
                    if (path.isEmpty()) continue;
                    Tasks.Progress.step(path);
                    String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                    if (!requested.isEmpty() && !normalized.equals(requested)
                            && !(requested.endsWith("/") && path.startsWith(requested))) continue;
                    String relative = requested.endsWith("/") ? path.substring(requested.length()) : path;
                    if (selected && !requested.isEmpty()) relative = requested.endsWith("/")
                            ? selectedName + "/" + relative : selectedName;
                    if (relative.isEmpty()) continue;
                    Path target = ArchiveOps.safeTarget(output, relative).toPath();
                    if (entry.isDirectory()) {
                        directories(target, created);
                        continue;
                    }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("解压目标已存在：" + relative);
                    if (entry.getSize() > MAX_EXTRACTED - total) throw new IOException("本次解压超过 4 GiB 上限");
                    directories(target.getParent(), created);
                    Path temporary = Files.createTempFile(target.getParent(), ".tar-entry-", ".tmp");
                    try {
                        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                            total += copy(input, out, MAX_EXTRACTED - total);
                        }
                        FileTimes.extracted(temporary.toFile(),entry.getLastModifiedDate().getTime());
                        Files.move(temporary, target);
                        created.add(target);
                    } finally { Files.deleteIfExists(temporary); }
                }
            }
        } catch (IOException | RuntimeException error) {
            for (int i = created.size() - 1; i >= 0; i--) {
                try { Files.deleteIfExists(created.get(i)); }
                catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }

    static void pack(FileStore store, List<FileStore.Node> nodes, File output, String format) throws IOException {
        pack(store,nodes,output,format,6);
    }
    static void pack(FileStore store, List<FileStore.Node> nodes, File output, String format,int level) throws IOException {
        pack(store, nodes, output, format, level, new char[0]);
    }
    static void pack(FileStore store, List<FileStore.Node> nodes, File output, String format, int level, char[] password) throws IOException {
        if (!Set.of("tar", "tar.gz", "tar.bz2", "tar.xz", "tar.lz4", "tar.zst", "7z", "gz", "bz2", "xz", "lz4", "zst").contains(format)) throw new IOException("不支持的压缩格式：" + format);
        String destination = output.getCanonicalPath();
        for (FileStore.Node node : nodes) {
            if (node.file == null) continue;
            String source = node.file.getCanonicalPath();
            if (destination.equals(source) || node.directory && destination.startsWith(source + File.separator))
                throw new IOException("归档输出不能位于所选文件夹内部");
        }
        if (format.equals("7z")) { packSevenZ(store, nodes, output, level, password); return; }
        if(!format.startsWith("tar")){packStream(store,nodes,output,format,level);return;}
        Path temporary = Files.createTempFile(output.getAbsoluteFile().getParentFile().toPath(), ".tar-pack-", ".tmp");
        try {
            try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(temporary));
                 OutputStream compressed = compressedOutput(file,format.equals("tar")?"tar":format.substring(4),level);
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed, "UTF-8")) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                tar.setAddPaxHeadersForNonAsciiNames(true);
                Set<String> seen = new HashSet<>(), written = new HashSet<>();
                for (FileStore.Node node : nodes) packNode(store, node, node.name, tar, seen, written, 0);
            }
            Tasks.Progress.check();
            FileStore.replace(temporary.toFile(), output);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void packNode(FileStore store, FileStore.Node node, String path, TarArchiveOutputStream output,
                                 Set<String> seen, Set<String> written, int depth) throws IOException {
        Tasks.Progress.step(path);
        ArchiveOps.validateEntry(path);
        if (node.file != null && Files.isSymbolicLink(node.file.toPath()))
            throw new IOException("TAR 压缩不跟随符号链接：" + path);
        String key = node.file != null ? node.file.getCanonicalPath() : node.key();
        if (depth > 256 || !seen.add(key)) throw new IOException("目录包含循环引用或嵌套过深");
        if (!written.add(path)) throw new IOException("归档存在同名条目：" + path);
        File payload = null;
        try {
            TarArchiveEntry entry = new TarArchiveEntry(path + (node.directory ? "/" : ""));
            if (node.modified > 0) entry.setModTime(node.modified);
            if (!node.directory) {
                if (node.file == null || node.root || node.size <= 0) payload = store.materialize(node);
                entry.setSize(payload != null ? payload.length()
                        : node.file != null ? node.file.length() : node.size);
            }
            output.putArchiveEntry(entry);
            if (!node.directory) try (InputStream input = payload == null ? store.read(node)
                    : new BufferedInputStream(new FileInputStream(payload))) {
                FileStore.pump(input, output);
            }
            output.closeArchiveEntry();
            if (node.directory) for (FileStore.Node child : store.list(node))
                packNode(store, child, path + "/" + child.name, output, seen, written, depth + 1);
        } finally {
            seen.remove(key);
            if (payload != null) store.release(node, payload);
        }
    }

    private static List<ArchiveOps.Item> listSevenZ(File file, String prefix) throws IOException {
        String parent = selection(prefix);
        Map<String, ArchiveOps.Item> items = new LinkedHashMap<>();
        try (SevenZFile archive = openSevenZ(file)) {
            for (SevenZArchiveEntry entry : archive.getEntries()) {
                String path = sevenPath(entry);
                if (path.isEmpty() || !path.startsWith(parent) || path.equals(parent)) continue;
                String suffix = path.substring(parent.length());
                int slash = suffix.indexOf('/');
                boolean directory = slash >= 0 || entry.isDirectory();
                String name = slash >= 0 ? suffix.substring(0, slash) : suffix;
                String child = parent + name + (directory ? "/" : "");
                items.putIfAbsent(child, new ArchiveOps.Item(child, name, directory,
                        directory ? 0 : entry.getSize(), entry.getLastModifiedDate() == null ? 0 : entry.getLastModifiedDate().getTime()));
            }
        }
        List<ArchiveOps.Item> result = new ArrayList<>(items.values());
        result.sort((a, b) -> a.directory() != b.directory() ? (a.directory() ? -1 : 1)
                : a.name().compareToIgnoreCase(b.name()));
        return result;
    }

    private static InputStream openSevenZEntry(File file, String entry) throws IOException {
        String requested = selection(entry);
        SevenZFile archive = openSevenZ(file);
        try {
            for (SevenZArchiveEntry item : archive.getEntries()) {
                if (!sevenPath(item).equals(requested)) continue;
                if (item.isDirectory()) throw new IOException("请选择 7Z 内的文件");
                InputStream input = archive.getInputStream(item);
                if (encryptedSevenZEntry(item) && !ArchiveOps.hasPassword(file)) throw new ArchiveOps.PasswordRequired(file);
                return new FilterInputStream(input) {
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { archive.close(); }
                    }
                };
            }
            throw new IOException("7Z 条目不存在：" + entry);
        } catch (IOException | RuntimeException error) {
            try { archive.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            if (error instanceof org.apache.commons.compress.PasswordRequiredException) throw new ArchiveOps.PasswordRequired(file);
            throw error;
        }
    }

    private static void extractSevenZ(File file, String prefix, File output, boolean selected) throws IOException {
        String requested = selection(prefix);
        String selectedName = requested.endsWith("/") ? requested.substring(0, requested.length() - 1) : requested;
        selectedName = selectedName.substring(selectedName.lastIndexOf('/') + 1);
        List<Path> created = new ArrayList<>();
        long total = 0;
        try {
            directories(output.toPath(), created);
            try (SevenZFile archive = openSevenZ(file)) {
                SevenZArchiveEntry item;
                while ((item = archive.getNextEntry()) != null) {
                    String path = sevenPath(item);
                    if (path.isEmpty()) continue;
                    Tasks.Progress.step(path);
                    String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                    if (!requested.isEmpty() && !normalized.equals(requested)
                            && !(requested.endsWith("/") && path.startsWith(requested))) continue;
                    String relative = requested.endsWith("/") ? path.substring(requested.length()) : path;
                    if (selected && !requested.isEmpty()) relative = requested.endsWith("/")
                            ? selectedName + "/" + relative : selectedName;
                    if (relative.isEmpty()) continue;
                    Path target = ArchiveOps.safeTarget(output, relative).toPath();
                    if (item.isDirectory()) { directories(target, created); continue; }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("解压目标已存在：" + relative);
                    if (item.getSize() > MAX_EXTRACTED - total) throw new IOException("本次解压超过 4 GiB 上限");
                    directories(target.getParent(), created);
                    Path temporary = Files.createTempFile(target.getParent(), ".7z-entry-", ".tmp");
                    try {
                        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                            total += copySevenZ(archive, out, MAX_EXTRACTED - total);
                        }
                        if(item.getLastModifiedDate()!=null)FileTimes.extracted(temporary.toFile(),item.getLastModifiedDate().getTime());
                        Files.move(temporary, target);
                        created.add(target);
                    } finally { Files.deleteIfExists(temporary); }
                }
            }
        } catch (IOException | RuntimeException error) {
            for (int i = created.size() - 1; i >= 0; i--) {
                try { Files.deleteIfExists(created.get(i)); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }

    private static void packSevenZ(FileStore store, List<FileStore.Node> nodes, File output, int level, char[] password) throws IOException {
        Path temporary = Files.createTempFile(output.getAbsoluteFile().getParentFile().toPath(), ".7z-pack-", ".tmp");
        try {
            try (SevenZOutputFile archive = new SevenZOutputFile(temporary.toFile(), password.length == 0 ? null : password)) {
                if(level==0)archive.setContentCompression(org.apache.commons.compress.archivers.sevenz.SevenZMethod.COPY);
                else archive.setContentMethods(List.of(new org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration(org.apache.commons.compress.archivers.sevenz.SevenZMethod.LZMA2,lzmaOptions(level))));
                Set<String> seen = new HashSet<>(), written = new HashSet<>();
                for (FileStore.Node node : nodes) packSevenZNode(store, node, node.name, archive, seen, written, 0);
            }
            Tasks.Progress.check();
            FileStore.replace(temporary.toFile(), output);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void packSevenZNode(FileStore store, FileStore.Node node, String path,
            SevenZOutputFile output, Set<String> seen, Set<String> written, int depth) throws IOException {
        Tasks.Progress.step(path);
        ArchiveOps.validateEntry(path);
        if (node.file != null && Files.isSymbolicLink(node.file.toPath()))
            throw new IOException("7Z 压缩不跟随符号链接：" + path);
        String key = node.file != null ? node.file.getCanonicalPath() : node.key();
        if (depth > 256 || !seen.add(key)) throw new IOException("目录包含循环引用或嵌套过深");
        if (!written.add(path)) throw new IOException("归档存在同名条目：" + path);
        File payload = null;
        try {
            SevenZArchiveEntry entry = new SevenZArchiveEntry();
            entry.setName(path + (node.directory ? "/" : ""));
            entry.setDirectory(node.directory);
            if (node.modified > 0) entry.setLastModifiedDate(new Date(node.modified));
            if (!node.directory) {
                if (node.file == null || node.root || node.size <= 0) payload = store.materialize(node);
                long length = payload != null ? payload.length() : node.file != null ? node.file.length() : node.size;
                entry.setSize(length); entry.setHasStream(true);
            }
            output.putArchiveEntry(entry);
            if (!node.directory) try (InputStream input = payload == null ? store.read(node)
                    : new BufferedInputStream(new FileInputStream(payload))) {
                byte[] buffer = new byte[131072]; int count;
                while (true) {
                    Tasks.Progress.check(); count = input.read(buffer); if (count < 0) break;
                    if (count > 0) { Tasks.Progress.check(); output.write(buffer, 0, count); Tasks.Progress.bytes(count); }
                }
            }
            output.closeArchiveEntry();
            if (node.directory) for (FileStore.Node child : store.list(node))
                packSevenZNode(store, child, path + "/" + child.name, output, seen, written, depth + 1);
        } finally {
            seen.remove(key);
            if (payload != null) store.release(node, payload);
        }
    }

    private static String sevenPath(SevenZArchiveEntry entry) throws IOException {
        if (entry.isAntiItem()) throw new IOException("7Z 反条目不受支持：" + entry.getName());
        String name = entry.getName();
        while (name.startsWith("./")) name = name.substring(2);
        if (name.isEmpty() || entry.isDirectory() && name.equals(".")) return "";
        ArchiveOps.validateEntry(name);
        for (String piece : name.split("/")) if (piece.isEmpty()) throw new IOException("7Z 路径包含空目录名");
        if (name.matches("^[A-Za-z]:.*")) throw new IOException("7Z 条目路径无效：" + name);
        return entry.isDirectory() && !name.endsWith("/") ? name + "/" : name;
    }

    private static long copySevenZ(SevenZFile input, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long count = 0;
        int read;
        while (true) {
            Tasks.Progress.check(); read = input.read(buffer, 0, buffer.length); if (read < 0) break;
            if (read == 0) continue;
            if (read > remaining - count) throw new IOException("本次解压超过 4 GiB 上限");
            Tasks.Progress.check();
            output.write(buffer, 0, read);
            Tasks.Progress.bytes(read);
            count += read;
        }
        return count;
    }

    private static TarArchiveInputStream open(File file) throws IOException {
        return new TarArchiveInputStream(compressedInput(file),"UTF-8");
    }
    private static InputStream compressedInput(File file)throws IOException {
        BufferedInputStream input = new BufferedInputStream(new FilterInputStream(new FileInputStream(file)) {
            @Override public int read() throws IOException { Tasks.Progress.check(); return super.read(); }
            @Override public int read(byte[] b, int off, int len) throws IOException { Tasks.Progress.check(); return in.read(b, off, len); }
            @Override public long skip(long n) throws IOException { Tasks.Progress.check(); return in.skip(n); }
        });
        try {
            input.mark(6);
            byte[] signature = new byte[6]; int length = input.read(signature);
            input.reset();
            InputStream content = length >= 2 && signature[0] == 0x1f && (signature[1] & 255) == 0x8b ? new GzipCompressorInputStream(input)
                    : BZip2CompressorInputStream.matches(signature,length) ? new BZip2CompressorInputStream(input)
                    : XZCompressorInputStream.matches(signature,length) ? new XZCompressorInputStream(input,false,128*1024)
                    : FramedLZ4CompressorInputStream.matches(signature,length) ? new FramedLZ4CompressorInputStream(input)
                    : length>=4 && (signature[0]&255)==0x28 && (signature[1]&255)==0xb5 && (signature[2]&255)==0x2f && (signature[3]&255)==0xfd ? new com.github.luben.zstd.ZstdInputStream(input) : input;
            if(content==input&&single(file))throw new IOException("压缩流格式无效");
            return content;
        } catch (IOException | RuntimeException error) {
            try { input.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    private static org.tukaani.xz.LZMA2Options lzmaOptions(int level)throws IOException {
        org.tukaani.xz.LZMA2Options options=new org.tukaani.xz.LZMA2Options(level);
        options.setDictSize(Math.min(options.getDictSize(),8*1024*1024));return options;
    }
    private static OutputStream compressedOutput(OutputStream out,String format,int level)throws IOException {
        return switch(format){case "gz"->{var parameters=new org.apache.commons.compress.compressors.gzip.GzipParameters();parameters.setCompressionLevel(level);yield new GzipCompressorOutputStream(out,parameters);}
            case "bz2"->new BZip2CompressorOutputStream(out,Math.max(1,level));case "xz"->new org.tukaani.xz.XZOutputStream(out,lzmaOptions(level));
            case "lz4"->new FramedLZ4CompressorOutputStream(out);case "zst"->new com.github.luben.zstd.ZstdOutputStream(out,Math.max(1,level));default->out;};
    }
    private static void packStream(FileStore store,List<FileStore.Node> nodes,File output,String format,int level)throws IOException {
        if(nodes.size()!=1||nodes.get(0).directory)throw new IOException("独立压缩流仅支持一个文件；文件夹或多个文件请使用 TAR."+format.toUpperCase(Locale.ROOT));
        FileStore.Node node=nodes.get(0);if(node.file!=null&&Files.isSymbolicLink(node.file.toPath()))throw new IOException("压缩不跟随符号链接");
        Path temporary=Files.createTempFile(output.getAbsoluteFile().getParentFile().toPath(),".stream-",".tmp");
        try {try(InputStream in=store.read(node);OutputStream file=Files.newOutputStream(temporary);OutputStream out=compressedOutput(file,format,level)){FileStore.pump(in,out);}
            Tasks.Progress.check();FileStore.replace(temporary.toFile(),output);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static void extractStream(File file,String prefix,File output)throws IOException {
        String name=streamName(file);if(!prefix.isEmpty()&&!prefix.equals(name))throw new IOException("压缩流条目不存在");
        List<Path> created=new ArrayList<>();
        try{directories(output.toPath(),created);Path target=ArchiveOps.safeTarget(output,name).toPath();if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("解压目标已存在："+name);
            Path temporary=Files.createTempFile(output.toPath(),".stream-entry-",".tmp");
            try{try(InputStream in=compressedInput(file);OutputStream out=Files.newOutputStream(temporary)){copy(in,out,MAX_EXTRACTED);}Tasks.Progress.check();Files.move(temporary,target);created.add(target);}finally{Files.deleteIfExists(temporary);}
        }catch(IOException|RuntimeException e){for(int i=created.size()-1;i>=0;i--)try{Files.deleteIfExists(created.get(i));}catch(IOException cleanup){e.addSuppressed(cleanup);}throw e;}
    }

    private static String path(TarArchiveEntry entry, TarArchiveInputStream input) throws IOException {
        if (entry.isSymbolicLink() || entry.isLink()) throw new IOException("TAR 链接条目不支持读取或解压");
        if ((!entry.isFile() && !entry.isDirectory()) || !input.canReadEntryData(entry))
            throw new IOException("不支持的 TAR 条目类型：" + entry.getName());
        String name = entry.getName();
        while (name.startsWith("./")) name = name.substring(2);
        if (entry.isDirectory() && (name.isEmpty() || name.equals("."))) return "";
        ArchiveOps.validateEntry(name);
        for (String piece : name.split("/")) if (piece.isEmpty()) throw new IOException("TAR 路径包含空目录名");
        if (name.matches("^[A-Za-z]:.*")) throw new IOException("TAR 条目路径无效：" + name);
        return entry.isDirectory() && !name.endsWith("/") ? name + "/" : name;
    }

    private static String selection(String value) throws IOException {
        if (!value.isEmpty()) {
            ArchiveOps.validateEntry(value);
            for (String piece : value.split("/")) if (piece.isEmpty()) throw new IOException("TAR 路径包含空目录名");
        }
        return value;
    }

    private static void directories(Path directory, List<Path> created) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory))
                throw new IOException("解压目录不是普通文件夹：" + directory);
            return;
        }
        Path parent = directory.getParent();
        if (parent != null) directories(parent, created);
        Files.createDirectory(directory);
        created.add(directory);
    }

    private static long copy(InputStream input, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long count = 0;
        int read;
        while (true) {
            Tasks.Progress.check(); read = input.read(buffer); if (read < 0) break;
            if (read == 0) continue;
            if (read > remaining - count) throw new IOException("本次解压超过 4 GiB 上限");
            Tasks.Progress.check();
            output.write(buffer, 0, read);
            Tasks.Progress.bytes(read);
            count += read;
        }
        return count;
    }

    private ArchiveFormats() { }
}
