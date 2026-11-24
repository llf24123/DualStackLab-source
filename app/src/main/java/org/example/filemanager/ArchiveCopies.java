package org.example.filemanager;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** A private archive copy includes all ZIP volumes, with each source checked after copying. */
final class ArchiveCopies {
    static String copy(FileStore store, FileStore.Node source, File destination, BooleanSupplier cancelled) throws IOException {
        Map<FileStore.Node,String> revisions=new LinkedHashMap<>();
        revisions.put(source,store.fingerprint(source));
        transfer(store,source,destination,cancelled);
        if(source.file!=null)ArchiveOps.copyPassword(source.file,destination);
        if(ArchiveOps.isSplitZip(destination)) {
            FileStore.Node parent=store.parent(source);
            if(parent==null)throw new IOException("无法定位 ZIP 分卷所在目录");
            Map<String,FileStore.Node> siblings=new HashMap<>();
            for(FileStore.Node sibling:store.list(parent))siblings.put(sibling.name,sibling);
            List<File> parts;
            try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(destination)){parts=zip.getSplitZipFiles();}
            for(File part:parts)if(!part.equals(destination)) {
                TextDiff.check(cancelled);FileStore.Node sibling=siblings.get(part.getName());
                if(sibling==null||sibling.directory)throw new IOException("缺少 ZIP 分卷："+part.getName());
                revisions.put(sibling,store.fingerprint(sibling));
            }
            for(File part:parts)if(!part.equals(destination))transfer(store,siblings.get(part.getName()),part,cancelled);
        }
        for(var entry:revisions.entrySet()) {
            TextDiff.check(cancelled);
            if(!entry.getValue().equals(store.fingerprint(entry.getKey())))throw new IOException("读取期间归档或分卷已变化，请刷新后重试："+entry.getKey().name);
        }
        return revisions.get(source);
    }
    private static void transfer(FileStore store,FileStore.Node source,File destination,BooleanSupplier cancelled)throws IOException {
        try(InputStream in=store.read(source);OutputStream out=new FileOutputStream(destination)) {
            byte[] buffer=new byte[131072];int count;
            while((count=in.read(buffer))!=-1){TextDiff.check(cancelled);Tasks.Progress.check();out.write(buffer,0,count);}
        }
    }
    private ArchiveCopies(){}
}
