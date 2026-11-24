package org.example.filemanager;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.*;

public final class DataDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".androiddata";
    private static final String[] COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS};
    @Override public boolean onCreate() { return true; }
    static String root() { return new File(Environment.getExternalStorageDirectory(), "Android/data").getPath(); }
    static Uri uri(String path) {
        int boundary = path.indexOf("/Android/data");
        if (boundary < 0) throw new IllegalArgumentException("目录不属于 Android/data");
        String root = path.substring(0, boundary + "/Android/data".length());
        return DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(AUTHORITY, root), path);
    }
    static boolean owns(Uri uri) { return uri != null && AUTHORITY.equals(uri.getAuthority()); }
    static String path(Uri uri) { return DocumentsContract.getDocumentId(uri); }
    private IDataAccess remote() throws IOException { return ShizukuStorage.remote(getContext()); }
    private static FileNotFoundException failure(Exception error) { return new FileNotFoundException(error.getMessage()); }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        if (ShizukuStorage.granted()) {
            Map<String, Object> values = new HashMap<>();
            values.put(DocumentsContract.Root.COLUMN_ROOT_ID, "android-data"); values.put(DocumentsContract.Root.COLUMN_DOCUMENT_ID, root());
            values.put(DocumentsContract.Root.COLUMN_TITLE, "Android/data（Shizuku）");
            values.put(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY);
            values.put(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
            MatrixCursor.RowBuilder row = cursor.newRow(); for (String column : columns) row.add(column, values.get(column));
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        try { add(cursor, remote().stat(documentId)); return cursor; }
        catch (Exception error) { throw failure(error); }
    }
    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        try {
            IDataAccess service = remote(); int offset = 0;
            do {
                Bundle page = service.list(parentDocumentId, offset);
                ArrayList<Bundle> entries = page.getParcelableArrayList("entries");
                if (entries == null) throw new IOException("Shizuku 未返回目录列表");
                for (Bundle entry : entries) add(cursor, entry);
                int next = page.getInt("next", -1);
                if (next != -1 && next <= offset) throw new IOException("目录分页无效");
                offset = next;
            } while (offset != -1);
            return cursor;
        } catch (Exception error) { throw failure(error); }
    }
    private static void add(MatrixCursor cursor, Bundle info) {
        String path = info.getString("path"), name = info.getString("name"); boolean directory = info.getBoolean("directory");
        int dot = name.lastIndexOf('.');
        String mime = directory ? DocumentsContract.Document.MIME_TYPE_DIR : dot < 0 ? null
                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (mime == null) mime = "application/octet-stream";
        int flags = 0;
        if (info.getBoolean("writable")) {
            flags = directory ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
            if (!path.matches("/storage/emulated/[0-9]+/Android/data"))
                flags |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
        }
        Map<String, Object> values = new HashMap<>();
        values.put(COLUMNS[0], path); values.put(COLUMNS[1], name); values.put(COLUMNS[2], mime);
        values.put(COLUMNS[3], info.getLong("size")); values.put(COLUMNS[4], info.getLong("modified")); values.put(COLUMNS[5], flags);
        MatrixCursor.RowBuilder row = cursor.newRow(); for (String column : cursor.getColumnNames()) row.add(column, values.get(column));
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        try { return remote().open(documentId, mode); }
        catch (Exception error) { throw failure(error); }
    }
    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        try {
            String created = remote().create(parentDocumentId, displayName, DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType));
            changed(parentDocumentId); return created;
        }
        catch (Exception error) { throw failure(error); }
    }
    @Override public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        try { String renamed = remote().rename(documentId, displayName); changed(documentId); changed(renamed); return renamed; }
        catch (Exception error) { throw failure(error); }
    }
    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        try { remote().delete(documentId); changed(documentId); }
        catch (Exception error) { throw failure(error); }
    }
    private void changed(String path) {
        File parent = path == null ? null : new File(path).getParentFile();
        if (parent != null) getContext().getContentResolver().notifyChange(uri(parent.getPath()), null);
    }
    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return normalized(parentDocumentId) && normalized(documentId) && documentId.startsWith(parentDocumentId + "/");
    }
    private static boolean normalized(String path) {
        return path != null && path.matches("/storage/emulated/[0-9]+/Android/data(?:/.*)?")
                && new File(path).toPath().toAbsolutePath().normalize().toString().equals(path);
    }
}
