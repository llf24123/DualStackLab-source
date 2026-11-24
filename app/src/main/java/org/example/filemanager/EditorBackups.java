package org.example.filemanager;

import java.io.*;
import java.nio.file.Files;

final class EditorBackups {
    /** Copy before publication; an archive entry backs up its outer container without changing its revision. */
    static void beforeSave(FileStore store, FileStore.Node edited) throws IOException {
        if (!FileManagerSettings.prefs(store.context()).getBoolean("editor-backup", false)) return;
        FileStore.Node source=edited; while(source.container!=null)source=source.container;
        FileStore.Node parent=store.parent(source);
        if(parent==null)throw new IOException("无法定位备份目录");
        String revision=store.fingerprint(source);
        File staging=File.createTempFile("editor-backup-",".tmp",store.context().getCacheDir());
        FileStore.Node backup=null;
        try {
            try(InputStream in=store.read(source);OutputStream out=new FileOutputStream(staging)){FileStore.pump(in,out);}
            if(!revision.equals(store.fingerprint(source)))throw new IOException("备份期间原文件发生变化");
            backup=store.create(parent,store.unique(parent,source.name+".bak"),false);
            store.write(backup,staging);
        }catch(IOException|RuntimeException e){if(backup!=null)try{store.delete(backup);}catch(Exception cleanup){e.addSuppressed(cleanup);}throw e;}
        finally{Files.deleteIfExists(staging.toPath());}
    }
    private EditorBackups() { }
}
