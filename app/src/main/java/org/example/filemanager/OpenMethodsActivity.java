package org.example.filemanager;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

public class OpenMethodsActivity extends LabActivity {
    private LinearLayout content;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); showPage();
    }
    @Override protected void onPaletteChanged() { if (content != null) showPage(); }
    private void showPage() {
        LinearLayout root = Ui.column(this), toolbar = Ui.toolbar(this, "默认打开方式", this::finish);
        content = Ui.column(this); root.addView(toolbar); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, root);
        if (SettingsDesign.themed()) {
            SettingsDesign.chrome(this,root,toolbar);
            content.setPadding(dp(16), dp(12), dp(16), dp(12));
        }
        refresh();
    }
    private void refresh() {
        content.removeAllViews();
        addCommand("内置打开方式排序", R.drawable.ic_sort, true, () -> OpenMethods.reorder(this));
        try {
            var values = OpenMethods.load(this); List<String> keys = new ArrayList<>(values.keySet());
            if (keys.isEmpty()) { showEmpty(); return; }
            ListView list = new ListView(this); content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            if (SettingsDesign.themed()) {
                list.setBackgroundColor(Color.TRANSPARENT); list.setCacheColorHint(Color.TRANSPARENT);
                list.setDivider(new ColorDrawable(Color.TRANSPARENT)); list.setDividerHeight(dp(5));
                list.setSelector(SettingsDesign.ripple(this, Color.TRANSPARENT, SettingsDesign.radius(14))); list.setDrawSelectorOnTop(true);
            }
            list.setAdapter(new BaseAdapter() {
                public int getCount() { return keys.size(); }
                public Object getItem(int p) { return keys.get(p); }
                public long getItemId(int p) { return p; }
                public View getView(int p, View reuse, ViewGroup parent) {
                    String key = keys.get(p); OpenMethods.Choice choice = values.get(key);
                    View row = reuse == null ? ruleRow() : reuse;
                    RuleText text = (RuleText) row.getTag(); text.suffix.setText(key); text.label.setText(choice.label());
                    text.component.setText(choice.component()); text.component.setVisibility(choice.external() ? View.VISIBLE : View.GONE); return row;
                }
            });
            java.util.function.IntConsumer remove = p -> MaterialDialogs.builder(this).setTitle(keys.get(p)).setItems(new String[]{"删除默认方式"}, (d,n) -> {
                try { OpenMethods.forget(this, keys.get(p)); refresh(); } catch (Exception error) { Tasks.showError(this, error); }
            }).show();
            list.setOnItemClickListener((p,v,pos,id) -> remove.accept(pos)); list.setOnItemLongClickListener((p,v,pos,id) -> { remove.accept(pos); return true; });
            addCommand("清除所有默认方式", R.drawable.ic_trash, false, () -> MaterialDialogs.builder(this).setTitle("清除所有默认方式？").setNegativeButton("取消", null).setPositiveButton("清除", (d,w) -> {
                getSharedPreferences(OpenMethods.PREFS, 0).edit().remove("values").apply(); refresh();
            }).show());
        } catch (Exception error) { Tasks.showError(this, error); }
    }
    private int dp(int value) { return Ui.dp(this, value); }
    private void addCommand(String title, int icon, boolean first, Runnable click) {
        Button button = SettingsDesign.command(this, title, icon); button.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        if (SettingsDesign.themed()) { if (first) params.bottomMargin = dp(8); else params.topMargin = dp(8); }
        content.addView(button, params);
    }
    private void showEmpty() {
        String title = "尚未设置默认方式", hint = "在文件的“打开方式”中长按一项，即可按后缀记住。";
        if (!SettingsDesign.themed()) {
            TextView empty = Ui.text(this, title + "\n" + hint, 14, Ui.MUTED); int pad = dp(20);
            empty.setPadding(pad, pad, pad, pad); content.addView(empty); return;
        }
        LinearLayout empty = Ui.row(this); empty.setPadding(dp(6), dp(20), dp(6), dp(20));
        empty.addView(SettingsDesign.icon(this, R.drawable.ic_file), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout copy = Ui.column(this); copy.setPaddingRelative(dp(14), 0, 0, 0);
        TextView name = Ui.text(this, title, 16, Ui.TEXT); name.setTypeface(null, Typeface.BOLD); copy.addView(name);
        TextView description = Ui.text(this, hint, 14, Ui.MUTED); description.setLineSpacing(dp(2), 1f);
        description.setPadding(0, dp(5), 0, 0); copy.addView(description);
        empty.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        ScrollView scroll = new ScrollView(this); scroll.addView(empty); content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private record RuleText(TextView suffix, TextView label, TextView component) { }
    private View ruleRow() {
        boolean themed = SettingsDesign.themed(); LinearLayout row = Ui.row(this), detail = Ui.column(this);
        row.setMinimumHeight(dp(themed ? 64 : 56)); row.setPadding(dp(16), dp(8), dp(16), dp(8));
        if (themed) {
            row.setBackground(Ui.material.card(this, SettingsDesign.card(), SettingsDesign.radius(14)));
            row.addView(SettingsDesign.icon(this, R.drawable.ic_file), new LinearLayout.LayoutParams(dp(38), dp(38)));
            detail.setPaddingRelative(dp(12), 0, dp(8), 0);
        } else row.setGravity(Gravity.TOP);
        row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
        TextView suffix = Ui.text(this, "", 16, Ui.TEXT), label = Ui.text(this, "", 13, Ui.MUTED), component = Ui.text(this, "", 11, Ui.MUTED);
        if (themed) {
            suffix.setTypeface(null, Typeface.BOLD); label.setPadding(0, dp(3), 0, 0);
            row.addView(SettingsDesign.chevron(this), new LinearLayout.LayoutParams(dp(18), dp(20)));
        }
        detail.addView(suffix); detail.addView(label); detail.addView(component); row.setTag(new RuleText(suffix, label, component)); return row;
    }
}
