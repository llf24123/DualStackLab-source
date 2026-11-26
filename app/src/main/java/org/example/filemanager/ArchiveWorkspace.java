package org.example.filemanager;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executor;

/** A private copy lives until its screen and all jobs submitted by that screen are finished. */
final class ArchiveWorkspace implements Executor, AutoCloseable {
    private static boolean swept;
    private File directory;
    private boolean closed;
    private int jobs;

    File create(File cache) throws IOException {
        synchronized (ArchiveWorkspace.class) {
            File base = new File(cache, "archive-workspaces");
            Files.createDirectories(base.toPath());
            if (!swept) {
                // ArchiveActivity runs in the app's main process. No session from this process
                // exists until the first workspace has been allocated under this lock.
                File[] stale = base.listFiles();
                if (stale != null) for (File folder : stale) remove(folder);
                File[] legacy = cache.listFiles((parent, name) -> name.matches("archive-[0-9]+"));
                if (legacy != null) for (File folder : legacy)
                    if (Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) remove(folder);
                swept = true;
            }
            File folder = Files.createTempDirectory(base.toPath(), "work-").toFile();
            synchronized (this) { directory = folder; }
            return folder;
        }
    }

    @Override public synchronized void execute(Runnable task) {
        if (closed) return;
        jobs++;
        Tasks.IO.execute(() -> {
            try { task.run(); }
            finally { synchronized (this) { jobs--; releaseIfIdle(); } }
        });
    }

    @Override public synchronized void close() { closed = true; releaseIfIdle(); }

    private void releaseIfIdle() {
        if (!closed || jobs != 0 || directory == null) return;
        File finished = directory;
        directory = null;
        Tasks.IO.execute(() -> remove(finished));
    }

    private static void remove(File folder) {
        try {
            Files.walkFileTree(folder.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    ArchiveOps.clearPassword(path.toFile());
                    ArchiveOps.forget(path.toFile());
                    Files.deleteIfExists(path);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.deleteIfExists(path);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) { Log.w("ArchiveWorkspace", "清理归档工作副本失败", error); }
    }
}
