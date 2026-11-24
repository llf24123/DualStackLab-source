package org.example.filemanager;

import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SettingsBackup {
    static final int LIMIT = 2 * 1024 * 1024;
    private static final Set<String> STRINGS = Set.of("home-left", "home-right", "filter-left", "filter-right");
    private static final Set<String> BOOLEANS = Set.of("showHidden", "comfortable", "regex-left", "regex-right");
    private static final Set<String> INTEGERS = Set.of("sort-left", "sort-right");
    record Data(JSONObject main, String theme, ThemeMaterial material, Map<ThemeMaterial,IconStyle> iconStyles, List<DirectoryPlaces.Entry> bookmarks, List<DirectoryPlaces.Entry> recent,
                Map<String, RenameExpression.Config> templates, Map<String, OpenMethods.Choice> openMethods, List<Integer> openOrder, JSONObject extras) { }
    static byte[] export(Context context) throws Exception {
        new DirectoryPlaces(context);
        JSONObject main = new JSONObject(); var prefs = context.getSharedPreferences("MainActivity", 0);
        for (var item : prefs.getAll().entrySet())
            if (STRINGS.contains(item.getKey()) || BOOLEANS.contains(item.getKey()) || INTEGERS.contains(item.getKey())) main.put(item.getKey(), item.getValue());
        var places = context.getSharedPreferences("directory-places", 0);
        JSONObject iconStyles=new JSONObject();
        for (ThemeMaterial material : ThemeMaterial.values()) if(material!=ThemeMaterial.CLASSIC)
            iconStyles.put(material.id,IconStyle.forMaterial(context,material).id);
        byte[] data = new JSONObject().put("format", "DualStackLab.settings").put("version", 1)
                .put("main", main).put("theme", Ui.themeMode(context)).put("material",ThemeMaterial.current(context).id)
                .put("iconStyles",iconStyles)
                .put("renameTemplates", new JSONObject(RenameTemplates.encode(RenameTemplates.load(context))))
                .put("openMethods", new JSONObject(OpenMethods.encode(OpenMethods.load(context))))
                .put("openOrder", new JSONArray(OpenMethods.order(context)))
                .put("extras", SettingsExtras.export(context))
                .put("bookmarks", new JSONArray(places.getString("bookmarks", "[]")))
                .put("recent", new JSONArray(places.getString("recent", "[]"))).toString(2).getBytes(StandardCharsets.UTF_8);
        if (data.length > LIMIT) throw new IOException("备份超过 2 MB，请减少书签后重试");
        return data;
    }
    static Data read(InputStream input) throws Exception {
        if (input == null) throw new IOException("无法打开备份文件");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        try (InputStream owned = input) { while ((n = owned.read(buffer)) != -1) {
            if (bytes.size() + n > LIMIT) throw new IOException("备份文件超过 2 MB"); bytes.write(buffer, 0, n);
        } }
        JSONObject json = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        if (!"DualStackLab.settings".equals(json.optString("format")) || json.getInt("version") != 1) throw new IOException("不是支持的双栈 Lab 设置备份");
        JSONObject main = json.getJSONObject("main");
        for (Iterator<String> keys = main.keys(); keys.hasNext();) {
            String key = keys.next(); Object value = main.get(key);
            if (STRINGS.contains(key)) {
                if (!(value instanceof String text) || text.length() > 16384) throw new IOException("设置内容无效：" + key);
                if (key.startsWith("home-")) validateLocation((String) value);
            } else if (BOOLEANS.contains(key)) { if (!(value instanceof Boolean)) throw new IOException("开关值无效：" + key); }
            else if (INTEGERS.contains(key)) { if (!(value instanceof Integer num) || num < 0 || num > 2) throw new IOException("排序设置无效"); }
            else throw new IOException("备份包含未知设置：" + key);
        }
        String theme = json.getString("theme");
        if (!Set.of("system", "light", "dark").contains(theme)) throw new IOException("主题设置无效");
        JSONObject extras = json.has("extras") ? json.getJSONObject("extras") : null;
        if (extras != null) SettingsExtras.validate(extras);
        ThemeMaterial material=json.has("material")?ThemeMaterial.fromId(json.getString("material")):null;
        if (json.has("material") && material==null) throw new IOException("主题材质无效");
        Map<ThemeMaterial,IconStyle> iconStyles=new EnumMap<>(ThemeMaterial.class);
        if(json.has("iconStyles")) {
            JSONObject values=json.getJSONObject("iconStyles");
            for (Iterator<String> keys=values.keys();keys.hasNext();) {
                String key=keys.next();ThemeMaterial family=ThemeMaterial.fromId(key);
                Object value=values.get(key);IconStyle style=value instanceof String s?IconStyle.fromId(s):null;
                if(family==null || family==ThemeMaterial.CLASSIC || style==null)throw new IOException("文件图标样式无效："+key);
                iconStyles.put(family,style);
            }
        }
        return new Data(main, theme, material, Collections.unmodifiableMap(iconStyles),entries(json.getJSONArray("bookmarks")), entries(json.getJSONArray("recent")),
                json.has("renameTemplates") ? RenameTemplates.read(json.getJSONObject("renameTemplates").toString()) : null,
                json.has("openMethods") ? OpenMethods.read(json.getJSONObject("openMethods").toString()) : null,
                json.has("openOrder") ? OpenMethods.parseOrder(json.getJSONArray("openOrder").toString()) : null, extras);
    }
    private static List<DirectoryPlaces.Entry> entries(JSONArray array) throws Exception {
        if (array.length() > 5000) throw new IOException("备份中的目录数量过多");
        List<DirectoryPlaces.Entry> entries = new ArrayList<>(); Set<String> keys = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.getJSONObject(i); String key = value.getString("key"), uri = value.getString("uri");
            String label = value.getString("label"), path = value.getString("path");
            validateLocation(key);
            if (label.isBlank() || label.length() > 1024 || path.isBlank() || path.length() > 16384 || path.indexOf('\0') >= 0)
                throw new IOException("目录记录无效");
            if (!uri.isEmpty() && (!uri.equals(key) || !"content".equals(Uri.parse(uri).getScheme()))) throw new IOException("存储授权记录无效");
            String parent = value.optString("parent");
            if (!parent.isEmpty()) validateLocation(parent);
            if (value.has("directory") && !(value.get("directory") instanceof Boolean)) throw new IOException("书签类型无效");
            if (keys.add(key)) entries.add(new DirectoryPlaces.Entry(key, label, path, uri, value.getBoolean("root"),
                    value.optBoolean("directory", true), parent));
        }
        return List.copyOf(entries);
    }
    private static void validateLocation(String key) throws IOException { validateLocation(key, 0); }
    private static void validateLocation(String key, int depth) throws IOException {
        if (key.isEmpty() || key.length() > 16384 || key.indexOf('\0') >= 0 || depth > 8) throw new IOException("目录地址无效");
        if (key.startsWith("/") || key.startsWith("root:/") || key.startsWith("content://")) return;
        if (key.startsWith("zip:")) {
            Uri uri = Uri.parse(key); String source = uri.getQueryParameter("source"), entry = uri.getQueryParameter("entry");
            if (source == null || entry == null) throw new IOException("归档书签无效");
            if (!entry.isEmpty()) ArchiveOps.validateEntry(entry);
            else if (!"1".equals(uri.getQueryParameter("dir"))) throw new IOException("归档根位置必须是目录");
            validateLocation(source, depth + 1); return;
        }
        throw new IOException("目录地址格式无效");
    }
    static synchronized void apply(Context context, Data data, boolean merge) throws Exception {
        // Validate the entire document before the first preference write.
        var main = context.getSharedPreferences("MainActivity", 0);
        var appearance = context.getSharedPreferences("appearance", 0);
        var places = context.getSharedPreferences("directory-places", 0);
        var templates = context.getSharedPreferences(RenameTemplates.PREFS, 0);
        var openMethods = context.getSharedPreferences(OpenMethods.PREFS, 0);
        Map<String, ?> oldMain = new HashMap<>(main.getAll()), oldTheme = new HashMap<>(appearance.getAll()), oldPlaces = new HashMap<>(places.getAll());
        Map<String, ?> oldTemplates = new HashMap<>(templates.getAll()), oldMethods = new HashMap<>(openMethods.getAll());
        String templateJson = null, methodJson = null;
        if (data.extras != null) SettingsExtras.validate(data.extras);
        SettingsExtras.Snapshot oldExtras = data.extras == null ? null : SettingsExtras.snapshot(context);
        if (data.templates != null) {
            Map<String, RenameExpression.Config> merged = merge ? RenameTemplates.load(context) : new LinkedHashMap<>();
            data.templates.forEach(merged::putIfAbsent); templateJson = RenameTemplates.encode(merged); RenameTemplates.read(templateJson);
        }
        if (data.openMethods != null) {
            Map<String, OpenMethods.Choice> merged = merge ? OpenMethods.load(context) : new TreeMap<>();
            data.openMethods.forEach(merged::putIfAbsent); methodJson = OpenMethods.encode(merged); OpenMethods.read(methodJson);
        }
        try {
            var editor = main.edit();
            if (!merge) { for (String key : STRINGS) editor.remove(key); for (String key : BOOLEANS) editor.remove(key); for (String key : INTEGERS) editor.remove(key); }
            for (Iterator<String> keys = data.main.keys(); keys.hasNext();) { String key = keys.next(); put(editor, key, data.main.get(key)); }
            DirectoryPlaces current = new DirectoryPlaces(context);
            List<DirectoryPlaces.Entry> bookmarks = merge ? merge(current.entries(true), data.bookmarks) : data.bookmarks;
            List<DirectoryPlaces.Entry> recent = merge ? merge(data.recent, current.entries(false)) : data.recent;
            recent = recent.subList(0, Math.min(recent.size(), DirectoryPlaces.RECENT_LIMIT));
            var themeEditor=appearance.edit().putString("mode",data.theme);
            if(data.material!=null)themeEditor.putString("material",data.material.id);
            else if(!merge)themeEditor.remove("material");
            for(ThemeMaterial family:ThemeMaterial.values()) if(family!=ThemeMaterial.CLASSIC) {
                IconStyle value=data.iconStyles.get(family);
                if(value!=null || !merge)IconStyle.write(themeEditor,family,value==null?IconStyle.FOLLOW:value);
            }
            if (!editor.commit() || !themeEditor.commit()
                    || !places.edit().putString("bookmarks", encode(bookmarks)).putString("recent", encode(recent)).commit()) throw new IOException("保存设置失败");
            if (templateJson != null && !templates.edit().putString("values", templateJson).commit()) throw new IOException("保存改名模板失败");
            var methodsEditor = openMethods.edit();
            if (methodJson != null) methodsEditor.putString("values", methodJson);
            if (data.openOrder != null) methodsEditor.putString("order", new JSONArray(data.openOrder).toString());
            if (!methodsEditor.commit()) throw new IOException("保存打开方式失败");
            SettingsExtras.apply(context, data.extras, merge);
        } catch (Exception error) {
            restore(main, oldMain); restore(appearance, oldTheme); restore(places, oldPlaces);
            restore(templates, oldTemplates); restore(openMethods, oldMethods);
            try { SettingsExtras.restore(context, oldExtras); } catch (Exception rollback) { error.addSuppressed(rollback); }
            throw error;
        }
    }
    private static List<DirectoryPlaces.Entry> merge(List<DirectoryPlaces.Entry> first, List<DirectoryPlaces.Entry> second) {
        Map<String, DirectoryPlaces.Entry> values = new LinkedHashMap<>();
        for (var entry : first) values.put(entry.key(), entry); for (var entry : second) values.putIfAbsent(entry.key(), entry);
        return new ArrayList<>(values.values());
    }
    private static String encode(List<DirectoryPlaces.Entry> entries) throws JSONException {
        JSONArray array = new JSONArray();
        for (var entry : entries) array.put(new JSONObject().put("key", entry.key()).put("label", entry.label()).put("path", entry.path()).put("uri", entry.uri()).put("root", entry.root())
                .put("directory", entry.directory()).put("parent", entry.parentKey()));
        return array.toString();
    }
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String text) editor.putString(key, text); else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
        else if (value instanceof Integer number) editor.putInt(key, number); else if (value instanceof Long number) editor.putLong(key, number);
        else if (value instanceof Float number) editor.putFloat(key, number);
    }
    private static void restore(SharedPreferences prefs, Map<String, ?> old) {
        var editor = prefs.edit().clear(); for (var entry : old.entrySet()) put(editor, entry.getKey(), entry.getValue()); editor.commit();
    }
    static boolean needsGrant(Context context, DirectoryPlaces.Entry entry) {
        String key = entry.key(); int depth = 0;
        while (key.startsWith("zip:") && depth++ < 8) { String source = Uri.parse(key).getQueryParameter("source"); if (source == null) return true; key = source; }
        if (!key.startsWith("content:")) return false;
        Uri uri = Uri.parse(key); if (DataDocumentsProvider.owns(uri)) return !ShizukuStorage.granted();
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) if (permission.isReadPermission()) {
            Uri grant = permission.getUri(); if (grant.equals(uri)) return false;
            try { if (Objects.equals(grant.getAuthority(), uri.getAuthority()) && DocumentsContract.isTreeUri(uri)
                    && DocumentsContract.getTreeDocumentId(grant).equals(DocumentsContract.getTreeDocumentId(uri))) return false; }
            catch (IllegalArgumentException ignored) { }
        }
        return true;
    }
    private SettingsBackup() { }
}
