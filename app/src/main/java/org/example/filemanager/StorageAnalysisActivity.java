package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class StorageAnalysisActivity extends LabActivity {
    private StorageAnalysis.Scan scan;
    private StorageAnalysis.Result result;
    private TextView status;
    private Button start, errors;
    private ListView list;
    private boolean duplicates;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() {
        if (scan != null) { status.setText("已扫描 " + scan.scanned + " 个文件" + (duplicates ? " · 已校验 " + scan.hashed : "") + "\n" + scan.current); handler.postDelayed(this, 250); }
    } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); duplicates = getIntent().getBooleanExtra("duplicates", false);
        LinearLayout body = Ui.column(this); body.addView(Ui.toolbar(this, duplicates ? "重复文件查找" : "目录占用排行", this::finish));
        status = Ui.text(this, (duplicates ? "先按大小筛选，再用 SHA-256 确认重复文件。" : "统计当前目录每个项目及其子目录的文件大小。")
                + "\n" + getIntent().getStringExtra("label"), 14, Ui.TEXT);
        int pad = Ui.dp(this, 16); status.setPadding(pad, pad, pad, pad); status.setMaxLines(4); body.addView(status);
        LinearLayout commands = Ui.row(this); start = new Button(this); start.setText("开始扫描"); start.setMinHeight(Ui.dp(this, 48));
        errors = new Button(this); errors.setText("读取错误"); errors.setEnabled(false);
        commands.addView(start, new LinearLayout.LayoutParams(0, -2, 1)); commands.addView(errors, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(commands);
        start.setOnClickListener(v -> {
            if (scan == null) begin(); else { scan.cancel(); handler.removeCallbacks(tick); start.setEnabled(false); status.setText("正在停止…"); }
        });
        errors.setOnClickListener(v -> { if (result != null) MaterialDialogs.builder(this).setTitle("未读取的项目")
                .setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, result.errors()), null).setPositiveButton("关闭", null).show(); });
        list = new ListView(this); list.setContentDescription(duplicates ? "重复文件结果" : "目录占用结果");
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); list.setOnItemClickListener((p, v, index, id) -> open(index)); Ui.install(this, body);
    }
    private void begin() {
        StorageAnalysis.Scan job = new StorageAnalysis.Scan(); scan = job; result = null; list.setAdapter(null);
        start.setText("停止"); errors.setEnabled(false); handler.post(tick);
        Tasks.run(this, Tasks.INTEGRITY, () -> {
            FileStore store = new FileStore(this); return job.run(store, store.fromKey(getIntent().getStringExtra("directory")), duplicates);
        }, found -> {
            finishScan(); result = found; errors.setEnabled(!found.errors().isEmpty());
            status.setText("已扫描 " + found.files() + " 个文件 · " + FileStore.size(found.bytes())
                    + (duplicates ? "\n重复 " + found.duplicates().size() + " 组 · 多余副本 " + FileStore.size(found.duplicates().stream().mapToLong(StorageAnalysis.Duplicates::redundantBytes).sum()) : "")
                    + "\n未读取 " + found.errorCount() + " 项 · 跳过链接/重复项 " + found.ignored() + " 项");
            list.setAdapter(new BaseAdapter() {
                public int getCount() { return duplicates ? found.duplicates().size() : found.entries().size(); }
                public Object getItem(int pos) { return duplicates ? found.duplicates().get(pos) : found.entries().get(pos); }
                public long getItemId(int pos) { return pos; }
                public View getView(int pos, View convert, ViewGroup parent) {
                    TextView row = convert instanceof TextView text ? text : Ui.text(StorageAnalysisActivity.this, "", 15, Ui.TEXT);
                    row.setMinHeight(Ui.dp(StorageAnalysisActivity.this, 64)); row.setPadding(Ui.dp(StorageAnalysisActivity.this, 16), Ui.dp(StorageAnalysisActivity.this, 8), Ui.dp(StorageAnalysisActivity.this, 16), Ui.dp(StorageAnalysisActivity.this, 8));
                    if (duplicates) { var group = found.duplicates().get(pos); row.setText(group.files().get(0).name + "\n" + group.files().size() + " 个相同文件 · 每个 " + FileStore.size(group.size())); }
                    else { var entry = found.entries().get(pos); row.setText(entry.node().name + "\n" + FileStore.size(entry.bytes()) + " · " + entry.files() + " 个文件" + (entry.incomplete() ? " · 未完整统计" : "")); }
                    return row;
                }
            });
        }, error -> { finishScan(); status.setText(job.cancelled.get() ? "已停止扫描" : "扫描失败"); if (!job.cancelled.get()) Tasks.showError(this, error); });
    }
    private void finishScan() { scan = null; handler.removeCallbacks(tick); start.setText("重新扫描"); start.setEnabled(true); }
    private void open(int index) {
        if (result == null) return;
        if (!duplicates) { locate(result.entries().get(index).node()); return; }
        var group = result.duplicates().get(index); ListView files = new ListView(this);
        files.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, group.files().stream().map(FileStore.Node::path).toArray(String[]::new)));
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle(group.files().size() + " 个相同文件").setView(files).setPositiveButton("关闭", null).create();
        files.setOnItemClickListener((p, v, pos, id) -> { dialog.dismiss(); locate(group.files().get(pos)); }); dialog.show();
    }
    private void locate(FileStore.Node node) {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("searchResult", node.key())); finish();
    }
    @Override protected void onDestroy() { if (scan != null) scan.cancel(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
