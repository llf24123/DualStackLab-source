package org.example.filemanager;

import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ApkInstall {
    private static final long SHARE_LIFETIME = 24 * 60 * 60 * 1000L;
    private static final Set<File> ACTIVE_SHARES = ConcurrentHashMap.newKeySet();
    static boolean isApkName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apk") || lower.endsWith(".apk.1");
    }
    static File stage(Context context, FileStore.Node node) throws IOException {
        return stageShared(context, node, ".apk");
    }
    /** Only remove a file in our own staging session, never the original APK. */
    static void discardStage(Context context,File file){
        if(file==null)return;
        File session=file.getParentFile();
        if(session==null || !session.getName().startsWith("session-") || !new File(context.getCacheDir(),"shared").equals(session.getParentFile()))return;
        file.delete();session.delete(); // Interrupted work is swept by cleanExpiredShares.
    }
    static File stageShared(Context context, FileStore.Node node, String suffix) throws IOException {
        File folder = new File(context.getCacheDir(), "shared");
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) throw new IOException("无法创建安装缓存");
        cleanExpiredShares(folder, System.currentTimeMillis());
        String name = node.name;
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0)
            name = "shared-file" + (suffix != null && suffix.matches("\\.[A-Za-z0-9]{1,12}") ? suffix : ".bin");
        File session = Files.createTempDirectory(folder.toPath(), "session-").toFile();
        ACTIVE_SHARES.add(session);
        File target = new File(session, name);
        boolean complete = false;
        try {
            try (InputStream in = new FileStore(context).read(node); OutputStream out = new FileOutputStream(target)) {
                FileStore.pump(in, out);
            }
            session.setLastModified(System.currentTimeMillis());
            complete = true;
            return target;
        } finally {
            if (!complete) removeShare(session);
            ACTIVE_SHARES.remove(session);
        }
    }
    /** Only disposable share sessions live here. Never follow links while removing old sessions. */
    static void cleanExpiredShares(File folder, long now) {
        File[] old = folder.listFiles();
        if (old != null) for (File item : old)
            if (!ACTIVE_SHARES.contains(item) && now - item.lastModified() > SHARE_LIFETIME) removeShare(item);
    }
    private static void removeShare(File item) {
        try (var paths = Files.walk(item.toPath())) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException | UncheckedIOException | SecurityException ignored) {
            // Concurrent cleanup or an unreadable expired session must not break a new share.
        }
    }
    static String silentCommand(File file) {
        // su reads the private file; Package Manager receives bytes rather than a private path.
        return "/system/bin/pm install -r -S " + file.length() + " < " + FileStore.quote(file.getAbsolutePath());
    }
    static String silent(File file) throws IOException {
        ShellRunner.Result result = new ShellRunner().run(silentCommand(file), null, true, 120000, null, false);
        if (result.timedOut()) throw new IOException("安装超过 120 秒，已停止");
        if (result.exitCode() != 0 || !result.output().trim().matches("(?s)(?:.*\\n)?Success"))
            throw new IOException("静默安装失败（需 ROOT 授权）\nexit " + result.exitCode() + "\n" + result.output());
        return "安装成功";
    }
    private ApkInstall() {}
}
