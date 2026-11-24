package org.example.filemanager;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.enums.AesVersion;
import net.lingala.zip4j.progress.ProgressMonitor;

/** ZIP maintenance uses a verified private copy; original files are only changed by guarded publication. */
final class ArchiveTools implements Closeable {
    record Progress(String phase,String entry,int checked,int total,long bytes,long totalBytes) { }
    record Issue(String entry,String message) { }
    record Report(int passed,int failed,int checked,int total,long bytes,boolean stopped,List<Issue> issues) {
        String text(){
            StringBuilder text=new StringBuilder(stopped?"已停止校验":"校验完成");
            text.append("\n已检查 ").append(checked).append(" / ").append(total).append(" 项 · 通过 ").append(passed).append(" · 失败 ").append(failed)
                    .append("\n实际读取 ").append(FileStore.size(bytes));
            int shown=0;
            for(Issue issue:issues){if(text.length()+issue.entry().length()+issue.message().length()>120000)break;text.append("\n\n").append(issue.entry()).append("\n").append(issue.message());shown++;}
            if(failed>shown)text.append("\n\n另有 ").append(failed-shown).append(" 项错误未展开");
            return text.toString();
        }
    }
    final FileStore store;
    final FileStore.Node source;
    final File directory,archive;
    final boolean split,encrypted;
    final int entries,volumes;
    private String revision,comment;
    private boolean closed;
    ArchiveTools(Context context,FileStore.Node source,BooleanSupplier cancelled)throws IOException{
        this.source=source;store=new FileStore(context);FileStore.checkName(source.name);
        if(source.directory)throw new IOException("请选择 ZIP 文件");
        directory=Files.createTempDirectory(context.getCacheDir().toPath(),"zip-tools-").toFile();archive=new File(directory,source.name);
        try{
            revision=ArchiveCopies.copy(store,source,archive,cancelled);
            try(ZipFile zip=new ZipFile(archive)){
                entries=zip.getFileHeaders().size();split=zip.isSplitArchive();encrypted=zip.isEncrypted();
                volumes=split?zip.getSplitZipFiles().size():1;comment=Objects.requireNonNullElse(zip.getComment(),"");
            }
        }catch(IOException|RuntimeException e){close();throw e;}
    }
    synchronized String comment(){return comment;}
    private void ready()throws IOException{if(closed)throw new IOException("归档工具已关闭");}
    synchronized void saveComment(String value,BooleanSupplier cancelled)throws IOException{
        ready();check(cancelled);
        if(split)throw new IOException("请先合并分卷 ZIP，再修改注释");
        if(value.getBytes(StandardCharsets.UTF_8).length>65535)throw new IOException("ZIP 注释最多 65535 个 UTF-8 字节");
        File edited=File.createTempFile("comment-edit-",".zip",directory);
        try{
            try(InputStream in=new FileInputStream(archive);OutputStream out=new FileOutputStream(edited)){
                byte[] buffer=new byte[131072];int n;while((n=in.read(buffer))!=-1){check(cancelled);out.write(buffer,0,n);}
            }
            try(ZipFile zip=new ZipFile(edited)){zip.setComment(value);}
            check(cancelled);revision=store.write(source,edited,revision);
            Files.move(edited.toPath(),archive.toPath(),StandardCopyOption.REPLACE_EXISTING);
            ArchiveOps.forget(archive);comment=value;
        }finally{Files.deleteIfExists(edited.toPath());}
    }
    synchronized FileStore.Node merge(FileStore.Node destination,String name,BooleanSupplier cancelled,Consumer<Progress> updates)throws IOException{
        ready();check(cancelled);FileStore.checkName(name);
        if(!split)throw new IOException("当前 ZIP 不是分卷压缩包");
        if(destination==null||!destination.directory||destination.container!=null)throw new IOException("请选择普通目录保存合并文件");
        if(!name.toLowerCase(Locale.ROOT).endsWith(".zip"))throw new IOException("合并后的文件名需以 .zip 结尾");
        if(store.listNames(destination).contains(name))throw new IOException("同名文件已存在，请换一个名称");
        File merged=File.createTempFile("merged-",".zip",directory);Files.delete(merged.toPath());
        FileStore.CopyLedger ledger=store.new CopyLedger();boolean complete=false;
        try{
            try(ZipFile zip=new ZipFile(archive)){
                AtomicReference<Thread> worker=new AtomicReference<>();
                zip.setThreadFactory(r->{Thread t=new Thread(r,"zip-volume-merge");worker.set(t);return t;});
                zip.setRunInThread(true);zip.mergeSplitFiles(merged);ProgressMonitor monitor=zip.getProgressMonitor();
                boolean interrupted=false;Thread thread=worker.get();
                while(thread!=null&&thread.isAlive()){
                    if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()){monitor.setCancelAllTasks(true);interrupted|=Thread.interrupted();}
                    updates.accept(new Progress("合并分卷",source.name,0,1,monitor.getWorkCompleted(),monitor.getTotalWork()));
                    try{thread.join(50);}catch(InterruptedException e){interrupted=true;monitor.setCancelAllTasks(true);}
                }
                if(interrupted)Thread.currentThread().interrupt();check(cancelled);
                if(monitor.getResult()==ProgressMonitor.Result.CANCELLED)throw new InterruptedIOException("已停止");
                if(monitor.getException()!=null)throw new IOException("合并分卷失败："+monitor.getException().getMessage(),monitor.getException());
            }
            check(cancelled);updates.accept(new Progress("保存合并文件",name,0,1,0,merged.length()));
            FileStore.Node output=store.create(destination,name,false);ledger.record(output);
            if(!name.equals(output.name))throw new IOException("目标存储更改了文件名，请重试");
            check(cancelled);store.write(output,merged);ledger.record(output);complete=true;return output;
        }catch(IOException|RuntimeException e){if(!complete)Tasks.Progress.cleanup(()->ledger.rollback(e));throw e;}
        finally{Files.deleteIfExists(merged.toPath());}
    }
    synchronized Report verify(char[] password,BooleanSupplier cancelled,Consumer<Progress> updates)throws IOException{
        ready();int passed=0,failed=0,checked=0;long bytes=0;List<Issue> issues=new ArrayList<>();
        try(ZipFile zip=new ZipFile(archive,password)){
            List<FileHeader> headers=zip.getFileHeaders();long totalBytes=0;
            for(FileHeader h:headers){long length=Math.max(0,h.getUncompressedSize());totalBytes=length>Long.MAX_VALUE-totalBytes?Long.MAX_VALUE:totalBytes+length;}
            byte[] buffer=new byte[131072];long lastUpdate=0;
            for(FileHeader h:headers){
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())return new Report(passed,failed,checked,headers.size(),bytes,true,List.copyOf(issues));
                try{
                    CRC32 crc=new CRC32();long length=0;
                    if(!h.isDirectory())try(InputStream in=zip.getInputStream(h)){
                        for(int n;(n=in.read(buffer))!=-1;){
                            check(cancelled);crc.update(buffer,0,n);length+=n;bytes+=n;
                            long now=System.nanoTime();if(now-lastUpdate>100_000_000L){updates.accept(new Progress("校验内容",h.getFileName(),checked,headers.size(),bytes,totalBytes));lastUpdate=now;}
                        }
                    }
                    check(cancelled);
                    if(length!=h.getUncompressedSize())throw new IOException("长度不一致：预期 "+h.getUncompressedSize()+"，实际 "+length);
                    boolean authenticatedAes=h.getAesExtraDataRecord()!=null&&h.getAesExtraDataRecord().getAesVersion()==AesVersion.TWO;
                    if(!authenticatedAes&&crc.getValue()!=h.getCrc())throw new IOException("CRC32 不匹配");
                    passed++;
                }catch(InterruptedIOException e){return new Report(passed,failed,checked,headers.size(),bytes,true,List.copyOf(issues));}
                catch(IOException|RuntimeException error){failed++;if(issues.size()<1000)issues.add(new Issue(h.getFileName(),Objects.requireNonNullElse(error.getMessage(),error.getClass().getSimpleName())));}
                checked++;updates.accept(new Progress("校验内容",h.getFileName(),checked,headers.size(),bytes,totalBytes));
            }
            return new Report(passed,failed,checked,headers.size(),bytes,false,List.copyOf(issues));
        }
    }
    private static void check(BooleanSupplier cancelled)throws InterruptedIOException{
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止");
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;ArchiveOps.clearPassword(archive);ArchiveOps.forget(archive);
        try(var paths=Files.walk(directory.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        catch(IOException|RuntimeException e){android.util.Log.w("ArchiveTools","归档临时目录清理失败",e);}
    }
}
