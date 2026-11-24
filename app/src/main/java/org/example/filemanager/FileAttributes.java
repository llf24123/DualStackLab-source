package org.example.filemanager;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Explicit metadata edits; independent from optional copy-time metadata preservation. */
final class FileAttributes {
    record State(int mode, long uid, long gid, long modified, long accessed, long changed, boolean root, boolean link) { }
    record Snapshot(FileStore.Node node, String identity, int mode) {
        boolean directory() { return OsConstants.S_ISDIR(mode); }
        boolean link() { return OsConstants.S_ISLNK(mode); }
        boolean regular() { return OsConstants.S_ISREG(mode); }
    }

    static State read(FileStore.Node requested) throws IOException {
        requireFilesystem(requested);
        Tasks.Progress.check();
        FileStore.Node node = RootAccess.automatic(requested);
        if (node.root) {
            String[] fields = RootDirectoryReader.stat(node.path(), "%f|%u|%g|%Y|%X|%Z", false).split("\\|");
            try {
                int mode = Integer.parseInt(fields[0], 16);
                return new State(mode & 07777, Long.parseLong(fields[1]), Long.parseLong(fields[2]),
                        Math.multiplyExact(Long.parseLong(fields[3]), 1000L), Math.multiplyExact(Long.parseLong(fields[4]), 1000L),
                        Math.multiplyExact(Long.parseLong(fields[5]), 1000L), true, OsConstants.S_ISLNK(mode));
            } catch (IndexOutOfBoundsException | NumberFormatException | ArithmeticException error) {
                throw new IOException("无法解析文件属性", error);
            }
        }
        try {
            StructStat stat = Os.lstat(node.path());
            return new State(stat.st_mode & 07777, stat.st_uid, stat.st_gid, stat.st_mtime * 1000L,
                    stat.st_atime * 1000L, stat.st_ctime * 1000L, false, OsConstants.S_ISLNK(stat.st_mode));
        } catch (ErrnoException error) { throw new IOException("读取文件属性失败：" + error.getMessage(), error); }
    }

    static void owner(Context context, FileStore.Node requested, Long uid, Long gid) throws IOException {
        requireFilesystem(requested); RootFileOps.validateOwner(uid, gid); Tasks.Progress.check();
        FileStore.Node node = RootAccess.automatic(requested);
        if (node.root) RootMutations.owner(context, node, uid, gid);
        else RootFileOps.attributesLocal(RootFileOps.Request.owner(spec(node), uid, gid));
    }

    static void times(Context context, FileStore.Node requested, Long modified, Long accessed) throws IOException {
        requireFilesystem(requested); RootFileOps.validateTimes(modified, accessed); Tasks.Progress.check();
        FileStore.Node node = RootAccess.automatic(requested);
        if (node.root) RootMutations.times(context, node, modified, accessed);
        else RootFileOps.attributesLocal(RootFileOps.Request.times(spec(node), modified, accessed));
    }

    static void requireFilesystem(FileStore.Node node) throws IOException {
        if (node == null) throw new IOException("没有选择文件");
        if (node.container != null) throw new IOException("归档成员暂不支持直接修改权限、所有者或时间，请先解压");
        if (node.uri != null || node.file == null) throw new IOException("系统目录授权（SAF）未提供可靠的权限、所有者和时间修改接口");
    }

    /** NOFOLLOW snapshot used by recursive edits; preserve the observed guards for every mutation. */
    static Snapshot inspect(FileStore.Node requested) throws IOException {
        requireFilesystem(requested); Tasks.Progress.check();
        FileStore.Node node = RootAccess.automatic(requested);
        int mode; String identity, parentIdentity; long size, modified;
        if (node.root) {
            String[] fields = RootDirectoryReader.stat(node.path(), "%f|%s|%Y|%d:%i", false).split("\\|");
            try {
                mode = Integer.parseInt(fields[0], 16); size = Long.parseLong(fields[1]);
                modified = Math.multiplyExact(Long.parseLong(fields[2]), 1000L); identity = fields[3];
            } catch (IndexOutOfBoundsException | NumberFormatException | ArithmeticException error) { throw new IOException("无法解析文件身份", error); }
            parentIdentity = RootDirectoryReader.stat(node.file.getAbsoluteFile().getParent(), "%d:%i", true);
        } else try {
            StructStat stat = Os.lstat(node.path()); mode = stat.st_mode; identity = FileStore.fileIdentity(stat);
            size = stat.st_size; modified = stat.st_mtime * 1000L;
            parentIdentity = FileStore.fileIdentity(Os.stat(node.file.getAbsoluteFile().getParent()));
        } catch (ErrnoException error) { throw new IOException("读取文件身份失败：" + error.getMessage(), error); }
        if (node.parentIdentity != null && !node.parentIdentity.equals(parentIdentity)) throw new IOException("父目录已变化，请刷新后重试");
        if ((!OsConstants.S_ISLNK(mode) || !node.identityFollowsLinks) && node.identity != null && !node.identity.equals(identity))
            throw new IOException("所选文件已变化，请刷新后重试");
        if (node.directoryIdentity != null && !OsConstants.S_ISLNK(mode) && !node.directoryIdentity.equals(identity))
            throw new IOException("所选目录已变化，请刷新后重试");
        FileStore.Node fixed = new FileStore.Node(node.name, node.file, null, OsConstants.S_ISDIR(mode), node.root, size, modified);
        fixed.identity = identity; fixed.parentIdentity = parentIdentity; fixed.identityFollowsLinks = false; fixed.unixMode = mode;
        if (fixed.directory) fixed.directoryIdentity = identity;
        return new Snapshot(fixed, identity, mode);
    }

    static List<FileStore.Node> children(Snapshot directory) throws IOException {
        Tasks.Progress.check();
        if (!directory.directory() || directory.link()) throw new IOException("不能进入非目录或符号链接");
        Snapshot before = inspect(directory.node);
        if (!before.directory() || !directory.identity.equals(before.identity)) throw new IOException("递归目录已变化，请刷新后重试");
        List<FileStore.Node> children = new ArrayList<>();
        if (directory.node.root) {
            String output = RootDirectoryReader.list(directory.node.path(), false);
            for (String line : output.split("\n")) if (!line.isEmpty() && line.split("\\|", 6).length != 6)
                throw new IOException("目录包含无法完整解析的条目，未继续递归");
            for (FileStore.Node child : FileStore.parseRootListing(output)) {
                Tasks.Progress.check();
                if (!child.file.getAbsoluteFile().getParentFile().toPath().normalize().equals(directory.node.file.getAbsoluteFile().toPath().normalize()))
                    throw new IOException("目录条目超出所选目录");
                child.parentIdentity = directory.identity;
                children.add(child);
                if (children.size() > 100000) throw new IOException("单个目录超过 100000 项，未继续递归");
            }
        } else {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory.node.file.toPath())) {
                for (Path path : stream) {
                    Tasks.Progress.check();
                    FileStore.Node child = new FileStore.Node(path.getFileName().toString(), path.toFile(), null, false, false, -1, 0);
                    child.parentIdentity = directory.identity;
                    children.add(child);
                    if (children.size() > 100000) throw new IOException("单个目录超过 100000 项，未继续递归");
                }
            }
        }
        Snapshot after = inspect(directory.node);
        if (!after.directory() || !directory.identity.equals(after.identity)) throw new IOException("遍历期间目录已变化，未使用此次条目");
        return children;
    }

    private static RootFileOps.Spec spec(FileStore.Node node) {
        return new RootFileOps.Spec(node.path(), node.identity, node.parentIdentity, node.directoryIdentity, node.identityFollowsLinks);
    }
    private FileAttributes() { }
}
