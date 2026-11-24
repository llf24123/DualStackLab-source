package org.example.filemanager;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.IOException;

final class AndroidDataAccess {
    static File path(FileStore.Node node) {
        if (node.container != null) return null;
        if (node.file != null) return path(node.file);
        if (DataDocumentsProvider.owns(node.uri)) return path(new File(DataDocumentsProvider.path(node.uri)));
        return null;
    }
    static File path(File file) {
        String value = file.toPath().toAbsolutePath().normalize().toString();
        String home = Environment.getExternalStorageDirectory().getPath();
        if (value.equals("/sdcard") || value.startsWith("/sdcard/")) value = home + value.substring(7);
        if (value.equals("/storage/self/primary") || value.startsWith("/storage/self/primary/")) value = home + value.substring(21);
        return value.matches("/storage/emulated/[0-9]+/Android/data(?:/.*)?") ? new File(value) : null;
    }
    static FileStore.Node resolve(Context context, FileStore store, FileStore.Node node) throws IOException {
        File directory = path(node);
        if (directory == null) return node;
        if (RootAccess.available()) return RootAccess.directory(directory, true);
        String own = new File(Environment.getExternalStorageDirectory(), "Android/data/" + context.getPackageName()).getPath();
        if ((directory.getPath().equals(own) || directory.getPath().startsWith(own + "/")) && directory.listFiles() != null)
            return FileStore.directory(directory, false);
        if (FileManagerSettings.prefs(context).getBoolean("auto-shizuku", true) && ShizukuStorage.granted())
            return store.document(DataDocumentsProvider.uri(directory.getPath()));
        if (DataDocumentsProvider.owns(node.uri)) throw new IOException("Shizuku 未启动或尚未授权");
        String home = Environment.getExternalStorageDirectory().getPath();
        String documentId = directory.getPath().startsWith(home + "/") ? "primary:" + directory.getPath().substring(home.length() + 1) : null;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (documentId == null) break;
            Uri grant = permission.getUri();
            if (!permission.isReadPermission() || !"com.android.externalstorage.documents".equals(grant.getAuthority())
                    || !DocumentsContract.isTreeUri(grant)) continue;
            String tree = DocumentsContract.getTreeDocumentId(grant);
            if (!documentId.equals(tree) && !documentId.startsWith(tree.endsWith(":") ? tree : tree + "/")) continue;
            try {
                FileStore.Node authorized = store.document(DocumentsContract.buildDocumentUriUsingTree(grant, documentId));
                store.list(authorized); return authorized;
            } catch (IOException | SecurityException expired) { }
        }
        if ((Build.VERSION.SDK_INT < 30 || !directory.getPath().endsWith("/Android/data")) && directory.listFiles() != null)
            return FileStore.directory(directory, false);
        throw new IOException("此目录需要 Shizuku 或有效的系统目录授权");
    }
    private AndroidDataAccess() { }
}
