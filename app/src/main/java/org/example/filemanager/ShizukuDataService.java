package org.example.filemanager;

import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Runs as the Shizuku shell UID. Its file scope is Android/data on emulated storage. */
public final class ShizukuDataService extends IDataAccess.Stub {
    public ShizukuDataService() { }

    static File checked(String path) throws IOException {
        File file = new File(path).getCanonicalFile();
        if (!file.getPath().equals(path)) throw new IOException("请使用规范的目录路径");
        if (!file.getPath().matches("/storage/emulated/[0-9]+/Android/data(?:/.*)?"))
            throw new IOException("访问路径超出 Android/data");
        if (Files.isSymbolicLink(new File(path).toPath())) throw new IOException("请打开真实文件路径");
        return file;
    }
    private static File mutable(String path) throws IOException {
        File file = checked(path);
        if (file.getPath().matches("/storage/emulated/[0-9]+/Android/data")) throw new IOException("不能修改 Android/data 根目录");
        return file;
    }
    private static Bundle metadata(File file) throws IOException {
        BasicFileAttributes info = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!info.isDirectory() && !info.isRegularFile()) throw new IOException("不支持此文件类型");
        Bundle result = new Bundle();
        result.putString("path", file.getPath()); result.putString("name", file.getName());
        result.putBoolean("directory", info.isDirectory()); result.putBoolean("writable", file.canWrite());
        result.putLong("size", info.size()); result.putLong("modified", info.lastModifiedTime().toMillis());
        return result;
    }
    private static IllegalStateException failure(Exception error) { return new IllegalStateException(error.getMessage(), error); }
    @Override public Bundle stat(String path) throws RemoteException {
        try { return metadata(checked(path)); }
        catch (Exception error) { throw failure(error); }
    }
    @Override public IDataListing list(String path) throws RemoteException {
        try {
            File[] files = checked(path).listFiles();
            if (files == null) throw new IOException("Shizuku 无法读取此目录，请检查系统权限");
            Arrays.sort(files, Comparator.comparing(File::getName));
            return new IDataListing.Stub() {
                @Override public Bundle page(int offset) { return listingPage(files, offset); }
            };
        } catch (Exception error) { throw failure(error); }
    }
    private static Bundle listingPage(File[] files, int offset) {
        try {
            if (offset < 0 || offset > files.length) throw new IOException("目录分页参数无效");
            ArrayList<Bundle> entries = new ArrayList<>();
            // Binder's shared transaction buffer is bounded by bytes, not the number of files.
            // Deep Android/data paths can make even 128 metadata records exceed that buffer.
            int end = offset, bytes = 1024;
            while (end < files.length && end < offset + 128) {
                File file = files[end];
                if (Files.isSymbolicLink(file.toPath())) { end++; continue; }
                Bundle info;
                try { info = metadata(checked(file.getPath())); }
                catch (NoSuchFileException removed) { end++; continue; }
                Parcel encoded = Parcel.obtain();
                int size;
                try { encoded.writeBundle(info); size = encoded.dataSize(); }
                finally { encoded.recycle(); }
                if (!entries.isEmpty() && bytes + size > 192 * 1024) break;
                entries.add(info); bytes += size; end++;
            }
            Bundle page = new Bundle(); page.putParcelableArrayList("entries", entries);
            page.putInt("next", end < files.length ? end : -1); return page;
        } catch (Exception error) { throw failure(error); }
    }
    @Override public ParcelFileDescriptor open(String path, String mode) throws RemoteException {
        java.io.FileDescriptor descriptor = null;
        try {
            File file = checked(path);
            StructStat expected = Os.lstat(file.getPath());
            if (!OsConstants.S_ISREG(expected.st_mode)) throw new IOException("打开目标不是普通文件");
            int flags = switch (mode) {
                case "r" -> OsConstants.O_RDONLY;
                case "w", "wt" -> OsConstants.O_WRONLY;
                case "wa" -> OsConstants.O_WRONLY | OsConstants.O_APPEND;
                case "rw", "rwt" -> OsConstants.O_RDWR;
                default -> throw new IOException("不支持的文件打开模式");
            };
            descriptor = Os.open(file.getPath(), flags | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
            StructStat actual = Os.fstat(descriptor);
            if (actual.st_dev != expected.st_dev || actual.st_ino != expected.st_ino || !OsConstants.S_ISREG(actual.st_mode))
                throw new IOException("打开期间文件已变化");
            if (mode.equals("w") || mode.equals("wt") || mode.equals("rwt")) Os.ftruncate(descriptor, 0);
            return ParcelFileDescriptor.dup(descriptor);
        } catch (Exception error) { throw failure(error); }
        finally { if (descriptor != null) try { Os.close(descriptor); } catch (Exception ignored) { } }
    }
    @Override public String create(String parent, String name, boolean directory) throws RemoteException {
        try {
            FileStore.checkName(name);
            File target = checked(new File(checked(parent), name).getPath());
            if (!(directory ? target.mkdir() : target.createNewFile())) throw new IOException("同名文件已存在或目录不可写");
            return target.getPath();
        } catch (Exception error) { throw failure(error); }
    }
    @Override public String rename(String path, String name) throws RemoteException {
        try {
            FileStore.checkName(name); File source = mutable(path);
            File target = checked(new File(source.getParentFile(), name).getPath());
            Files.move(source.toPath(), target.toPath()); return target.getPath();
        } catch (Exception error) { throw failure(error); }
    }
    @Override public void delete(String path) throws RemoteException {
        try {
            Files.walkFileTree(mutable(path).toPath(), new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(directory); return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception error) { throw failure(error); }
    }
    @Override public void destroy() { System.exit(0); }
}
