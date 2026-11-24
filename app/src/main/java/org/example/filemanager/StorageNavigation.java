package org.example.filemanager;

import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NotDirectoryException;
import java.nio.file.attribute.BasicFileAttributes;

final class StorageNavigation {
    /** Resolves typed paths before changing the pane, falling back through missing parents. */
    static FileStore.Node jump(File input) throws IOException {
        File path = input.toPath().toAbsolutePath().normalize().toFile();
        if (RootAccess.available())
            return location(FileStore.directory(new File(RootDirectoryReader.nearestDirectory(path.getPath())), true));
        while (path != null) {
            try {
                if (Files.readAttributes(path.toPath(), BasicFileAttributes.class).isDirectory())
                    return location(FileStore.directory(path, false));
            } catch (NoSuchFileException | NotDirectoryException missing) {
                // Permission failures are surfaced instead of changing to an unrelated directory.
            } catch (AccessDeniedException denied) {
                if (AndroidDataAccess.path(path) != null) return FileStore.directory(path, false);
                throw denied;
            }
            path = path.getParentFile();
        }
        throw new IOException("找不到可进入的上级目录");
    }

    static FileStore.Node location(FileStore.Node node) {
        if (node == null || node.file == null) return node;
        String path = normalized(node.file);
        if (storageHome(path))
            return FileStore.directory(Environment.getExternalStorageDirectory(), node.root);
        return node;
    }

    static FileStore.Node parent(FileStore store, FileStore.Node node) {
        return location(store.parent(location(node)));
    }

    /** Paths whose metadata the media provider owns; writes must run with the app's own identity there. */
    static boolean ordinaryStorage(File path) {
        String value = normalized(path);
        return value.startsWith("/storage/")
                || value.startsWith("/mnt/user/") || value.equals("/sdcard") || value.startsWith("/sdcard/");
    }

    /** The app owns normal shared-storage writes; virtual ownership is supplied by the mounted filesystem. */
    static boolean appManagedStorage(File path) {
        if (!ordinaryStorage(path) || otherUserStorage(path) || !path.canRead() || !path.canWrite()) return false;
        try {
            File canonical = path.getCanonicalFile();
            if (otherUserStorage(canonical)) return false;
            // Android's Java provider does not implement Files.getFileStore. Ask the storage
            // service about the resolved volume instead of inferring ownership from a prefix.
            return Environment.isExternalStorageEmulated(canonical) || Environment.isExternalStorageRemovable(canonical);
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | SecurityException error) {
            return false;
        }
    }

    /** All-files access belongs to one Android user, not every emulated-storage mount. */
    static boolean otherUserStorage(File path) {
        return otherUserStorage(path, android.os.Process.myUid() / 100000);
    }
    static boolean otherUserStorage(File path, int currentUser) {
        String value = normalized(path);
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "^/(?:storage/emulated|mnt/user|mnt/runtime/(?:default|read|write|full)/emulated)/(\\d+)(?:/|$)").matcher(value);
        if (!match.find()) return false;
        try { return Long.parseLong(match.group(1)) != currentUser; }
        catch (NumberFormatException invalid) { return true; }
    }

    static boolean storageContainer(File path) {
        String value = normalized(path);
        return value.equals("/storage") || value.equals("/storage/emulated");
    }

    static FileStore.Node knownStorageChild(File path) {
        return FileStore.directory(normalized(path).equals("/storage")
                ? new File("/storage/emulated") : Environment.getExternalStorageDirectory(), false);
    }

    private static boolean storageHome(String path) {
        return path.equals(normalized(Environment.getExternalStorageDirectory()))
                || path.equals("/sdcard") || path.equals("/storage/self/primary");
    }

    private static String normalized(File file) {
        return file.toPath().toAbsolutePath().normalize().toString();
    }

    private StorageNavigation() {}
}
