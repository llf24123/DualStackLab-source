package org.example.filemanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import org.json.*;
import java.io.*;
import java.util.*;

final class DirectoryPlaces {
    static final int RECENT_LIMIT = 50;
    record Destination(FileStore.Node directory, String focusKey, String focusName) { }
    record Entry(String key, String label, String path, String uri, boolean root, boolean directory, String parentKey) {
        Entry(String key, String label, String path, String uri, boolean root) {
            this(key, label, path, uri, root, true, "");
        }
        static Entry from(FileStore.Node node) {
            return from(node, null);
        }
        static Entry from(FileStore.Node node, FileStore.Node parent) {
            String label = node.file != null && node.path().equals(Environment.getExternalStorageDirectory().getAbsolutePath())
                    ? "内部存储" : node.name;
            return new Entry(node.key(), label, node.path(), node.uri == null ? "" : node.uri.toString(), node.root,
                    node.directory, parent == null ? "" : parent.key());
        }
        String displayPath() { return uri.isEmpty() ? path : uri; }
        FileStore.Node resolve(FileStore store) throws IOException {
            if (!directory) return store.fromKey(key);
            if (key.startsWith("zip:")) return store.fromKey(key);
            if (!uri.isEmpty()) return store.document(Uri.parse(uri));
            File directory = new File(path);
            return StorageNavigation.jump(directory);
        }
        Destination destination(FileStore store) throws IOException {
            if (directory) return new Destination(resolve(store), null, null);
            FileStore.Node parent;
            if (!parentKey.isEmpty()) {
                parent = store.fromKey(parentKey);
            } else parent = store.parent(resolve(store));
            if (parent == null) throw new IOException("此文件没有可定位的上级目录");
            if (parent.file != null) parent = StorageNavigation.jump(parent.file);
            String name = path.substring(path.lastIndexOf('/') + 1);
            return new Destination(parent, key, name);
        }
    }
    private final SharedPreferences preferences;
    private final Context context;
    DirectoryPlaces(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("directory-places", Context.MODE_PRIVATE);
        if (!preferences.contains("bookmarks")) {
            List<Entry> defaults = new ArrayList<>();
            File base = Environment.getExternalStorageDirectory();
            for (String path : new String[]{
                    "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv",
                    "/storage/emulated/0/Download", "/data/adb",
                    "/storage/emulated/0/Android/data/tw.nekomimi.nekogram/files/Telegram/Telegram Files",
                    new File(base, "Documents").getPath()})
                defaults.add(Entry.from(FileStore.directory(new File(path), path.equals("/data/adb"))));
            save("bookmarks", defaults);
        }
    }
    List<Entry> entries(boolean bookmarks) {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(bookmarks ? "bookmarks" : "recent", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i); if (item == null) continue;
                String key = item.optString("key"), path = item.optString("path");
                if (key.isEmpty() || path.isEmpty()) continue;
                entries.add(new Entry(key, item.optString("label", path), path,
                        item.optString("uri"), item.optBoolean("root"), item.optBoolean("directory", true), item.optString("parent")));
            }
        } catch (JSONException ignored) { }
        return entries;
    }
    void visit(FileStore.Node node) {
        if (!node.directory) return;
        List<Entry> items = entries(false); items.removeIf(e -> e.key.equals(node.key()));
        items.add(0, Entry.from(node));
        if (items.size() > RECENT_LIMIT) items.subList(RECENT_LIMIT, items.size()).clear();
        save("recent", items);
    }
    void bookmark(Entry entry) {
        List<Entry> items = entries(true);
        if (items.stream().anyMatch(e -> e.key.equals(entry.key))) return;
        if (FileManagerSettings.prefs(context).getBoolean("bookmarks-top", false)) items.add(0, entry); else items.add(entry);
        save("bookmarks", items);
    }
    void rename(String key, String label) {
        List<Entry> items = entries(true);
        for (int i = 0; i < items.size(); i++) {
            Entry old = items.get(i);
            if (old.key.equals(key)) items.set(i, new Entry(old.key, label, old.path, old.uri, old.root, old.directory, old.parentKey));
        }
        save("bookmarks", items);
    }
    void move(String key, int direction) {
        List<Entry> items = entries(true);
        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(key)) {
            int to = i + direction;
            if (to >= 0 && to < items.size()) Collections.swap(items, i, to);
            break;
        }
        save("bookmarks", items);
    }
    void remove(boolean bookmarks, String key) {
        List<Entry> items = entries(bookmarks); items.removeIf(e -> e.key.equals(key));
        save(bookmarks ? "bookmarks" : "recent", items);
    }
    void clear(boolean bookmarks) { save(bookmarks ? "bookmarks" : "recent", Collections.emptyList()); }
    private void save(String key, List<Entry> items) {
        JSONArray array = new JSONArray();
        for (Entry entry : items) {
            try {
                array.put(new JSONObject().put("key", entry.key).put("label", entry.label)
                        .put("path", entry.path).put("uri", entry.uri).put("root", entry.root)
                        .put("directory", entry.directory).put("parent", entry.parentKey));
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }
        preferences.edit().putString(key, array.toString()).apply();
    }
}
