package org.example.filemanager;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.*;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.IOException;
import java.util.*;

final class RemovableStorage {
    private static final String DOCUMENTS = "com.android.externalstorage.documents";
    record Volume(String id, String name, File directory, String state, StorageVolume system) {
        String title() { return name + (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state) ? "（只读）" : ""); }
        boolean mounted() { return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state); }
        Intent authorization() {
            Intent intent = Build.VERSION.SDK_INT >= 29 && system != null
                    ? system.createOpenDocumentTreeIntent() : new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            if (Build.VERSION.SDK_INT < 29 && id.matches("[A-Za-z0-9-]+"))
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(DOCUMENTS, id + ":"));
            return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
    }
    static List<Volume> volumes(Context context) {
        StorageManager manager = context.getSystemService(StorageManager.class);
        List<Volume> result = new ArrayList<>();
        if (manager == null) return result;
        for (StorageVolume volume : manager.getStorageVolumes()) {
            if (volume.isPrimary() || !volume.isRemovable() || !present(volume.getState())) continue;
            String uuid = volume.getUuid();
            File path = Build.VERSION.SDK_INT >= 30 ? volume.getDirectory()
                    : uuid != null && uuid.matches("[A-Za-z0-9-]+") ? new File("/storage", uuid) : null;
            String id = uuid != null ? uuid : path != null ? path.getAbsolutePath() : volume.getDescription(context);
            String name = volume.getDescription(context) + (uuid == null ? "" : " · " + uuid);
            result.add(new Volume(id, name, path, volume.getState(), volume));
        }
        result.sort(Comparator.comparing(Volume::name).thenComparing(Volume::id));
        return result;
    }
    static boolean present(String state) {
        return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)
                || Environment.MEDIA_CHECKING.equals(state) || Environment.MEDIA_NOFS.equals(state)
                || Environment.MEDIA_UNMOUNTABLE.equals(state);
    }
    /** Null requests system authorization. A stale grant never prevents direct access or reauthorization. */
    static FileStore.Node open(Context context, FileStore store, Volume volume) {
        List<Uri> readable = new ArrayList<>();
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions())
            if (permission.isReadPermission()) readable.add(permission.getUri());
        Uri grant = matchingTree(readable, volume.id);
        if (grant != null) try {
            FileStore.Node node = store.document(DocumentsContract.buildDocumentUriUsingTree(grant, DocumentsContract.getTreeDocumentId(grant)));
            if (node.directory) { store.list(node); return node; }
        } catch (IOException | SecurityException ignored) { }
        if (volume.directory != null) try {
            FileStore.Node node = RootAccess.directory(volume.directory, false);
            store.list(node); return node;
        } catch (IOException | SecurityException ignored) { }
        return null;
    }
    static Uri matchingTree(List<Uri> grants, String volumeId) {
        Uri result = null; int shortest = Integer.MAX_VALUE;
        for (Uri grant : grants) {
            if (!DOCUMENTS.equals(grant.getAuthority()) || !DocumentsContract.isTreeUri(grant)) continue;
            try {
                String id = DocumentsContract.getTreeDocumentId(grant); int colon = id.indexOf(':');
                if (colon < 0 || !volumeId.equalsIgnoreCase(id.substring(0, colon))) continue;
                if (id.length() < shortest) { result = grant; shortest = id.length(); }
            } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }
    private RemovableStorage() { }
}
