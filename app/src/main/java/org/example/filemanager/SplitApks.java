package org.example.filemanager;

import android.content.Context;
import android.content.pm.*;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Device-targeted bundletool archives and ordinary exported split APK sets. */
final class SplitApks {
    private static final String WORKSPACE_PREFIX = "split-install-";
    private static final Set<File> ACTIVE_WORKSPACES = new HashSet<>();
    record Manifest(String packageName, String version, String split) { }
    record Part(File file, Manifest manifest, String name) { }
    static final class Prepared implements AutoCloseable {
        final File folder;
        final List<Part> parts = new ArrayList<>();
        String packageName, label, selection;
        final List<String> excluded = new ArrayList<>();
        long bytes;
        Prepared(File folder) { this.folder = folder; }
        public void close() {
            synchronized (ACTIVE_WORKSPACES) {
                if (ACTIVE_WORKSPACES.remove(folder)) deleteWorkspace(folder);
            }
        }
    }

    private static void deleteWorkspace(File folder) {
        // All workspace files are direct children. Never follow a link out of the cache.
        if (Files.isDirectory(folder.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            File[] parts = folder.listFiles();
            if (parts != null) for (File part : parts) part.delete();
        }
        folder.delete();
    }

    static void cleanAbandonedWorkspaces(File cache) {
        synchronized (ACTIVE_WORKSPACES) {
            File[] sessions = cache.listFiles((dir, name) -> name.startsWith(WORKSPACE_PREFIX));
            if (sessions != null) for (File session : sessions)
                if (!ACTIVE_WORKSPACES.contains(session)) deleteWorkspace(session);
        }
    }

    private static Prepared createWorkspace(File cache) throws IOException {
        synchronized (ACTIVE_WORKSPACES) {
            cleanAbandonedWorkspaces(cache);
            File folder = Files.createTempDirectory(cache.toPath(), WORKSPACE_PREFIX).toFile();
            ACTIVE_WORKSPACES.add(folder);
            return new Prepared(folder);
        }
    }
    static boolean matches(String name) { return name.toLowerCase(Locale.ROOT).endsWith(".apks"); }
    static Prepared prepare(Context context, FileStore.Node node, AtomicBoolean cancelled) throws Exception {
        return prepare(context, node, cancelled, ApksTargeting.Profile.AUTOMATIC);
    }
    static Prepared prepare(Context context, FileStore.Node node, AtomicBoolean cancelled, ApksTargeting.Profile profile) throws Exception {
        Prepared result = createWorkspace(context.getCacheDir());
        try {
            File archive = new File(result.folder, "source.apks");
            try (InputStream in = new FileStore(context).read(node); OutputStream out = new FileOutputStream(archive)) {
                copy(in, out, cancelled);
            }
            Set<String> splits = new HashSet<>();
            Manifest identity = null;
            Part base = null;
            ApksTargeting.Device device = ApksTargeting.Device.current(context);
            try (ZipFile zip = new ZipFile(archive)) {
                ApksTargeting.Selection selected = null;
                ZipEntry toc = zip.getEntry("toc.pb");
                if (toc != null) {
                    if (toc.getSize() < 0 || toc.getSize() > 8 * 1024 * 1024) throw new IOException("APKS 目录元数据大小无效");
                    byte[] data; ByteArrayOutputStream bytes = new ByteArrayOutputStream(); CRC32 crc = new CRC32();
                    try (InputStream input = new CheckedInputStream(zip.getInputStream(toc), crc)) {
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = input.read(buffer)) != -1) {
                            check(cancelled);
                            if (bytes.size() + n > 8 * 1024 * 1024) throw new IOException("APKS 目录元数据超过 8 MB"); bytes.write(buffer, 0, n);
                        }
                    }
                    data = bytes.toByteArray();
                    if (data.length != toc.getSize() || crc.getValue() != toc.getCrc()) throw new IOException("APKS 目录元数据损坏");
                    selected = ApksTargeting.select(data, device, context, profile);
                }
                result.selection = (selected == null ? "导出拆分包 · 自动选择可用配置" : selected.description()) + "\n" + device.summary();
                Enumeration<? extends ZipEntry> entries = zip.entries();
                Set<String> found = new HashSet<>(), archiveNames = new HashSet<>();
                while (entries.hasMoreElements()) {
                    check(cancelled);
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                    ArchiveOps.validateEntry(entry.getName());
                    if (!archiveNames.add(entry.getName())) throw new IOException("APKS 内 APK 路径重复");
                    if (selected != null && !selected.paths().contains(entry.getName())) { result.excluded.add(entry.getName()); continue; }
                    found.add(entry.getName());
                    if (result.parts.size() >= 512) throw new IOException("APKS 内 APK 数量超过 512 个");
                    // Never use archive paths as filesystem paths.
                    File apk = new File(result.folder, "part-" + result.parts.size() + ".apk");
                    CRC32 crc = new CRC32();
                    try (InputStream in = new CheckedInputStream(zip.getInputStream(entry), crc); OutputStream out = new FileOutputStream(apk)) {
                        copy(in, out, cancelled);
                    }
                    if (apk.length() != entry.getSize() || crc.getValue() != entry.getCrc())
                        throw new IOException("APKS 成员损坏：" + entry.getName());
                    Manifest manifest = manifest(apk);
                    if (identity == null) identity = manifest;
                    if (!identity.packageName.equals(manifest.packageName) || !identity.version.equals(manifest.version))
                        throw new IOException("APKS 包含不同应用或不同版本，无法作为同一组安装");
                    if (!splits.add(manifest.split)) throw new IOException(manifest.split.isEmpty()
                            ? "APKS 包含多个基础包或设备变体，请使用针对当前设备导出的安装包"
                            : "APKS 包含重复拆分项：" + manifest.split);
                    Part part = new Part(apk, manifest, entry.getName());
                    result.parts.add(part); result.bytes += apk.length();
                    if (manifest.split.isEmpty()) base = part;
                }
                if (selected != null && !found.equals(selected.paths())) throw new IOException("APKS 缺少设备变体所需的 APK");
                if (selected == null) selectExported(result, device);
            }
            if (base == null) throw new IOException("APKS 缺少基础 APK（base）");
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(base.file.getPath(), 0);
            if (info == null || info.applicationInfo == null) throw new IOException("APKS 的基础 APK 无效");
            info.applicationInfo.sourceDir = base.file.getPath(); info.applicationInfo.publicSourceDir = base.file.getPath();
            result.packageName = info.packageName;
            result.label = info.applicationInfo.loadLabel(context.getPackageManager()).toString();
            result.parts.remove(base); result.parts.add(0, base);
            Files.deleteIfExists(archive.toPath());
            return result;
        } catch (Exception error) { result.close(); throw error; }
    }
    private record Config(String group, String type, String value) { }
    static boolean optional(Part part) {
        return config(part).type.equals("language");
    }
    static void retainSelection(Prepared prepared, Set<Part> selected) throws IOException {
        if (!prepared.parts.containsAll(selected)
                || prepared.parts.stream().anyMatch(part -> !optional(part) && !selected.contains(part)))
            throw new IOException("基础包和必需分包不能取消");
        prepared.parts.removeIf(part -> !selected.contains(part));
        prepared.bytes = prepared.parts.stream().mapToLong(part -> part.file.length()).sum();
    }
    private static Config config(Part part) {
        String split = part.manifest.split;
        int index = split.indexOf("config.");
        if (index < 0) return new Config(split, "required", "");
        String value = split.substring(index + 7), group = split.substring(0, index);
        String abi = value.replace('_', '-');
        if (value.equals("x86_64")) abi = "x86_64";
        if (Set.of("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64", "mips", "mips64", "riscv64").contains(abi)) return new Config(group, "abi", abi);
        if (density(value) > 0) return new Config(group, "density", value);
        if (value.matches("[a-z]{2,3}([_-]r?[A-Za-z]{2,4})?")) return new Config(group, "language", value.split("[_-]")[0]);
        return new Config(group, "required", value);
    }
    private static int density(String value) { return switch (value) {
        case "ldpi" -> 120; case "mdpi" -> 160; case "tvdpi" -> 213; case "hdpi" -> 240; case "xhdpi" -> 320; case "xxhdpi" -> 480; case "xxxhdpi" -> 640; case "nodpi" -> 65535; default -> 0;
    }; }
    private static void selectExported(Prepared result, ApksTargeting.Device device) throws IOException {
        Map<String, List<Part>> groups = new LinkedHashMap<>();
        for (Part part : result.parts) { Config config = config(part); if (!config.type.equals("required")) groups.computeIfAbsent(config.group + ":" + config.type, k -> new ArrayList<>()).add(part); }
        Set<Part> excluded = new HashSet<>();
        for (List<Part> candidates : groups.values()) {
            String type = config(candidates.get(0)).type;
            if (type.equals("abi")) {
                String selected = null;
                for (String abi : device.abis()) if (candidates.stream().anyMatch(p -> config(p).value.equals(abi))) { selected = abi; break; }
                if (selected == null) throw new IOException("拆分包缺少兼容此设备的 ABI：" + String.join(", ", device.abis()));
                for (Part part : candidates) if (!config(part).value.equals(selected)) excluded.add(part);
            } else if (type.equals("density")) {
                List<Integer> densities = new ArrayList<>(); for (Part part : candidates) densities.add(density(config(part).value));
                int selected = ApksTargeting.bestDensity(densities, device.density());
                for (Part part : candidates) if (density(config(part).value) != selected) excluded.add(part);
            } else if (candidates.size() > 1) {
                // An exported single-language package remains installable on a different locale.
                for (Part part : candidates) if (!device.languages().contains(ApksTargeting.language(config(part).value))) excluded.add(part);
            }
        }
        for (Part part : excluded) { result.parts.remove(part); result.bytes -= part.file.length(); result.excluded.add(part.name); Files.deleteIfExists(part.file.toPath()); }
    }
    static Manifest manifest(File apk) throws IOException {
        // ABI and install-time asset splits can have binary AndroidManifest.xml but no resources.arsc.
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null || entry.getSize() <= 0 || entry.getSize() > 16L * 1024 * 1024)
                throw new IOException("APK 清单缺失或大小无效");
            byte[] data;
            try (InputStream in = zip.getInputStream(entry)) {
                data = FileStore.readBytes(in, (int) entry.getSize() + 1);
            }
            if (data.length != entry.getSize()) throw new IOException("APK 清单长度无效");
            synchronized (ApkTools.class) {
                AndroidManifestBlock manifest;
                try { manifest = AndroidManifestBlock.load(new ByteArrayInputStream(data)); }
                catch (IOException malformed) { throw new IOException("APK 的 AndroidManifest.xml 无效", malformed); }
                var root = manifest.getManifestElement();
                String pkg = manifest.getPackageName();
                Integer version = manifest.getVersionCode();
                if (root == null || pkg == null || pkg.isBlank() || version == null)
                    throw new IOException("APK 清单缺少应用标识或版本号");
                var major = root.searchAttributeByResourceId(android.R.attr.versionCodeMajor);
                if (major != null && !major.getValueType().isInteger()) throw new IOException("APK 主版本号无效");
                String split = manifest.getSplit();
                return new Manifest(pkg, (major == null ? 0 : major.getData()) + ":" + version,
                        split == null ? "" : split);
            }
        } catch (RuntimeException malformed) {
            throw new IOException("APK 的 AndroidManifest.xml 无效", malformed);
        }
    }
    static int writeSession(Context context, Prepared prepared, AtomicBoolean cancelled) throws Exception {
        return writeSession(context,prepared.parts.stream().map(Part::file).toList(),prepared.packageName,prepared.bytes,cancelled);
    }
    static int writeSingleSession(Context context, File file, AtomicBoolean cancelled) throws Exception {
        if(!file.isFile() || file.length()==0)throw new IOException("安装缓存已失效，请重新打开 APK");
        return writeSession(context,List.of(file),null,file.length(),cancelled);
    }
    /** Commands used by the transactional Package Manager install flow. Keep these small and
     * deterministic so the root path works on ROMs which do not accept file paths in
     * {@code pm install-multiple}. APK bytes are supplied through stdin to avoid mount-namespace
     * visibility problems with the app's private cache directory. */
    static String installCreateCommand(long bytes, int userId) throws IOException {
        if (bytes <= 0 || userId < 0) throw new IOException("安装会话参数无效");
        return "/system/bin/pm install-create -r --user " + userId + " -S " + bytes;
    }

    static String installWriteCommand(int sessionId, String name, long bytes) throws IOException {
        if (sessionId < 0 || bytes <= 0 || name == null || !name.matches("[A-Za-z0-9_.-]{1,96}"))
            throw new IOException("安装分包参数无效");
        return "/system/bin/pm install-write -S " + bytes + " " + sessionId + " " + name + " -";
    }

    static String installCommitCommand(int sessionId) throws IOException {
        if (sessionId < 0) throw new IOException("安装会话参数无效");
        return "/system/bin/pm install-commit " + sessionId;
    }

    static String installAbandonCommand(int sessionId) throws IOException {
        if (sessionId < 0) throw new IOException("安装会话参数无效");
        return "/system/bin/pm install-abandon " + sessionId;
    }

    static int parseInstallSessionId(String output) throws IOException {
        if (output == null) throw new IOException("ROOT 静默安装未返回会话编号");
        java.util.regex.Matcher success = java.util.regex.Pattern
                .compile("(?m)^[ \\t]*Success[ \\t]*:[ \\t]*created install session[ \\t]*\\[([0-9]+)\\][ \\t]*$")
                .matcher(output);
        String value = success.find() ? success.group(1) : output.trim();
        if (value.matches("[0-9]+")) {
            try { return Integer.parseInt(value); }
            catch (NumberFormatException error) { throw new IOException("ROOT 静默安装返回无效会话编号", error); }
        }
        throw new IOException("ROOT 静默安装创建会话失败\n" + output);
    }
    static int currentUserId() { return android.os.Process.myUid() / 100_000; }
    static void installSilently(List<File> files, int userId, AtomicBoolean cancelled, ShellRunner runner) throws IOException {
        check(cancelled);
        if (!RootAccess.available()) throw new IOException("未获得 ROOT 授权，无法静默安装");
        if (files == null || files.isEmpty()) throw new IOException("安装分包为空");
        long total = 0;
        for (File file : files) {
            if (file == null || !file.isFile() || file.length() <= 0
                    || !file.getName().toLowerCase(Locale.ROOT).endsWith(".apk"))
                throw new IOException("安装分包缺失或无效，请重新打开 APKS");
            try { total = Math.addExact(total, file.length()); }
            catch (ArithmeticException overflow) { throw new IOException("安装包大小超出系统限制", overflow); }
        }
        int session = -1;
        try {
            long createTimeout = 120000, installTimeout = 300000;
            ShellRunner.Result created = runner.run(installCreateCommand(total, userId), null, true, createTimeout, null, false);
            requireSuccessfulExit(created, "ROOT 静默安装创建会话失败", createTimeout);
            session = parseInstallSessionId(created.output());
            for (int i = 0; i < files.size(); i++) {
                check(cancelled);
                File file = files.get(i);
                String name = files.size() == 1 ? "base.apk" : "part-" + i + ".apk";
                try (InputStream input = cancellationAware(new FileInputStream(file), cancelled)) {
                    ShellRunner.Result written = runner.run(installWriteCommand(session, name, file.length()),
                            null, true, installTimeout, null, false, input);
                    requireSuccess(written, "ROOT 静默安装写入分包失败", installTimeout, true);
                }
            }
            check(cancelled);
            ShellRunner.Result committed = runner.run(installCommitCommand(session), null, true, installTimeout, null, false);
            requireSuccess(committed, "ROOT 静默安装提交失败", installTimeout, false);
            session = -1;
        } catch (IOException | RuntimeException error) {
            if (session >= 0) abandonSilently(session);
            if (error instanceof IOException io) throw io;
            throw new IOException("ROOT 静默安装失败", error);
        }
    }

    private static void requireSuccessfulExit(ShellRunner.Result result, String prefix, long timeout) throws IOException {
        if (result.timedOut()) throw new IOException(prefix + "：超过 " + timeout / 1000 + " 秒，已停止");
        if (result.stopped()) throw new IOException(prefix + "：操作已停止");
        if (result.exitCode() != 0) throw installFailure(result, prefix);
    }

    private static void requireSuccess(ShellRunner.Result result, String prefix, long timeout, boolean writing) throws IOException {
        requireSuccessfulExit(result, prefix, timeout);
        if (!ApkInstall.hasShellSuccess(result.output(), writing)) throw installFailure(result, prefix);
    }

    private static IOException installFailure(ShellRunner.Result result, String prefix) {
        return new IOException(prefix + "\nexit " + result.exitCode() + "\n" + result.output().trim());
    }

    private static void abandonSilently(int session) {
        // A cancelled ShellRunner is sticky-stopped; use a fresh root shell for cleanup.
        try { new ShellRunner().run(installAbandonCommand(session), null, true, 30000, null, false); }
        catch (Exception ignored) { }
    }

    private static InputStream cancellationAware(InputStream input, AtomicBoolean cancelled) {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException { check(cancelled); return super.read(); }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                check(cancelled); return super.read(buffer, offset, length);
            }
            @Override public long skip(long count) throws IOException { check(cancelled); return super.skip(count); }
        };
    }
    private static int writeSession(Context context,List<File> files,String packageName,long bytes,AtomicBoolean cancelled) throws Exception {
        check(cancelled);
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if(packageName!=null)params.setAppPackageName(packageName);params.setSize(bytes);
        if (android.os.Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            for (int i = 0; i < files.size(); i++) {
                check(cancelled);
                File apk=files.get(i);String name=files.size()==1?"base.apk":"part-"+i+".apk";
                try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite(name, 0, apk.length())) {
                    copy(in, out, cancelled); session.fsync(out);
                }
            }
            check(cancelled);
            return id;
        } catch (Exception error) { installer.abandonSession(id); throw error; }
    }
    private static void copy(InputStream in, OutputStream out, AtomicBoolean cancelled) throws IOException {
        byte[] buffer = new byte[128 * 1024]; int count;
        while ((count = in.read(buffer)) != -1) { check(cancelled); out.write(buffer, 0, count); }
    }
    private static void check(AtomicBoolean cancelled) throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("已取消安装");
    }
    private SplitApks() { }
}
