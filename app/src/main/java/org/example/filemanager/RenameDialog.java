package org.example.filemanager;

import android.app.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

final class RenameDialog {
    static void show(Activity activity, FileStore store, FileStore.Node parent, List<FileStore.Node> sources, Consumer<BatchRename.Plan> execute) {
        List<FileStore.Node> nodes = List.copyOf(sources);
        int pad = Ui.dp(activity, 16);
        LinearLayout body = Ui.column(activity); body.setPadding(pad, 0, pad, 0);
        Spinner mode = new Spinner(activity); mode.setContentDescription("批量改名模式");
        mode.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{"表达式模式", "简单替换与编号"}));
        body.addView(mode, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 48)));
        EditText expression = Ui.input(activity, "文件名表达式"); expression.setContentDescription("文件名表达式"); expression.setText("{P}{S}"); body.addView(expression);
        LinearLayout templateActions = Ui.row(activity);
        Button templates = new Button(activity); templates.setText("模板");
        Button save = new Button(activity); save.setText("保存模板");
        templateActions.addView(templates, new LinearLayout.LayoutParams(0, -2, 1)); templateActions.addView(save, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(templateActions);
        LinearLayout replacementRow = Ui.row(activity);
        EditText find = Ui.input(activity, "查找内容"), replacement = Ui.input(activity, "替换内容");
        find.setContentDescription("查找内容"); replacement.setContentDescription("替换内容");
        replacementRow.addView(find, new LinearLayout.LayoutParams(0, -2, 1)); replacementRow.addView(replacement, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(replacementRow);
        CheckBox regex = new CheckBox(activity); regex.setText("使用正则表达式查找替换"); regex.setMinHeight(Ui.dp(activity, 48)); body.addView(regex);
        EditText date = Ui.input(activity, "时间格式"); date.setContentDescription("改名时间格式"); date.setText(RenameExpression.DEFAULT_DATE); body.addView(date);
        LinearLayout simple = Ui.column(activity);
        CheckBox number = new CheckBox(activity); number.setText("在名称末尾追加序号"); number.setMinHeight(Ui.dp(activity, 48));
        EditText start = Ui.input(activity, "起始序号"), extension = Ui.input(activity, "统一后缀（留空保留）");
        start.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); start.setText("1"); simple.addView(number); simple.addView(start); simple.addView(extension); body.addView(simple);
        TextView help = Ui.text(activity, "{P} 原名  {S} 后缀  {1} 序号  {z1} 补零序号\n{T} 修改时间  {E} 拍摄时间（无则用修改时间）\n{AN} 应用名  {AP} 包名  {AV} 版本名  {AC} 版本号\n正则支持环视与反向引用；替换用 $1 或 ${组名}。\n留空查找内容可跳过替换。预览不会修改文件。", 12, Ui.MUTED);
        help.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 12)); body.addView(help);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                boolean advanced = position == 0;
                for (View view : new View[]{expression, templateActions, regex, date, help}) view.setVisibility(advanced ? View.VISIBLE : View.GONE);
                simple.setVisibility(advanced ? View.GONE : View.VISIBLE);
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        java.util.function.Supplier<RenameExpression.Config> config = () -> new RenameExpression.Config(expression.getText().toString(), date.getText().toString(), find.getText().toString(), replacement.getText().toString(), regex.isChecked());
        Consumer<RenameExpression.Config> fill = value -> {
            expression.setText(value.expression()); date.setText(value.date()); find.setText(value.find()); replacement.setText(value.replacement()); regex.setChecked(value.regex());
        };
        templates.setOnClickListener(v -> templatePicker(activity, fill));
        save.setOnClickListener(v -> {
            RenameExpression.Config value = config.get();
            try { new RenameExpression(value); } catch (IllegalArgumentException invalid) { expression.setError(invalid.getMessage()); return; }
            EditText name = Ui.input(activity, "模板名称"); name.setContentDescription("模板名称");
            AlertDialog naming = MaterialDialogs.builder(activity).setTitle("保存改名模板").setView(name).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            naming.setOnShowListener(d -> naming.getButton(-1).setOnClickListener(button -> {
                String key = name.getText().toString().trim();
                try {
                    if (RenameTemplates.load(activity).containsKey(key)) {
                        MaterialDialogs.builder(activity).setTitle("替换同名模板？").setMessage(key).setNegativeButton("取消", null)
                                .setPositiveButton("替换", (q, w) -> saveTemplate(activity, naming, key, value)).show();
                    } else { RenameTemplates.save(activity, key, value); naming.dismiss(); Tasks.toast(activity, "模板已保存"); }
                } catch (Exception error) { name.setError(error.getMessage()); }
            })); naming.show();
        });
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        AlertDialog dialog = MaterialDialogs.builder(activity).setTitle("批量重命名 · " + nodes.size() + " 项").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("预览", null).create();
        boolean[] dismissed = {false}; java.util.concurrent.Future<?>[] job = {null};
        dialog.setOnDismissListener(d -> { dismissed[0] = true; if (job[0] != null) job[0].cancel(true); });
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            BatchRename.Naming rule;
            try {
                rule = mode.getSelectedItemPosition() == 0 ? new RenameExpression(config.get())
                        : new BatchRename.Rule(find.getText().toString(), replacement.getText().toString(), number.isChecked(), Integer.parseInt(start.getText().toString()), extension.getText().toString().trim());
            } catch (IllegalArgumentException invalid) { (mode.getSelectedItemPosition() == 0 ? expression : start).setError(invalid.getMessage()); return; }
            dialog.getButton(-1).setEnabled(false); dialog.getButton(-1).setText("正在预览…");
            job[0] = Tasks.BROWSE.submit(() -> {
                try {
                    BatchRename.Plan plan = BatchRename.preview(store, parent, nodes, rule);
                    activity.runOnUiThread(() -> {
                        if (dismissed[0] || activity.isDestroyed()) return;
                        dialog.getButton(-1).setEnabled(true); dialog.getButton(-1).setText("预览");
                        showPreview(activity, plan, () -> { dialog.dismiss(); execute.accept(plan); });
                    });
                } catch (Exception error) { activity.runOnUiThread(() -> {
                    if (dismissed[0] || activity.isDestroyed()) return;
                    dialog.getButton(-1).setEnabled(true); dialog.getButton(-1).setText("预览"); Tasks.showError(activity, error);
                }); }
            });
        })); dialog.show();
    }
    private static void saveTemplate(Activity activity, AlertDialog dialog, String key, RenameExpression.Config config) {
        try { RenameTemplates.save(activity, key, config); dialog.dismiss(); Tasks.toast(activity, "模板已保存"); }
        catch (Exception error) { Tasks.showError(activity, error); }
    }
    private static void templatePicker(Activity activity, Consumer<RenameExpression.Config> select) {
        try {
            Map<String, RenameExpression.Config> saved = RenameTemplates.load(activity);
            List<String> labels = new ArrayList<>(List.of("原名加 .bak", "照片时间与序号", "应用名与版本")); labels.addAll(saved.keySet());
            List<RenameExpression.Config> values = new ArrayList<>();
            for (String value : List.of("{P}{S}.bak", "{E}_{z1}{S}", "{AN}_{AV}{S}")) values.add(new RenameExpression.Config(value, RenameExpression.DEFAULT_DATE, "", "", false));
            values.addAll(saved.values());
            ListView list = new ListView(activity); list.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels));
            AlertDialog dialog = MaterialDialogs.builder(activity).setTitle("改名模板").setView(list).setNegativeButton("关闭", null).create();
            list.setOnItemClickListener((p,v,pos,id) -> { select.accept(values.get(pos)); dialog.dismiss(); });
            list.setOnItemLongClickListener((p,v,pos,id) -> {
                if (pos < 3) return true;
                MaterialDialogs.builder(activity).setTitle(labels.get(pos)).setItems(new String[]{"删除模板"}, (d,n) -> {
                    try { RenameTemplates.remove(activity, labels.get(pos)); dialog.dismiss(); templatePicker(activity, select); }
                    catch (Exception error) { Tasks.showError(activity, error); }
                }).show(); return true;
            }); dialog.show();
        } catch (Exception error) { Tasks.showError(activity, error); }
    }
    private static void showPreview(Activity activity, BatchRename.Plan plan, Runnable execute) {
        ListView list = new ListView(activity); list.setContentDescription("批量重命名预览");
        list.setAdapter(new BaseAdapter() {
            public int getCount() { return plan.items().size(); }
            public Object getItem(int pos) { return plan.items().get(pos); }
            public long getItemId(int pos) { return pos; }
            public View getView(int pos, View reuse, ViewGroup parent) {
                BatchRename.Item item = plan.items().get(pos);
                LinearLayout row = Ui.column(activity); int pad = Ui.dp(activity, 14); row.setPadding(pad, pad / 2, pad, pad / 2);
                row.addView(Ui.text(activity, item.source().name, 13, Ui.MUTED));
                row.addView(Ui.text(activity, "→ " + item.name(), 15, item.error().isEmpty() ? Ui.TEXT : 0xffd85757));
                if (!item.error().isEmpty()) row.addView(Ui.text(activity, item.error(), 12, 0xffd85757));
                row.setMinimumHeight(Ui.dp(activity, 56)); return row;
            }
        });
        AlertDialog preview = MaterialDialogs.builder(activity).setTitle("预览 · " + plan.changes() + " 项更名").setView(list)
                .setNegativeButton("返回修改", null).setPositiveButton("执行", (d,w) -> execute.run()).create();
        preview.setOnShowListener(d -> { preview.getButton(-1).setEnabled(plan.valid() && plan.changes() > 0); Ui.fadeIn(list); }); preview.show();
    }
}
