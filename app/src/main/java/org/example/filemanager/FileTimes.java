package org.example.filemanager;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;

/** Optional metadata preservation. Timestamp failures must not discard successfully copied bytes. */
final class FileTimes {
    private static final ThreadLocal<Boolean> setting=new ThreadLocal<>();
    static final class Scope implements AutoCloseable {
        private final Boolean previous=setting.get();
        Scope(Context context){setting.set(FileManagerSettings.prefs(context).getBoolean("preserve-time",true));}
        public void close(){if(previous==null)setting.remove();else setting.set(previous);}
    }
    static void extracted(File file,long modified){if(Boolean.FALSE.equals(setting.get())||modified<=0)return;try{Files.setLastModifiedTime(file.toPath(),FileTime.fromMillis(modified));}catch(IOException|RuntimeException ignored){}}
    static void copied(Context context,FileStore.Node source,FileStore.Node target){
        if(!FileManagerSettings.prefs(context).getBoolean("preserve-time",true)||source.modified<=0||target.container!=null)return;
        try{
            if(target.file!=null){
                java.io.FileDescriptor raw=Os.open(target.path(),RootFileOps.O_PATH|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);
                try(ParcelFileDescriptor fd=ParcelFileDescriptor.dup(raw)){
                    StructStat stat=Os.fstat(fd.getFileDescriptor());if(OsConstants.S_ISLNK(stat.st_mode)||target.identity!=null&&!target.identity.equals(FileStore.fileIdentity(stat)))return;
                    Files.setLastModifiedTime(Paths.get("/proc/self/fd/"+fd.getFd()),FileTime.fromMillis(source.modified));
                }finally{Os.close(raw);}
            }else if(target.uri!=null&&!target.directory){
                try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(target.uri,"rw")){if(fd!=null&&OsConstants.S_ISREG(Os.fstat(fd.getFileDescriptor()).st_mode))Files.setLastModifiedTime(Paths.get("/proc/self/fd/"+fd.getFd()),FileTime.fromMillis(source.modified));}
            }
        }catch(IOException|ErrnoException|RuntimeException ignored){}
    }
    private FileTimes(){}
}
