package org.example.filemanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Set;

/** Keep large path lists out of Binder transactions while retaining selection order. */
final class PaneSelections {
    private static final int INLINE_CHARS = 8192;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;

    static void save(Context context, Bundle state, String key, Set<String> paths, String session) {
        long chars = 0;
        for (String path : paths) chars += path.length() + 4L;
        String ref = session + key;
        SharedPreferences prefs = context.getSharedPreferences("browser-selection", 0);
        if (chars <= INLINE_CHARS) {
            state.putStringArrayList(key, new ArrayList<>(paths));
            if (prefs.contains(ref)) prefs.edit().remove(ref).remove(ref + ".time").apply();
            return;
        }
        long now = System.currentTimeMillis();
        SharedPreferences.Editor edit = prefs.edit();
        for (var entry : prefs.getAll().entrySet()) {
            if (entry.getKey().endsWith(".time") && entry.getValue() instanceof Long time && time < now - WEEK) {
                edit.remove(entry.getKey()).remove(entry.getKey().substring(0, entry.getKey().length() - 5));
            }
        }
        edit.putString(ref, new JSONArray(paths).toString()).putLong(ref + ".time", now).apply();
        state.putString(key + ".reference", ref);
    }

    static void restore(Context context, Bundle state, String key, Set<String> target) {
        String ref = state.getString(key + ".reference");
        if (ref == null) {
            ArrayList<String> inline = state.getStringArrayList(key);
            if (inline != null) target.addAll(inline);
            return;
        }
        String json = context.getSharedPreferences("browser-selection", 0).getString(ref, null);
        if (json == null) return;
        try {
            JSONArray paths = new JSONArray(json);
            for (int i = 0; i < paths.length(); i++) target.add(paths.getString(i));
        } catch (JSONException invalid) {
            target.clear();
        }
    }
    private PaneSelections() {}
}
