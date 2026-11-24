package org.example.filemanager;

import android.app.*;
import android.content.Context;
import android.widget.*;
import java.io.IOException;
import java.util.*;
import org.json.*;

final class FileMenuOrder {
    static final String[] LABELS = {"复制", "移动", "删除", "重命名", "工具", "压缩", "解压", "属性", "分享", "打开方式", "添加书签", "文件对比"};
    static List<Integer> parse(String value) throws IOException {
        try {
            JSONArray array = new JSONArray(value); List<Integer> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.get(i);
                if (!(item instanceof Integer n) || n < 0 || n >= LABELS.length || result.contains(n)) throw new IOException("文件菜单顺序无效");
                result.add(n);
            }
            for (int i = 0; i < LABELS.length; i++) if (!result.contains(i)) result.add(i);
            return result;
        } catch (JSONException e) { throw new IOException("文件菜单顺序无效", e); }
    }
    static List<Integer> load(Context c) {
        try { return parse(FileManagerSettings.prefs(c).getString("menu-order", "[]")); }
        catch (IOException e) { try { return parse("[]"); } catch (IOException impossible) { throw new AssertionError(impossible); } }
    }
    static void show(Activity activity) {
        List<Integer> order = load(activity); List<String> names = new ArrayList<>();
        Runnable labels = () -> { names.clear(); for (int id : order) names.add(LABELS[id]); }; labels.run();
        ListView list = new ListView(activity);
        ArrayAdapter<String> adapter = SettingsDesign.sortRows(list, names);
        Runnable save = () -> { FileManagerSettings.prefs(activity).edit().putString("menu-order", new JSONArray(order).toString()).apply(); labels.run(); adapter.notifyDataSetChanged(); };
        DragOrder.install(list, (from, to) -> { order.add(to, order.remove((int) from)); save.run(); });
        list.setOnItemClickListener((p, v, at, id) -> MaterialDialogs.builder(activity).setTitle(names.get(at))
                .setItems(new String[]{"上移", "下移", "置顶"}, (d, n) -> { int to = n == 0 ? Math.max(0, at - 1) : n == 1 ? Math.min(order.size()-1, at+1) : 0;
                    order.add(to, order.remove(at)); save.run(); }).show());
        MaterialDialogs.builder(activity).setTitle("文件操作菜单排序").setView(list).setNegativeButton("关闭", null)
                .setNeutralButton("恢复默认", (d, n) -> FileManagerSettings.prefs(activity).edit().remove("menu-order").apply()).show();
    }
    private FileMenuOrder() { }
}
