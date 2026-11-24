package org.example.filemanager;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Builds privately, checks all destination names, then publishes the final ZIP volume last. */
final class ArchiveCreation {
    static FileStore.Node create(Context context,FileStore store,List<FileStore.Node> sources,FileStore.Node destination,
            String name,String format,char[] password,int level,long splitSize,boolean removeSources)throws IOException {
        FileStore.checkName(name);if(sources.isEmpty())throw new IOException("没有选中文件");
        if (splitSize > 0 && ((!format.equals("zip") && !format.equals("7z")) || destination.container != null))
            throw new IOException("ZIP / 7Z 分卷请保存到普通目录");
        if(!name.toLowerCase(Locale.ROOT).endsWith("."+format))throw new IOException("文件名需以 ."+format+" 结尾");
        for(FileStore.Node source:sources)store.checkCopy(source,destination);
        Map<FileStore.Node,FileStore.OutputVersion> versions=new LinkedHashMap<>();
        if(removeSources){Tasks.Progress.phase("核对来源");for(var source:sources)versions.put(source,store.outputVersion(source));}
        File folder=Files.createTempDirectory(context.getCacheDir().toPath(),"archive-create-").toFile();
        File archive=new File(folder,name);
        FileStore.CopyLedger ledger=store.new CopyLedger();boolean complete=false;
        try{
            Tasks.Progress.phase("压缩文件");
            List<File> parts;
            if(splitSize>0 && format.equals("zip"))parts=ArchiveOps.packSplit(store,sources,archive,password,level,splitSize);
            else{ArchiveOps.pack(store,sources,archive,format,password,level);parts=List.of(archive);}
            if(removeSources){
                Tasks.Progress.phase("校验归档内容");
                for(var source:sources){
                    String entry=ArchiveFormats.single(archive)?ArchiveOps.list(archive,"").get(0).path():source.name+(source.directory?"/":"");
                    FileStore.Node packed=FileStore.archiveNode(FileStore.local(archive),entry,source.directory,source.size,source.modified);
                    if(!versions.get(source).content().equals(store.contentSignature(packed,new HashSet<>())))throw new IOException("归档内容校验不一致，来源保留："+source.name);
                }
            }
            File main = archive;
            if (splitSize > 0 && format.equals("7z")) {
                parts = splitSevenZ(archive, splitSize);
                main = parts.get(0);
            }
            Set<String> existing=store.listNames(destination);
            if (format.equals("7z") && splitSize > 0) {
                String prefix = name + ".";
                for (String existingName : existing)
                    if (existingName.startsWith(prefix) && existingName.substring(prefix.length()).matches("\\d{3,5}"))
                        throw new IOException("同名 7Z 分卷已存在：" + existingName);
            }
            for(File part:parts)if(existing.contains(part.getName()))throw new IOException("同名文件或分卷已存在："+part.getName());
            File mainPart = main;
            List<File> ordered=new ArrayList<>(parts);ordered.sort(Comparator.comparing((File f)->f.equals(mainPart)).thenComparing(File::getName));
            Map<FileStore.Node,String> outputVersions=new LinkedHashMap<>();FileStore.Node result=null;
            Tasks.Progress.phase("保存压缩文件");
            for(File part:ordered){Tasks.Progress.check();FileStore.Node out=store.create(destination,part.getName(),false);ledger.record(out);
                if(!part.getName().equals(out.name))throw new IOException("目标提供方更改了文件名，已取消保存："+out.name);
                store.write(out,part);ledger.record(out);
                if(removeSources){String digest=FileIntegrity.calculate(store,out).sha256();String expected=FileIntegrity.calculate(new FileInputStream(part),new java.util.concurrent.atomic.AtomicBoolean()).sha256();
                    if(!digest.equals(expected))throw new IOException("写入后校验失败，来源保留："+out.name);outputVersions.put(out,store.fingerprint(out));}
                if(part.equals(mainPart))result=out;
            }
            if(result==null)throw new IOException("缺少归档主文件");
            if(password.length>0&&result.file!=null)ArchiveOps.setPassword(result.file,password);
            Tasks.Progress.check();complete=true;
            if(removeSources){
                try{
                    for(var source:sources)if(!versions.get(source).equals(store.outputVersion(source)))throw new IOException("来源发生变化："+source.name);
                    for(var out:outputVersions.entrySet())store.checkRevision(out.getKey(),out.getValue());
                    Tasks.Progress.phase("删除已校验来源");
                    for(var source:sources){Tasks.Progress.check();if(!versions.get(source).equals(store.outputVersion(source)))throw new IOException("来源发生变化："+source.name);store.delete(source);}
                }catch(IOException error){throw new IOException("压缩包已保存；部分或全部来源未删除："+error.getMessage(),error);}
            }
            return result;
        }catch(IOException|RuntimeException error){if(!complete)Tasks.Progress.cleanup(()->ledger.rollback(error));throw error;}
        finally{
            ArchiveOps.clearPassword(archive);ArchiveOps.forget(archive);
            try(var paths=Files.walk(folder.toPath())){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
            catch(IOException|RuntimeException cleanup){android.util.Log.w("ArchiveCreation","清理压缩临时文件失败",cleanup);}
        }
    }
    static List<File> splitSevenZ(File archive, long partSize) throws IOException {
        if (partSize <= 0 || (archive.length() - 1) / partSize >= 1024) throw new IOException("分卷大小过小或超过 1024 卷");
        List<File> volumes = new ArrayList<>();
        byte[] buffer = new byte[128 * 1024];
        long remaining = archive.length();
        try (InputStream input = new BufferedInputStream(new FileInputStream(archive))) {
            for (int index = 1; remaining > 0; index++) {
                File volume = new File(archive.getParentFile(), archive.getName() + String.format(Locale.ROOT, ".%03d", index));
                volumes.add(volume);
                long length = Math.min(partSize, remaining);
                try (OutputStream output = Files.newOutputStream(volume.toPath(), StandardOpenOption.CREATE_NEW)) {
                    for (long left = length; left > 0;) {
                        Tasks.Progress.check();
                        int read = input.read(buffer, 0, (int) Math.min(left, buffer.length));
                        if (read < 0) throw new EOFException("7Z 分卷内容不完整");
                        output.write(buffer, 0, read);
                        left -= read;
                    }
                }
                remaining -= length;
            }
        }
        if (volumes.isEmpty()) throw new IOException("7Z 归档为空");
        return volumes;
    }
    private ArchiveCreation(){}
}
