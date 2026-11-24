package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DirectoryCompareActivity extends LabActivity {
    private DirectoryCompare.Scan scan;
    private CompareReport.Stored report;
    private List<CompareReport.Row> rows = List.of();
    private int page, pageGeneration;
    private Button previous, next;
    private TextView pages;
    private TextView status;
    private ListView list;
    private Button start, export, sync;
    private final Map<String, CompareReport.Row> selectedRows = new LinkedHashMap<>();
    private boolean syncing;
    private CheckBox recursive, sha;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() { public void run() {
        if (scan != null) { status.setText("正在校验：" + scan.current); handler.postDelayed(this, 250); }
    } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); LinearLayout body = Ui.column(this);
        body.addView(Ui.toolbar(this, "目录内容校验", this::finish));
        recursive = new CheckBox(this); recursive.setText("递归子目录"); recursive.setMinHeight(Ui.dp(this, 48));
        sha = new CheckBox(this); sha.setText("计算 SHA-256 校验文件内容"); sha.setMinHeight(Ui.dp(this, 48));
        recursive.setChecked(saved != null && saved.getBoolean("recursive")); sha.setChecked(saved != null && saved.getBoolean("sha"));
        body.addView(recursive); body.addView(sha);
        status = Ui.text(this, "选择范围后开始校验。读取失败会列入结果，不会作为一致处理。", 14, Ui.TEXT);
        status.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8)); body.addView(status);
        LinearLayout commands = Ui.row(this);
        start = new Button(this); start.setText("开始校验"); start.setOnClickListener(v -> { if (scan == null) begin(); else { scan.cancel(); handler.removeCallbacks(ticker); start.setEnabled(false); status.setText("正在停止…"); } });
        export = new Button(this); export.setText("导出结果"); export.setEnabled(false); export.setOnClickListener(v -> {
            try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "DualStackLab-directory-compare.txt"), 1); } catch (Exception e) { Tasks.showError(this, e); }
        });
        commands.addView(start, new LinearLayout.LayoutParams(0, -2, 1)); commands.addView(export, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(commands);
        sync = new Button(this);
        sync.setMinHeight(Ui.dp(this, 48));
        sync.setOnClickListener(v -> chooseDirection());
        body.addView(sync);
        updateSync();
        list = new ListView(this); list.setContentDescription("目录校验完整差异列表"); body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        LinearLayout navigation = Ui.row(this); previous = new Button(this); previous.setText("上一页"); next = new Button(this); next.setText("下一页");
        pages = Ui.text(this, "", 13, Ui.MUTED); pages.setGravity(android.view.Gravity.CENTER);
        navigation.addView(previous); navigation.addView(pages, new LinearLayout.LayoutParams(0, -2, 1)); navigation.addView(next); body.addView(navigation);
        previous.setEnabled(false); next.setEnabled(false);
        previous.setOnClickListener(v -> loadPage(page - 1)); next.setOnClickListener(v -> loadPage(page + 1));
        list.setOnItemClickListener((p, v, position, id) -> {
            if (position >= rows.size()) return; var item = rows.get(position);
            if (list.isItemChecked(position)) selectedRows.put(item.name(), item);
            else selectedRows.remove(item.name());
            updateSync();
        });
        list.setOnItemLongClickListener((p, v, position, id) -> {
            if (position >= rows.size()) return false;
            var item = rows.get(position);
            MaterialDialogs.builder(this).setTitle(item.label()).setMessage(item.name() + "\n当前：" + item.left()
                    + "\n另一：" + item.right() + "\n" + item.detail()).setPositiveButton("关闭", null).show();
            return true;
        }); Ui.install(this, body);
        if (saved != null && saved.getString("report") != null) {
            start.setEnabled(false);
            Tasks.run(this, () -> CompareReport.open(this, saved.getString("report")), restored -> {
                start.setEnabled(true); showReport(restored, saved.getInt("page", 0));
            }, error -> { start.setEnabled(true); status.setText("原校验结果无法恢复，请重新校验"); });
        }
    }
    private void begin() {
        DirectoryCompare.Scan job = new DirectoryCompare.Scan(); scan = job; report = null; export.setEnabled(false); pageGeneration++;
        rows = List.of(); selectedRows.clear(); updateSync(); previous.setEnabled(false); next.setEnabled(false); pages.setText("");
        list.setAdapter(null); recursive.setEnabled(false); sha.setEnabled(false); start.setText("停止"); handler.post(ticker);
        DirectoryCompare.Options options = new DirectoryCompare.Options(recursive.isChecked(), sha.isChecked());
        Tasks.run(this, Tasks.INTEGRITY, () -> {
            FileStore store = new FileStore(this);
            return CompareReport.create(this, store, store.fromKey(getIntent().getStringExtra("left")), store.fromKey(getIntent().getStringExtra("right")), options, job);
        }, result -> {
            finishScan(); showReport(result, 0);
        }, error -> { finishScan(); status.setText(job.cancelled.get() ? "已停止，本次未完成校验" : "校验失败"); if (!job.cancelled.get()) Tasks.showError(this, error); });
    }
    private void showReport(CompareReport.Stored result, int page) {
        report = result; export.setEnabled(true); status.setText("已比较 " + result.files + " 项 · 差异/错误 " + result.count + " 项"); loadPage(page);
    }
    private void loadPage(int wanted) {
        if (report == null) return; int token = ++pageGeneration; CompareReport.Stored source = report;
        int selected = Math.max(0, Math.min(wanted, (source.count - 1) / CompareReport.PAGE_SIZE));
        previous.setEnabled(false); next.setEnabled(false);
        Tasks.run(this, () -> source.page(selected), found -> {
            if (token != pageGeneration) return; page = selected; rows = found;
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice,
                    found.stream().map(row -> row.label() + " · " + row.name()).toArray(String[]::new)));
            list.clearChoices();
            for (int i = 0; i < found.size(); i++) list.setItemChecked(i, selectedRows.containsKey(found.get(i).name()));
            int total = Math.max(1, (source.count + CompareReport.PAGE_SIZE - 1) / CompareReport.PAGE_SIZE);
            pages.setText((page + 1) + " / " + total); previous.setEnabled(page > 0); next.setEnabled(page + 1 < total);
        }, error -> { if (token == pageGeneration) Tasks.showError(this, error); });
    }
    private void updateSync() {
        sync.setText(selectedRows.isEmpty() ? "勾选差异后同步" : "同步已选 " + selectedRows.size() + " 项");
        sync.setEnabled(!syncing && !selectedRows.isEmpty());
    }
    private void chooseDirection() {
        List<CompareReport.Row> chosen = List.copyOf(selectedRows.values());
        MaterialDialogs.builder(this).setTitle("选择同步方向")
                .setItems(new String[]{"比较时的当前窗口 → 另一窗口", "比较时的另一窗口 → 当前窗口"},
                        (dialog, which) -> prepareSync(chosen, which == 0))
                .setNegativeButton("取消", null).show();
    }
    private void prepareSync(List<CompareReport.Row> chosen, boolean leftToRight) {
        syncing = true; updateSync(); start.setEnabled(false);
        Tasks.run(this, Tasks.FILES, () -> {
            FileStore store = new FileStore(this);
            var from = store.fromKey(getIntent().getStringExtra(leftToRight ? "left" : "right"));
            var to = store.fromKey(getIntent().getStringExtra(leftToRight ? "right" : "left"));
            return DirectorySync.preview(store, from, to, chosen, leftToRight);
        }, preview -> {
            syncing = false; updateSync(); start.setEnabled(true);
            List<String> lines = new ArrayList<>();
            for (var op : preview.operations()) {
                String action = op.source().directory ? "新建目录" : op.target() != null ? "已有同名"
                        : op.parent().missing().isEmpty() ? "新增" : "新建父目录并复制";
                lines.add(action + " · " + op.relative());
            }
            lines.addAll(preview.skipped());
            MaterialDialogs.builder(this).setTitle("同步预览 · " + preview.operations().size() + " 项")
                    .setItems(lines.stream().limit(200).toArray(String[]::new), null).setNegativeButton("取消", null)
                    .setNeutralButton(preview.operations().isEmpty() ? null : "跳过同名", (d, w) -> applySync(preview, FileStore.ConflictPolicy.SKIP))
                    .setPositiveButton(preview.operations().isEmpty() ? null : "同步并覆盖同名", (d, w) -> applySync(preview, FileStore.ConflictPolicy.OVERWRITE)).show();
        }, this::syncError);
    }
    private void applySync(DirectorySync.Preview preview, FileStore.ConflictPolicy policy) {
        syncing = true; updateSync(); start.setEnabled(false);
        Tasks.run(this, Tasks.FILES, () -> DirectorySync.apply(new FileStore(this), preview, policy), count -> {
            syncing = false;
            Tasks.toast(this, "已同步 " + count + " 项");
            begin();
            start.setEnabled(true);
        }, this::syncError);
    }
    private void syncError(Exception error) {
        syncing = false; updateSync(); start.setEnabled(true); Tasks.showError(this, error);
    }
    private void finishScan() { scan = null; handler.removeCallbacks(ticker); start.setText("开始校验"); start.setEnabled(true); recursive.setEnabled(true); sha.setEnabled(true); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        if (report == null) { Tasks.toast(this, "校验结果已释放，请重新校验后导出"); return; }
        CompareReport.Stored output = report;
        Tasks.run(this, () -> {
            try (OutputStream stream = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (stream == null) throw new IOException("无法写入报告"); output.export(stream);
            } return true;
        }, done -> Tasks.toast(this, "完整校验结果已导出"));
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean("recursive", recursive.isChecked()); out.putBoolean("sha", sha.isChecked()); if (report != null) { out.putString("report", report.id); out.putInt("page", page); } }
    @Override protected void onDestroy() { if (scan != null) scan.cancel(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
