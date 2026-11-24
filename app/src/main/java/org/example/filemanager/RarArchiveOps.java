package org.example.filemanager;

import com.github.junrar.Archive;
import com.github.junrar.ArchiveOptions;
import com.github.junrar.exception.CrcErrorException;
import com.github.junrar.exception.WrongPasswordException;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.rarfile.rar5.Rar5Redirection;
import com.github.junrar.rarfile.rar5.Rar5RedirType;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

final class RarArchiveOps {
    private static final long MAX_DICTIONARY = 256L * 1024 * 1024;
    private static final long MAX_EXTRACTED = 4L * 1024 * 1024 * 1024;

    private RarArchiveOps() {}

    static List<ArchiveOps.Item> list(File file, String prefix) throws IOException {
        Map<String, Map<String, ArchiveOps.Item>> folders = new HashMap<>();
        try (Archive archive = open(file)) {
            for (FileHeader header : archive.getFileHeaders()) {
                String raw = normalize(header.getFileName());
                boolean directory = header.isDirectory() || raw.endsWith("/");
                String name = directory && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
                ArchiveOps.validateEntry(name);
                String[] pieces = name.split("/");
                String parent = "";
                for (int i = 0; i < pieces.length; i++) {
                    if (pieces[i].isEmpty()) throw new IOException("RAR 路径包含空目录名");
                    boolean folder = i < pieces.length - 1 || directory;
                    String child = parent + pieces[i] + (folder ? "/" : "");
                    Map<String, ArchiveOps.Item> children = folders.computeIfAbsent(parent, ignored -> new LinkedHashMap<>());
                    if (!children.containsKey(child)) {
                        long size = folder ? 0 : Math.max(0, header.getFullUnpackSize());
                        long modified = header.getLastModifiedTime() == null ? 0 : header.getLastModifiedTime().toMillis();
                        children.put(child, new ArchiveOps.Item(child, pieces[i], folder, size, modified));
                    }
                    parent = child;
                }
            }
        } catch (Exception error) {
            throw io("无法读取 RAR 归档", error);
        }
        List<ArchiveOps.Item> result = new ArrayList<>(folders.getOrDefault(prefix, Collections.emptyMap()).values());
        sort(result);
        return result;
    }

    static long entrySize(File file, String entry) throws IOException {
        try (Archive archive = open(file)) {
            for (FileHeader header : archive.getFileHeaders()) {
                if (normalize(header.getFileName()).equals(entry)) return header.isDirectory() ? 0 : header.getFullUnpackSize();
            }
        } catch (Exception error) {
            throw io("无法读取 RAR 条目", error);
        }
        return -1;
    }

    static List<String> entryNames(File file) throws IOException {
        List<String> names = new ArrayList<>();
        try (Archive archive = open(file)) {
            for (FileHeader header : archive.getFileHeaders()) names.add(normalize(header.getFileName()));
        } catch (Exception error) {
            throw io("无法读取 RAR 目录", error);
        }
        return names;
    }
    static InputStream openEntry(File file, String entry) throws IOException {
        Archive archive = open(file);
        try {
            FileHeader match = null;
            for (FileHeader header : archive.getFileHeaders()) {
                if (normalize(header.getFileName()).equals(entry)) { match = header; break; }
            }
            if (match == null || match.isDirectory()) throw new IOException("RAR 条目不存在");
            checkReadable(file, match);
            if (match.isEncrypted() || match.isSplitAfter()) {
                File temporary = File.createTempFile("rar-member-", ".tmp");
                try {
                    extractVerified(file, archive, match, temporary, MAX_EXTRACTED);
                    archive.close();
                    return new FilterInputStream(new FileInputStream(temporary)) {
                        @Override public void close() throws IOException {
                            try { super.close(); } finally { Files.deleteIfExists(temporary.toPath()); }
                        }
                    };
                } catch (Exception error) {
                    Files.deleteIfExists(temporary.toPath());
                    throw error;
                }
            }
            InputStream content = archive.getInputStream(match);
            return new FilterInputStream(content) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { archive.close(); }
                }
            };
        } catch (Exception error) {
            try { archive.close(); } catch (IOException ignored) { }
            throw io("无法读取 RAR 条目", error);
        }
    }


    static void extract(File file, String prefix, File output, boolean selected) throws IOException {
        if (!output.isDirectory() && !output.mkdirs()) throw new IOException("无法创建输出目录");
        String selectedName = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        selectedName = selectedName.substring(selectedName.lastIndexOf('/') + 1);
        long total = 0;
        try (Archive archive = open(file)) {
            for (FileHeader header : archive.getFileHeaders()) {
                String path = normalize(header.getFileName());
                Tasks.Progress.step(path);
                checkNoLink(header);
                boolean directory = header.isDirectory() || path.endsWith("/");
                String normalized = directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                ArchiveOps.validateEntry(normalized);
                if (!prefix.isEmpty() && !normalized.equals(prefix) && !(prefix.endsWith("/") && path.startsWith(prefix))) continue;
                String relative = prefix.endsWith("/") ? path.substring(prefix.length()) : path;
                if (selected && !prefix.isEmpty()) relative = prefix.endsWith("/") ? selectedName + "/" + relative : selectedName;
                if (relative.isEmpty()) continue;
                File target = ArchiveOps.safeTarget(output, relative);
                if (directory) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("创建目录失败");
                    continue;
                }
                if (target.exists()) throw new IOException("解压目标已存在：" + relative);
                checkReadable(file, header);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("创建目录失败");
                File tmp = File.createTempFile(".extract-", ".tmp", parent);
                try {
                    total += extractVerified(file, archive, header, tmp, MAX_EXTRACTED - total);
                    Files.move(tmp.toPath(), target.toPath());
                } finally { Files.deleteIfExists(tmp.toPath()); }
            }
        } catch (Exception error) {
            throw io("RAR 解压失败", error);
        }
    }

    private static Archive open(File file) throws IOException {
        char[] value = ArchiveOps.password(file);
        char[] password = value == null ? null : value.clone();
        try {
            var options = ArchiveOptions.builder().maxDictionarySize(MAX_DICTIONARY);
            if (password != null) options.password(password);
            return new Archive(file, options.build());
        } catch (WrongPasswordException wrongPassword) {
            ArchiveOps.clearPassword(file);
            throw new ArchiveOps.PasswordRequired(file);
        } catch (Exception error) {
            throw io("不是可读取的 RAR/RAR5 文件，或归档已损坏", error);
        } finally { if (password != null) Arrays.fill(password, '\0'); }
    }

    static boolean encrypted(File file) {
        try (Archive archive = open(file)) { return archive.isPasswordProtected(); }
        catch (ArchiveOps.PasswordRequired required) { return true; }
        catch (Exception error) { return false; }
    }

    private static void checkReadable(File file, FileHeader header) throws IOException {
        checkNoLink(header);
        if (header.isEncrypted() && !ArchiveOps.hasPassword(file)) throw new ArchiveOps.PasswordRequired(file);
        if (header.isSplitBefore()) throw new IOException("请从第一卷打开 RAR");
    }

    private static void checkNoLink(FileHeader header) throws IOException {
        Rar5Redirection link = header.getRedirection();
        if (link != null && link.getType() != null && link.getType() != Rar5RedirType.NONE)
            throw new IOException("为避免路径越界，RAR 链接条目不支持读取或解压");
    }

    private static String normalize(String name) throws IOException {
        if (name == null || name.isEmpty()) throw new IOException("RAR 条目名称为空");
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*") || normalized.contains(":"))
            throw new IOException("RAR 条目路径无效：" + name);
        return normalized;
    }

    private static long extractVerified(File file, Archive archive, FileHeader header, File output, long limit) throws Exception {
        if (header.getFullUnpackSize() > limit) throw new IOException("本次解压超过 4 GiB 上限");
        long[] bytes = {0};
        try (OutputStream target = new BufferedOutputStream(new FileOutputStream(output))) {
            archive.extractFile(header, new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
                @Override public void write(byte[] data, int start, int count) throws IOException {
                    Tasks.Progress.check();
                    if (count > limit - bytes[0]) throw new IOException("本次解压超过 4 GiB 上限");
                    target.write(data, start, count);
                    bytes[0] += count;
                    Tasks.Progress.bytes(count);
                }
            });
        } catch (WrongPasswordException wrongPassword) {
            ArchiveOps.clearPassword(file);
            throw new ArchiveOps.PasswordRequired(file);
        } catch (CrcErrorException checksumFailure) {
            String message = header.isEncrypted() && header.isSplitAfter()
                    ? "加密 RAR 跨卷条目校验失败，请检查密码及分卷是否完整"
                    : "RAR 条目校验失败，请检查归档和密码";
            throw new IOException(message, checksumFailure);
        }
        if (bytes[0] != header.getFullUnpackSize()) throw new IOException("RAR 条目不完整，请检查所有分卷与密码");
        return bytes[0];
    }

    private static void sort(List<ArchiveOps.Item> items) {
        items.sort((a, b) -> a.directory() != b.directory() ? (a.directory() ? -1 : 1)
                : a.name().compareToIgnoreCase(b.name()));
    }

    private static IOException io(String message, Exception error) {
        return error instanceof IOException value ? value : new IOException(message + ": " + error.getMessage(), error);
    }
}
