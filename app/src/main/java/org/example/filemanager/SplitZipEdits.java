package org.example.filemanager;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.lingala.zip4j.ZipFile;

/** Rebuilds split volumes next to their source and keeps an original set during publication. */
final class SplitZipEdits {
    private record Volume(Path path, FileStore.FileState state) { }
    private record JournalEntry(String name, long device, long inode, String digest) { }
    private record Journal(String phase, List<JournalEntry> originals, List<JournalEntry> outputs) { }
    private static final String PREFIX = ".dualstack-split-";
    private static final Object[] DIRECTORY_LOCKS = new Object[64];
    static { java.util.Arrays.setAll(DIRECTORY_LOCKS, ignored -> new Object()); }

    private SplitZipEdits() { }

    static boolean canEdit(FileStore.Node node) throws IOException {
        return node.file != null && node.container == null && !node.root && node.file.canRead()
                && node.name.toLowerCase(Locale.ROOT).endsWith(".zip") && node.file.getParentFile().canWrite()
                && ArchiveOps.isSplitZip(node.file);
    }

    static String fingerprint(File archive) throws IOException {
        return revision(snapshot(archive));
    }

    static void rewrite(File archive, String expected, Map<String, String> renames,
                        Map<String, File> additions, Set<String> removals) throws IOException {
        Path parent = archive.toPath().toAbsolutePath().getParent();
        synchronized (ArchiveOps.lock(archive)) { synchronized (directoryLock(parent)) {
            recoverDirectory(parent.toFile());
            if (ArchiveOps.isEncrypted(archive)) throw new IOException("加密分卷 ZIP 请先合并为单卷再处理");
            List<Volume> source = snapshot(archive);
            if (expected != null && !expected.equals(revision(source))) throw new IOException("归档分卷已变化，请重新打开");
            long volumeSize = Math.max(65536, source.get(0).state.size());
            Path folder = Files.createTempDirectory(parent, PREFIX);
            Path backup = Files.createDirectory(folder.resolve("original"));
            Path staging = Files.createDirectory(folder.resolve("staged"));
            boolean preserve = false;
            try {
                String name = archive.getName();
                File main = staging.resolve(name.substring(0, name.length() - 4) + ".zip").toFile();
                ArchiveOps.rewriteSplitCompressed(archive, source.stream().map(Volume::path).toArray(Path[]::new),
                        main, volumeSize, renames, additions, removals);
                List<Volume> output = snapshot(main);
                Map<Path, Volume> oldPaths = new LinkedHashMap<>();
                for (Volume volume : source) oldPaths.put(volume.path, volume);
                for (Volume volume : output) {
                    Path target = volume.path.equals(main.toPath()) ? archive.toPath().toAbsolutePath() : parent.resolve(volume.path.getFileName());
                    if (!oldPaths.containsKey(target) && Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("同名 ZIP 分卷已存在：" + target.getFileName());
                    Volume original = oldPaths.get(target);
                    if (original != null) copyPermissions(volume.path, original.state);
                    try (RandomAccessFile out = new RandomAccessFile(volume.path.toFile(), "r")) { out.getFD().sync(); }
                }
                if (!source.equals(snapshot(archive))) throw new IOException("重建期间 ZIP 分卷发生变化");
                Tasks.Progress.check();
                writeJournal(folder, new Journal("prepared", entries(source), outputEntries(archive, main, output)));
                syncDirectory(staging); syncDirectory(parent);
                List<Volume> withdrawn = new ArrayList<>();
                Map<Path, Volume> published = new LinkedHashMap<>();
                try {
                    // Remove and restore the .zip index at the boundary so ordinary readers discover complete sets.
                    List<Volume> descending = new ArrayList<>(source);
                    Collections.reverse(descending);
                    for (Volume volume : descending) {
                        Files.move(volume.path, backup.resolve(volume.path.getFileName()), StandardCopyOption.ATOMIC_MOVE);
                        withdrawn.add(volume);
                    }
                    syncDirectory(parent); syncDirectory(backup);
                    for (Volume volume : output) {
                        Path target = volume.path.equals(main.toPath()) ? archive.toPath().toAbsolutePath() : parent.resolve(volume.path.getFileName());
                        Files.move(volume.path, target);
                        published.put(target, volume);
                    }
                    syncDirectory(parent); syncDirectory(staging);
                    writeJournal(folder, new Journal("committed", entries(source), outputEntries(archive, main, output)));
                    ArchiveOps.forget(archive);
                } catch (IOException | RuntimeException failure) {
                    List<Exception> errors = new ArrayList<>();
                    Tasks.Progress.cleanup(() -> rollback(parent, backup, withdrawn, published, errors));
                    if (!errors.isEmpty()) {
                        preserve = true;
                        IOException incomplete = new IOException("分卷写回未完成，原件保存在 " + backup, failure);
                        for (var error : errors) incomplete.addSuppressed(error);
                        throw incomplete;
                    }
                    throw failure;
                }
            } finally {
                if (!preserve) cleanup(folder);
            }
        } }
    }

    static void writeJournal(Path folder, String phase, File archive, List<File> originals,
                             File stagedMain, List<File> stagedOutputs) throws IOException {
        List<JournalEntry> old = new ArrayList<>();
        for (File file : originals) {
            FileStore.FileState state = FileStore.FileState.read(file);
            old.add(new JournalEntry(file.getName(), state.device(), state.inode(), state.digest()));
        }
        List<JournalEntry> next = new ArrayList<>();
        for (File file : stagedOutputs) {
            String name = file.equals(stagedMain) ? archive.getName() : file.getName();
            FileStore.FileState state = FileStore.FileState.read(file);
            next.add(new JournalEntry(name, state.device(), state.inode(), state.digest()));
        }
        writeJournal(folder, new Journal(phase, old, next));
    }

    private static List<JournalEntry> entries(List<Volume> volumes) {
        List<JournalEntry> result = new ArrayList<>();
        for (Volume volume : volumes) result.add(new JournalEntry(volume.path.getFileName().toString(),
                volume.state.device(), volume.state.inode(), volume.state.digest()));
        return result;
    }

    private static List<JournalEntry> outputEntries(File archive, File main, List<Volume> volumes) {
        List<JournalEntry> result = new ArrayList<>();
        for (Volume volume : volumes) {
            String name = volume.path.equals(main.toPath()) ? archive.getName() : volume.path.getFileName().toString();
            result.add(new JournalEntry(name, volume.state.device(), volume.state.inode(), volume.state.digest()));
        }
        return result;
    }

    private static Object directoryLock(Path directory) {
        return DIRECTORY_LOCKS[directory.toAbsolutePath().normalize().hashCode() & (DIRECTORY_LOCKS.length - 1)];
    }

    static void recoverDirectory(File directory) throws IOException {
        if (!directory.isDirectory()) return;
        File[] entries = directory.listFiles();
        if (entries == null) return;
        recoverDirectory(directory, entries);
    }

    static boolean recoverDirectory(File directory, File[] entries) throws IOException {
        return recoverDirectory(directory, entries, true);
    }

    static boolean recoverForListing(File directory, File[] entries) throws IOException {
        return recoverDirectory(directory, entries, false);
    }

    private static boolean recoverDirectory(File directory, File[] entries, boolean strict) throws IOException {
        if (java.util.Arrays.stream(entries).noneMatch(entry -> entry.getName().startsWith(PREFIX))) return false;
        Path parent = directory.toPath().toAbsolutePath().normalize();
        synchronized (directoryLock(parent)) {
            boolean recovered = false;
            for (File entry : entries) {
                if (!entry.getName().startsWith(PREFIX) || !Files.isDirectory(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    recovered |= recoverTransaction(entry.toPath(), parent);
                } catch (IOException error) {
                    if (strict) throw error;
                    // Preserve the incomplete transaction and keep the containing directory browsable.
                    android.util.Log.w("SplitZipEdits", "保留无法恢复的分卷事务：" + entry, error);
                }
            }
            return recovered;
        }
    }

    private static boolean recoverTransaction(Path folder, Path parent) throws IOException {
        Path journalPath = folder.resolve("journal");
        if (!Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            Path backup = folder.resolve("original");
            try (var files = Files.list(backup)) {
                if (files.findAny().isPresent()) throw new IOException("分卷 ZIP 备份缺少恢复记录，原件保留在：" + backup);
            } catch (java.nio.file.NoSuchFileException ignored) { }
            // A name prefix alone is not proof that a directory belongs to us.
            return false;
        }
        Journal journal = readJournal(journalPath);
        validateVolumeSet(journal.originals, journal.outputs);
        validateLayout(folder, journal);
        if (journal.phase.equals("committed")) {
            for (JournalEntry output : journal.outputs) {
                Path published = parent.resolve(output.name);
                if (!Files.isRegularFile(published, LinkOption.NOFOLLOW_LINKS)
                        || !same(output, FileStore.FileState.read(published.toFile())))
                    throw new IOException("已写回的 ZIP 分卷发生变化，备份保留：" + published);
            }
            cleanup(folder); return true;
        }
        Path backup = folder.resolve("original");
        if (!Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("分卷 ZIP 原件备份目录无效：" + backup);
        // Verify every original before removing any published output. Recovery may itself be interrupted.
        for (JournalEntry original : journal.originals) {
            Path saved = backup.resolve(original.name), target = parent.resolve(original.name);
            Path existing = Files.exists(saved, LinkOption.NOFOLLOW_LINKS) ? saved : target;
            if (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)
                    || !same(original, FileStore.FileState.read(existing.toFile())))
                throw new IOException("分卷 ZIP 原卷已变化或缺失，现有文件及备份均保留：" + existing);
        }
        Map<String, JournalEntry> originals = new LinkedHashMap<>();
        for (JournalEntry item : journal.originals) originals.put(item.name, item);
        for (JournalEntry output : journal.outputs) {
            Path target = parent.resolve(output.name);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            FileStore.FileState current = FileStore.FileState.read(target.toFile());
            JournalEntry original = originals.get(output.name);
            if (!same(output, current) && (original == null || !same(original, current)))
                throw new IOException("分卷 ZIP 恢复遇到外部文件，原件与备份均保留：" + target);
        }
        Journal recovering = new Journal("recovering", journal.originals, journal.outputs);
        writeJournal(folder, recovering);
        for (JournalEntry output : journal.outputs) {
            Path target = parent.resolve(output.name);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            FileStore.FileState current = FileStore.FileState.read(target.toFile());
            JournalEntry original = originals.get(output.name);
            boolean savedOriginal = original != null && same(original, current)
                    && !Files.exists(backup.resolve(output.name), LinkOption.NOFOLLOW_LINKS);
            if (savedOriginal) continue;
            if (same(output, current)) Files.delete(target);
            else if (original == null || !same(original, current))
                throw new IOException("分卷 ZIP 恢复遇到外部文件，原件与备份均保留：" + target);
        }
        syncDirectory(parent);
        for (JournalEntry original : journal.originals) {
            Path saved = backup.resolve(original.name), target = parent.resolve(original.name);
            if (Files.exists(saved, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!same(original, FileStore.FileState.read(target.toFile())))
                        throw new IOException("分卷 ZIP 原卷目标被外部占用，备份保留在：" + backup);
                    Files.delete(saved);
                } else Files.move(saved, target, StandardCopyOption.ATOMIC_MOVE);
            } else if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || !same(original, FileStore.FileState.read(target.toFile())))
                throw new IOException("分卷 ZIP 原卷缺失，备份保留在：" + backup);
        }
        syncDirectory(parent); syncDirectory(backup);
        for (JournalEntry original : journal.originals)
            if (original.name.toLowerCase(Locale.ROOT).endsWith(".zip")) ArchiveOps.forget(parent.resolve(original.name).toFile());
        cleanup(folder);
        return true;
    }

    private static void writeJournal(Path folder, Journal journal) throws IOException {
        List<String> lines = new ArrayList<>(); lines.add("dualstack-split-v1"); lines.add("P\t" + journal.phase);
        for (JournalEntry item : journal.originals) lines.add("O\t" + encode(item.name) + "\t" + item.device + "\t" + item.inode + "\t" + item.digest);
        for (JournalEntry item : journal.outputs) lines.add("N\t" + encode(item.name) + "\t" + item.device + "\t" + item.inode + "\t" + item.digest);
        Path temp = folder.resolve("journal.tmp"), target = folder.resolve("journal");
        Files.deleteIfExists(temp);
        try (var out = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            var data = StandardCharsets.UTF_8.encode(String.join("\n", lines) + "\n");
            while (data.hasRemaining()) out.write(data);
            out.force(true);
        }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(folder);
    }

    private static void validateVolumeSet(List<JournalEntry> originals, List<JournalEntry> outputs) throws IOException {
        String main = originals.get(originals.size() - 1).name;
        if (!main.toLowerCase(Locale.ROOT).endsWith(".zip") || !main.equals(outputs.get(outputs.size() - 1).name))
            throw new IOException("分卷 ZIP 恢复记录的主卷名称不一致");
        String prefix = main.substring(0, main.length() - 4);
        for (List<JournalEntry> set : List.of(originals, outputs)) {
            for (int i = 0; i < set.size() - 1; i++) {
                String part = String.format(Locale.ROOT, "%s.z%02d", prefix, i + 1);
                if (!part.equalsIgnoreCase(set.get(i).name)) throw new IOException("分卷 ZIP 恢复记录卷名不连续");
            }
        }
    }

    private static void validateLayout(Path folder, Journal journal) throws IOException {
        for (File entry : listChildren(folder)) {
            String name = entry.getName();
            if (name.equals("journal") || name.equals("journal.tmp")) {
                if (Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            } else if (name.equals("original") || name.equals("staged")) {
                if (!Files.isDirectory(entry.toPath(), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("分卷暂存目录已被替换：" + entry);
                var allowed = new java.util.HashSet<String>();
                List<JournalEntry> set = name.equals("original") ? journal.originals : journal.outputs;
                for (JournalEntry part : set) allowed.add(part.name);
                if (name.equals("staged")) {
                    String main = set.get(set.size() - 1).name;
                    allowed.add(main.substring(0, main.length() - 4) + ".zip");
                }
                for (File part : listChildren(entry.toPath())) {
                    if (!allowed.contains(part.getName()) || !Files.isRegularFile(part.toPath(), LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("暂存目录内存在额外文件，已保留：" + part);
                }
                continue;
            }
            throw new IOException("分卷事务目录内存在额外文件，已保留：" + entry);
        }
    }

    private static File[] listChildren(Path folder) throws IOException {
        File[] children = folder.toFile().listFiles();
        if (children == null) throw new IOException("无法读取分卷暂存目录：" + folder);
        return children;
    }

    private static Journal readJournal(Path path) throws IOException {
        if (Files.size(path) > 1024 * 1024) throw new IOException("分卷 ZIP 恢复记录过大：" + path);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < 4 || !lines.get(0).equals("dualstack-split-v1")) throw new IOException("分卷 ZIP 恢复记录无效：" + path);
        String phase = lines.get(1).startsWith("P\t") ? lines.get(1).substring(2) : "";
        if (!Set.of("prepared", "recovering", "committed").contains(phase)) throw new IOException("分卷 ZIP 恢复阶段无效：" + path);
        List<JournalEntry> originals = new ArrayList<>(), outputs = new ArrayList<>();
        Set<String> oldNames = new java.util.HashSet<>(), newNames = new java.util.HashSet<>();
        for (int i = 2; i < lines.size(); i++) {
            String[] fields = lines.get(i).split("\t", -1);
            if (fields.length != 5 || !(fields[0].equals("O") || fields[0].equals("N"))
                    || !fields[2].matches("[0-9]+") || !fields[3].matches("[0-9]+") || !fields[4].matches("[0-9a-f]{64}"))
                throw new IOException("分卷 ZIP 恢复记录损坏：" + path);
            String name;
            try { name = new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException error) { throw new IOException("分卷 ZIP 恢复文件名无效", error); }
            FileStore.checkName(name);
            if (!(fields[0].equals("O") ? oldNames : newNames).add(name))
                throw new IOException("分卷 ZIP 恢复记录存在重复文件名");
            try {
                (fields[0].equals("O") ? originals : outputs).add(new JournalEntry(name,
                        Long.parseLong(fields[2]), Long.parseLong(fields[3]), fields[4]));
            } catch (NumberFormatException error) { throw new IOException("分卷 ZIP 恢复身份无效", error); }
        }
        if (originals.isEmpty() || outputs.isEmpty()) throw new IOException("分卷 ZIP 恢复记录不完整：" + path);
        return new Journal(phase, originals, outputs);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean same(JournalEntry expected, FileStore.FileState actual) {
        return expected.device == actual.device() && expected.inode == actual.inode()
                && expected.digest.equals(actual.digest());
    }

    private static void syncDirectory(Path directory) throws IOException {
        try {
            java.io.FileDescriptor descriptor = android.system.Os.open(directory.toString(),
                    android.system.OsConstants.O_RDONLY, 0);
            try {
                if (!android.system.OsConstants.S_ISDIR(android.system.Os.fstat(descriptor).st_mode))
                    throw new IOException("分卷 ZIP 事务路径不是目录：" + directory);
                android.system.Os.fsync(descriptor);
            } finally { android.system.Os.close(descriptor); }
        } catch (android.system.ErrnoException error) { throw new IOException("无法持久化分卷 ZIP 事务目录：" + directory, error); }
    }

    private static void rollback(Path parent, Path backup, List<Volume> withdrawn, Map<Path, Volume> published, List<Exception> errors) {
        List<Path> outputs = new ArrayList<>(published.keySet());
        Collections.reverse(outputs);
        for (Path path : outputs) try {
            Volume volume = published.get(path);
            FileStore.FileState current = FileStore.FileState.read(path.toFile());
            if (current.device() != volume.state.device() || current.inode() != volume.state.inode()
                    || !current.digest().equals(volume.state.digest())) throw new IOException("新分卷已在外部更改：" + path);
            Files.delete(path);
        } catch (IOException | RuntimeException error) { errors.add(error); }
        Collections.reverse(withdrawn);
        for (Volume original : withdrawn) try {
            Files.move(backup.resolve(original.path.getFileName()), original.path);
        } catch (IOException | RuntimeException error) { errors.add(error); }
        if (errors.isEmpty()) try { syncDirectory(parent); syncDirectory(backup); }
        catch (IOException error) { errors.add(error); }
    }

    private static List<Volume> snapshot(File archive) throws IOException {
        List<Volume> result = new ArrayList<>();
        Path main = archive.toPath().toAbsolutePath();
        try (ZipFile zip = new ZipFile(main.toFile())) {
            List<File> files = zip.getSplitZipFiles();
            if (files.isEmpty() || files.size() > 1024) throw new IOException("ZIP 分卷数量无效");
            for (File file : files) {
                Path path = file.toPath().toAbsolutePath();
                if (!path.getParent().equals(main.getParent())) throw new IOException("ZIP 分卷不在归档所在目录");
                result.add(new Volume(path, FileStore.FileState.read(path.toFile())));
            }
        }
        if (!result.get(result.size() - 1).path.equals(main)) throw new IOException("缺少 ZIP 主卷");
        return result;
    }

    private static String revision(List<Volume> volumes) {
        StringBuilder signature = new StringBuilder("split-zip:");
        for (Volume volume : volumes) signature.append(volume.state.fingerprint()).append('|');
        return signature.toString();
    }

    private static void copyPermissions(Path path, FileStore.FileState original) throws IOException {
        try {
            var current = android.system.Os.lstat(path.toString());
            if (current.st_uid != original.uid() || current.st_gid != original.gid())
                android.system.Os.chown(path.toString(), original.uid(), original.gid());
            if ((current.st_mode & 07777) != original.mode()) android.system.Os.chmod(path.toString(), original.mode());
        } catch (android.system.ErrnoException error) { throw new IOException(error); }
    }

    private static void cleanup(Path folder) {
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException error) { android.util.Log.w("SplitZipEdits", "清理分卷暂存失败", error); }
    }
}
