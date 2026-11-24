package org.example.filemanager;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.util.*;

final class RenameTemplates {
    static final String PREFS = "rename-templates";
    static Map<String, RenameExpression.Config> read(String json) throws IOException {
        try {
            JSONObject object = new JSONObject(json); if (object.length() > 50) throw new IOException("最多保存 50 个模板");
            Map<String, RenameExpression.Config> values = new LinkedHashMap<>();
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next(); validateName(key); values.put(key, RenameExpression.Config.read(object.getJSONObject(key)));
            } return values;
        } catch (JSONException error) { throw new IOException("模板数据无效", error); }
    }
    static Map<String, RenameExpression.Config> load(Context context) throws IOException {
        return read(context.getSharedPreferences(PREFS, 0).getString("values", "{}"));
    }
    static void save(Context context, String name, RenameExpression.Config config) throws IOException {
        try { RenameExpression.Config.read(config.json()); } catch (JSONException invalid) { throw new IOException(invalid); }
        validateName(name); Map<String, RenameExpression.Config> values = load(context); values.put(name, config);
        if (values.size() > 50) throw new IOException("最多保存 50 个模板"); write(context, values);
    }
    static void remove(Context context, String name) throws IOException {
        Map<String, RenameExpression.Config> values = load(context); values.remove(name); write(context, values);
    }
    private static void validateName(String name) throws IOException {
        if (name.isBlank() || name.length() > 80 || name.indexOf('\0') >= 0) throw new IOException("模板名称应为 1–80 字");
    }
    static String encode(Map<String, RenameExpression.Config> values) throws IOException {
        try { JSONObject json = new JSONObject(); for (var entry : values.entrySet()) json.put(entry.getKey(), entry.getValue().json()); return json.toString(); }
        catch (JSONException invalid) { throw new IOException(invalid); }
    }
    private static void write(Context context, Map<String, RenameExpression.Config> values) throws IOException {
        context.getSharedPreferences(PREFS, 0).edit().putString("values", encode(values)).apply();
    }
}
