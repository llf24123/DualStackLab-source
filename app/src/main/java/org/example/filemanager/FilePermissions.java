package org.example.filemanager;

import android.app.*;
import android.system.*;
import android.text.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;

final class FilePermissions {
    private static final int PATH_ONLY = 010000000; // Linux O_PATH pins an inode without opening a device or requiring read access.
    record State(int mode, long uid, long gid, boolean root, boolean link) { }
    static int parse(String value) throws IOException {
        if (!value.matches("[0-7]{3,4}")) throw new IOException("请输入 3 或 4 位八进制权限，仅允许 0–7，例如 600、755");
        return Integer.parseInt(value, 8);
    }
    static String octal(int mode) { return String.format(Locale.ROOT, (mode & 07000) == 0 ? "%03o" : "%04o", mode); }
    static String symbolic(int mode) {
        char[] result = "---------".toCharArray();
        for (int i = 0; i < 9; i++) if ((mode & (1 << (8 - i))) != 0) result[i] = "rwx".charAt(i % 3);
        if ((mode & 04000) != 0) result[2] = (mode & 0100) != 0 ? 's' : 'S';
        if ((mode & 02000) != 0) result[5] = (mode & 0010) != 0 ? 's' : 'S';
        if ((mode & 01000) != 0) result[8] = (mode & 0001) != 0 ? 't' : 'T';
        return new String(result);
    }
    static State read(FileStore.Node requested) throws IOException {
        FileStore.Node node = RootAccess.automatic(requested);
        if (node.file == null) throw new IOException("此来源没有可修改的 POSIX 文件权限");
        int full; long uid, gid;
        if (node.root) {
            String[] fields = RootDirectoryReader.stat(node.path(), "%f|%u|%g", false).split("\\|");
            try {
                full = Integer.parseInt(fields[0], 16); uid = Long.parseLong(fields[1]); gid = Long.parseLong(fields[2]);
            } catch (IndexOutOfBoundsException | NumberFormatException error) { throw new IOException("无法解析文件权限", error); }
        } else try {
            StructStat stat = Os.lstat(node.path()); full = stat.st_mode; uid = stat.st_uid; gid = stat.st_gid;
        } catch (ErrnoException error) { throw new IOException("读取权限失败：" + error.getMessage(), error); }
        return new State(full & 07777, uid, gid, node.root, OsConstants.S_ISLNK(full));
    }
    static State apply(android.content.Context context, FileStore.Node requested, int mode) throws IOException {
        if (mode < 0 || mode > 07777) throw new IOException("权限值超出 0000–7777");
        Tasks.Progress.check();
        FileStore.Node node = RootAccess.automatic(requested);
        State previous = read(node);
        if (previous.link()) throw new IOException("符号链接不支持直接修改权限，请打开目标文件属性");
        if (node.root) return RootMutations.chmod(context, node, mode);
        try {
            java.io.FileDescriptor raw = Os.open(node.file.getParent(), PATH_ONLY | OsConstants.O_NONBLOCK, 0);
            android.os.ParcelFileDescriptor parent;
            try { parent = android.os.ParcelFileDescriptor.dup(raw); } finally { Os.close(raw); }
            try (android.os.ParcelFileDescriptor directory = parent) {
                StructStat parentStat = Os.fstat(directory.getFileDescriptor());
                if (!OsConstants.S_ISDIR(parentStat.st_mode) || node.parentIdentity != null
                        && !node.parentIdentity.equals(FileStore.fileIdentity(parentStat))) throw new IOException("父目录已变化，请刷新");
                String path = "/proc/self/fd/" + directory.getFd() + "/" + node.file.getName();
                raw = Os.open(path, PATH_ONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
                android.os.ParcelFileDescriptor pinned;
                try { pinned = android.os.ParcelFileDescriptor.dup(raw); } finally { Os.close(raw); }
                try (android.os.ParcelFileDescriptor leaf = pinned) {
                    StructStat selected = Os.fstat(leaf.getFileDescriptor());
                    if ((!OsConstants.S_ISREG(selected.st_mode) && !OsConstants.S_ISDIR(selected.st_mode))
                            || node.identity != null && !node.identity.equals(FileStore.fileIdentity(selected)))
                        throw new IOException("目标已变化或不是普通文件/目录，请刷新");
                    Tasks.Progress.step(node.name);
                    Os.chmod("/proc/self/fd/" + leaf.getFd(), mode);
                    StructStat actual = Os.fstat(leaf.getFileDescriptor());
                    if (!FileStore.fileIdentity(selected).equals(FileStore.fileIdentity(Os.lstat(path))))
                        throw new IOException("权限操作期间目录项已变化，请刷新核对");
                    if ((actual.st_mode & 07777) != mode) throw new IOException("文件系统没有应用此权限");
                    return new State(actual.st_mode & 07777, actual.st_uid, actual.st_gid, false, false);
                }
            }
        } catch (ErrnoException error) { throw new IOException("修改权限失败：" + error.getMessage(), error); }
    }
    static AlertDialog show(Activity activity, FileStore.Node node, String modified, Runnable changed) {
        return FileProperties.show(activity, java.util.List.of(node), changed);
    }
    private FilePermissions() { }
}
