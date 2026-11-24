package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.webkit.MimeTypeMap;
import android.widget.*;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.function.IntConsumer;

final class OpenMethods {
    static final String PREFS = "open-methods";
    static final String[] LABELS = {"编辑文本", "浏览压缩包", "查看图片", "播放音乐", "播放视频", "Apk 信息", "浏览 APKS", "Linux 脚本", "HTML 功能", "Svg 功能", "Hex 编辑器"};
    private static final int[] ICONS = {R.drawable.ic_file, R.drawable.ic_archive, R.drawable.ic_image, R.drawable.ic_music,
            R.drawable.ic_video, R.drawable.ic_package, R.drawable.ic_package, R.drawable.ic_terminal, R.drawable.ic_globe, R.drawable.ic_image, R.drawable.ic_edit};
    private static final int[] COLORS = {0xff3c60a5, 0xff795548, 0xff379da0, 0xffd93b40, 0xffec9500, 0xff46a540, 0xff795548, 0xff3c60a5, 0xff2186b6, 0xff379da0, 0xff76538d};
    record Choice(int action, String component, String mime, String label) {
        boolean external() { return action == -1; }
        JSONObject json() throws JSONException { return new JSONObject().put("action", action).put("component", component).put("mime", mime).put("label", label); }
    }
    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
    static String mime(String name) {
        String ext = extension(name); String type = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.substring(1));
        return type == null ? "application/octet-stream" : type;
    }
    static Map<String, Choice> read(String encoded) throws IOException {
        try {
            JSONObject object = new JSONObject(encoded); if (object.length() > 500) throw new IOException("默认打开方式最多 500 项");
            Map<String, Choice> values = new TreeMap<>();
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next(); JSONObject item = object.getJSONObject(key);
                if (!key.startsWith(".") || key.length() < 2 || key.length() > 128 || key.contains("/") || key.contains("\\") || key.indexOf('\0') >= 0 || !key.equals(key.toLowerCase(Locale.ROOT))) throw new IOException("文件后缀无效");
                if (!(item.get("action") instanceof Integer)) throw new IOException("打开方式无效");
                for (String field : List.of("component", "mime", "label")) if (!(item.get(field) instanceof String text) || text.length() > 1024) throw new IOException("打开方式内容无效");
                Choice choice = new Choice(item.getInt("action"), item.getString("component"), item.getString("mime"), item.getString("label"));
                if (choice.action < -1 || choice.action >= LABELS.length || choice.label.isBlank()) throw new IOException("打开方式无效");
                if (choice.external() && (ComponentName.unflattenFromString(choice.component) == null || !choice.mime.matches("[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*-]+"))) throw new IOException("外部应用打开方式无效");
                values.put(key, choice);
            } return values;
        } catch (JSONException invalid) { throw new IOException("默认打开方式数据无效", invalid); }
    }
    static Map<String, Choice> load(Context context) throws IOException { return read(context.getSharedPreferences(PREFS, 0).getString("values", "{}")); }
    static String encode(Map<String, Choice> values) throws IOException {
        try { JSONObject object = new JSONObject(); for (var item : values.entrySet()) object.put(item.getKey(), item.getValue().json()); return object.toString(); }
        catch (JSONException error) { throw new IOException(error); }
    }
    static Choice find(Context context, String name) {
        try { return load(context).get(extension(name)); } catch (IOException invalid) { return null; }
    }
    static void remember(Context context, String name, Choice choice) throws IOException {
        String ext = extension(name); if (ext.isEmpty()) throw new IOException("没有后缀的文件不能设置默认打开方式");
        Map<String, Choice> values = load(context); values.put(ext, choice); String encoded = encode(values); read(encoded);
        context.getSharedPreferences(PREFS, 0).edit().putString("values", encoded).apply();
    }
    static void forget(Context context, String ext) throws IOException {
        Map<String, Choice> values = load(context); values.remove(ext); context.getSharedPreferences(PREFS, 0).edit().putString("values", encode(values)).apply();
    }
    static List<Integer> parseOrder(String encoded) throws IOException {
        try {
            JSONArray array = new JSONArray(encoded); List<Integer> order = new ArrayList<>();
            if (array.length() > LABELS.length) throw new IOException("打开方式排序无效");
            for (int i = 0; i < array.length(); i++) {
                if (!(array.get(i) instanceof Integer n) || n < 0 || n >= LABELS.length || order.contains(n)) throw new IOException("打开方式排序无效");
                order.add(n);
            }
            for (int i = 0; i < LABELS.length; i++) if (!order.contains(i)) order.add(i);
            return order;
        } catch (JSONException error) { throw new IOException("打开方式排序无效", error); }
    }
    static List<Integer> order(Context context) {
        try { return parseOrder(context.getSharedPreferences(PREFS, 0).getString("order", "[]")); }
        catch (IOException invalid) { List<Integer> order = new ArrayList<>(); for (int i = 0; i < LABELS.length; i++) order.add(i); return order; }
    }
    static Intent intent(Uri uri, String mime, ComponentName component) {
        Intent intent = new Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_DEFAULT).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("文件", uri));
        if (component != null) intent.setComponent(component); return intent;
    }
    static List<ResolveInfo> candidates(Context context, String name, String type) {
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".files/shared/" + Uri.encode(name));
        List<ResolveInfo> found = context.getPackageManager().queryIntentActivities(intent(uri, type, null), PackageManager.MATCH_DEFAULT_ONLY);
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();
        for (ResolveInfo item : found) {
            ActivityInfo info = item.activityInfo;
            if (info == null || !info.exported || !info.enabled || !info.applicationInfo.enabled || context.getPackageName().equals(info.packageName)) continue;
            if (info.permission != null && context.checkSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED) continue;
            unique.put(info.packageName + "/" + info.name, item);
        }
        List<ResolveInfo> result = new ArrayList<>(unique.values()); result.sort(new ResolveInfo.DisplayNameComparator(context.getPackageManager())); return result;
    }
    static void launch(Activity activity, FileStore.Node node, Choice choice, Runnable fallback) {
        ComponentName component = ComponentName.unflattenFromString(choice.component);
        Tasks.run(activity, () -> {
            if (component == null || candidates(activity, node.name, choice.mime).stream().noneMatch(info -> component.equals(new ComponentName(info.activityInfo.packageName, info.activityInfo.name)))) return null;
            return ApkInstall.stageShared(activity, node, "-" + node.name);
        }, file -> {
            if (file == null) { unavailable(activity, node, fallback); return; }
            try {
                Uri uri = androidx.core.content.FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", file);
                activity.startActivity(intent(uri, choice.mime, component));
            } catch (ActivityNotFoundException | SecurityException error) { unavailable(activity, node, fallback); }
        });
    }
    private static void unavailable(Activity activity, FileStore.Node node, Runnable fallback) {
        try { forget(activity, extension(node.name)); } catch (IOException ignored) { }
        Tasks.toast(activity, "默认应用不可用，请重新选择打开方式"); fallback.run();
    }
    static void show(Activity activity, FileStore.Node node, IntConsumer internal) {
        int pad = Ui.dp(activity, 12);
        LinearLayout body = Ui.column(activity); body.setPadding(pad, 0, pad, Ui.dp(activity, 8));
        LinearLayout heading = Ui.row(activity); TextView title = Ui.text(activity, "打开方式…", 19, Ui.TEXT);
        heading.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(activity, 52), 1));
        TextView manage = Ui.text(activity, "管理", 13, Ui.ACCENT); manage.setGravity(Gravity.CENTER); manage.setBackground(Ui.ripple(Ui.SURFACE));
        heading.addView(manage, new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48))); body.addView(heading);
        GridLayout grid = new GridLayout(activity); grid.setColumnCount(3); body.addView(grid);
        TextView empty = Ui.text(activity, "没有可用应用，请切换类型", 14, Ui.MUTED); empty.setPadding(pad, pad, pad, pad); empty.setVisibility(View.GONE); body.addView(empty);
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        AlertDialog dialog = MaterialDialogs.builder(activity).setView(scroll).setNeutralButton("类型", null).setPositiveButton("关闭", null).create();
        String[] types = {"内置", "系统建议", "文本", "图片", "视频", "音频", "全部"};
        String[] mimes = {"", mime(node.name), "text/plain", "image/*", "video/*", "audio/*", "*/*"};
        int[] generation = {0}; boolean[] dismissed = {false};
        Runnable builtin = () -> {
            generation[0]++; grid.removeAllViews(); empty.setVisibility(View.GONE);
            for (int action : order(activity)) {
                LinearLayout cell = cell(activity, LABELS[action], ICONS[action], COLORS[action]); grid.addView(cell, params(activity));
                cell.setOnClickListener(v -> { dialog.dismiss(); internal.accept(action); });
                cell.setOnLongClickListener(v -> { options(activity, node, new Choice(action, "", "", LABELS[action]), dialog); return true; });
            }
            LinearLayout external = cell(activity, "其他应用", R.drawable.ic_send, 0xff568299); grid.addView(external, params(activity));
            external.setOnClickListener(v -> populateExternal(activity, node, mimes[1], grid, empty, dialog, generation, dismissed));
            LinearLayout select = cell(activity, "选择文件", R.drawable.ic_check, 0xff64942d); grid.addView(select, params(activity));
            select.setOnClickListener(v -> { dialog.dismiss(); internal.accept(11); });
            Ui.fadeIn(grid);
        };
        manage.setOnClickListener(v -> activity.startActivity(new Intent(activity, OpenMethodsActivity.class)));
        dialog.setOnDismissListener(d -> { dismissed[0] = true; generation[0]++; });
        dialog.setOnShowListener(d -> {
            dialog.getButton(-3).setOnClickListener(v -> MaterialDialogs.builder(activity).setTitle("文件类型").setItems(types, (which, index) -> {
                if (index == 0) builtin.run(); else populateExternal(activity, node, mimes[index], grid, empty, dialog, generation, dismissed);
            }).show()); builtin.run();
        }); dialog.show();
    }
    private static void populateExternal(Activity activity, FileStore.Node node, String mime, GridLayout grid, TextView empty, AlertDialog dialog, int[] generation, boolean[] dismissed) {
        int token = ++generation[0]; grid.removeAllViews(); empty.setText("读取应用…"); empty.setVisibility(View.VISIBLE);
        Tasks.run(activity, () -> {
            List<ExternalApp> apps = new ArrayList<>();
            for (ResolveInfo info : candidates(activity, node.name, mime)) {
                try { apps.add(new ExternalApp(info, info.loadLabel(activity.getPackageManager()).toString(), info.loadIcon(activity.getPackageManager()))); }
                catch (RuntimeException removed) { }
            } return apps;
        }, apps -> {
            if (dismissed[0] || token != generation[0]) return;
            empty.setText("没有可用应用，请切换类型"); empty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
            for (ExternalApp app : apps) {
                ResolveInfo info = app.info; String label = app.label;
                Choice choice = new Choice(-1, new ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToString(), mime, label);
                LinearLayout cell = cell(activity, label, R.drawable.ic_package, 0xff568299);
                ImageView icon = (ImageView)cell.getChildAt(0); icon.setImageTintList(null); icon.setPadding(0, 0, 0, 0); icon.setBackground(null);
                icon.setImageDrawable(app.icon);
                cell.setContentDescription("外部打开方式 " + label); grid.addView(cell, params(activity));
                cell.setOnClickListener(v -> { dialog.dismiss(); launch(activity, node, choice, () -> Tasks.toast(activity, "请重新打开方式列表")); });
                cell.setOnLongClickListener(v -> { options(activity, node, choice, dialog); return true; });
            } Ui.fadeIn(grid);
        }, error -> { if (!dismissed[0] && token == generation[0]) { empty.setText("读取应用失败，请重新切换类型"); } });
    }
    private record ExternalApp(ResolveInfo info, String label, android.graphics.drawable.Drawable icon) { }
    private static void options(Activity activity, FileStore.Node node, Choice choice, AlertDialog chooser) {
        MaterialDialogs.builder(activity).setTitle(choice.label).setItems(choice.external() ? new String[]{"设为默认打开方式", "应用详情"} : new String[]{"设为默认打开方式"}, (d,n) -> {
            if (n == 0) {
                try { remember(activity, node.name, choice); chooser.dismiss(); Tasks.toast(activity, extension(node.name) + " 默认使用“" + choice.label + "”"); }
                catch (IOException error) { Tasks.showError(activity, error); }
            } else {
                ComponentName component = ComponentName.unflattenFromString(choice.component);
                if (component != null) try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + component.getPackageName()))); }
                catch (ActivityNotFoundException ignored) { Tasks.toast(activity, "无法打开应用详情"); }
            }
        }).show();
    }
    private static GridLayout.LayoutParams params(Activity activity) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(); params.width = 0; params.height = Ui.dp(activity, 100);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); return params;
    }
    private static LinearLayout cell(Activity activity, String label, int drawable, int color) {
        LinearLayout cell = Ui.column(activity); cell.setGravity(Gravity.CENTER); cell.setBackground(Ui.ripple(Ui.SURFACE)); cell.setFocusable(true);
        cell.setContentDescription("打开方式 " + label);
        boolean classic=ToolAppearance.classic();
        ImageView icon = Ui.image(activity, drawable, classic?Color.WHITE:Ui.ACCENT); int pad = Ui.dp(activity, 12); icon.setPadding(pad, pad, pad, pad);
        icon.setBackground(classic?Ui.background(color,Ui.dp(activity,6)):Ui.material.card(activity,Ui.TINT,12));
        cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        TextView text = Ui.text(activity, label, 12, Ui.TEXT); text.setGravity(Gravity.CENTER); text.setMaxLines(2);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END); cell.addView(text, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 36))); return cell;
    }
    static void reorder(Activity activity) {
        List<Integer> values = order(activity); List<String> labels = new ArrayList<>(); for (int id : values) labels.add(LABELS[id]);
        ListView list = new ListView(activity); ArrayAdapter<String> adapter = SettingsDesign.sortRows(list, labels);
        MaterialDialogs.builder(activity).setTitle("内置打开方式排序").setView(list).setNegativeButton("关闭", null).setNeutralButton("恢复默认", (d,w) -> activity.getSharedPreferences(PREFS, 0).edit().remove("order").apply()).show();
        DragOrder.install(list, (from, to) -> {
            values.add(to, values.remove((int)from)); labels.add(to, labels.remove((int)from)); adapter.notifyDataSetChanged();
            activity.getSharedPreferences(PREFS, 0).edit().putString("order", new JSONArray(values).toString()).apply();
        });
        list.setOnItemClickListener((p,v,pos,id) -> MaterialDialogs.builder(activity).setTitle(labels.get(pos)).setItems(new String[]{"上移", "下移"}, (d,n) -> {
            int target = pos + (n == 0 ? -1 : 1); if (target < 0 || target >= values.size()) return;
            Collections.swap(values, pos, target); Collections.swap(labels, pos, target); adapter.notifyDataSetChanged(); Ui.fadeIn(list);
            activity.getSharedPreferences(PREFS, 0).edit().putString("order", new JSONArray(values).toString()).apply();
        }).show());
    }
    private OpenMethods() { }
}
