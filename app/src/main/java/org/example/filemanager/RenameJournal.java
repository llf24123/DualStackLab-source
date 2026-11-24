package org.example.filemanager;

import android.app.*;
import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Write-ahead record: every temporary/final name is durable before touching the directory. */
final class RenameJournal {
    private final AtomicFile file;
    private final JSONObject state;
    private final Map<String, JSONObject> items = new LinkedHashMap<>();
    private RenameJournal(File file, JSONObject state) throws JSONException {
        this.file = new AtomicFile(file); this.state = state;
        JSONArray entries = state.getJSONArray("items");
        for (int i = 0; i < entries.length(); i++) { JSONObject item = entries.getJSONObject(i); items.put(item.getString("key"), item); }
    }
    static RenameJournal begin(FileStore store, BatchRename.Plan plan, List<BatchRename.Item> changes,
                               Map<BatchRename.Item, String> temporary) throws IOException {
        try {
            JSONArray entries = new JSONArray();
            for (BatchRename.Item item : changes) {
                FileStore.Node source = item.source();
                String signature = signature(store, source);
                entries.put(new JSONObject().put("key", source.key()).put("current", source.key()).put("original", source.name)
                        .put("target", item.name()).put("temp", temporary.get(item)).put("signature", signature));
            }
            JSONObject state = new JSONObject().put("parent", plan.parent().key()).put("complete", false).put("items", entries);
            RenameJournal journal = new RenameJournal(new File(directory(store.context()), UUID.randomUUID() + ".json"), state);
            journal.save(); return journal;
        } catch (JSONException e) { throw new IOException(e); }
    }
    private static String signature(FileStore store, FileStore.Node node) throws IOException {
        if (node.uri == null || node.directory) return "identity:" + store.identity(node);
        String revision = store.fingerprint(node); return "content:" + revision.substring(revision.lastIndexOf(':') + 1);
    }
    void moved(BatchRename.Item item, FileStore.Node current) throws IOException { moved(items.get(item.source().key()), current); }
    private void moved(JSONObject item, FileStore.Node current) throws IOException {
        try {
            item.put("current", current.key());
            // Local files are recoverable from the durable names and inode signature. Only
            // document providers may return a new opaque URI that needs to be persisted.
            if (current.uri != null) save();
        } catch (JSONException e) { throw new IOException(e); }
    }
    void complete() throws IOException {
        try { state.put("complete", true); save(); file.delete(); } catch (JSONException e) { throw new IOException(e); }
    }
    private void save() throws IOException {
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(state.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (IOException error) { if (out != null) file.failWrite(out); throw error; }
    }
    private static File directory(Context context) throws IOException {
        File directory = new File(context.getNoBackupFilesDir(), "rename-recovery");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存重命名恢复记录"); return directory;
    }
    static List<RenameJournal> pending(Context context) throws IOException {
        List<RenameJournal> result = new ArrayList<>(); File[] files = directory(context).listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return result;
        for (File file : files) {
            try {
                AtomicFile atomic = new AtomicFile(file);
                try (InputStream input = atomic.openRead()) {
                    JSONObject json = new JSONObject(new String(FileStore.readBytes(input, 32 * 1024 * 1024), StandardCharsets.UTF_8));
                    if (json.optBoolean("complete")) atomic.delete(); else result.add(new RenameJournal(file, json));
                }
            } catch (JSONException error) { throw new IOException("重命名恢复记录损坏：" + file.getName(), error); }
        } return result;
    }
    void restore(FileStore store) throws Exception {
        FileStore.Node parent = store.fromKey(state.getString("parent"));
        List<FileStore.Node> children = store.list(parent);
        Map<JSONObject, FileStore.Node> found = new LinkedHashMap<>(); Set<String> claimed = new HashSet<>();
        for (JSONObject item : items.values()) {
            Set<String> names = new HashSet<>(List.of(item.getString("original"), item.getString("temp"), item.getString("target")));
            FileStore.Node match = null;
            for (FileStore.Node child : children) {
                if (!names.contains(child.name) && !child.key().equals(item.getString("current"))) continue;
                if (claimed.contains(child.key()) || !item.getString("signature").equals(signature(store, child))) continue;
                if (match != null) throw new IOException("无法唯一定位待恢复文件，请核对：" + item.getString("original"));
                match = child;
            }
            if (match == null) throw new IOException("文件已被修改或无法读取，恢复记录保留：" + item.getString("original"));
            claimed.add(match.key()); found.put(item, match);
        }
        // Free final names first, so cyclic renames recover without overwriting another item.
        for (var entry : found.entrySet()) {
            JSONObject item = entry.getKey(); FileStore.Node node = entry.getValue();
            String temp = item.getString("temp");
            if (!node.name.equals(temp)) { node = store.rename(node, temp); entry.setValue(node); moved(item, node); }
        }
        for (var entry : found.entrySet()) {
            FileStore.Node restored = store.rename(entry.getValue(), entry.getKey().getString("original")); moved(entry.getKey(), restored);
            if (!restored.name.equals(entry.getKey().getString("original"))) throw new IOException("提供方未恢复原名：" + restored.path());
        }
        complete();
    }
    static void offerPending(Activity activity) {
        Tasks.run(activity, () -> pending(activity), records -> {
            if (records.isEmpty()) return;
            MaterialDialogs.builder(activity).setTitle("发现未完成的批量重命名")
                    .setMessage("有 " + records.size() + " 次操作留下恢复记录，可以恢复文件原名。")
                    .setNegativeButton("稍后", null).setPositiveButton("恢复原名", (d, w) -> {
                        Tasks.run(activity, Tasks.FILES, () -> {
                            FileStore store = new FileStore(activity);
                            for (RenameJournal journal : records) journal.restore(store); return true;
                        }, done -> { Tasks.toast(activity, "已恢复原文件名"); activity.recreate(); }, error -> Tasks.showError(activity, error));
                    }).show();
        }, error -> Tasks.showError(activity, error));
    }
}
