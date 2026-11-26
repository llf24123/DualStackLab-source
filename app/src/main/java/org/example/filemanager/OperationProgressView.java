package org.example.filemanager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** A transient, nonmodal progress strip. Its worker state contains no Activity reference. */
@android.annotation.SuppressLint("ViewConstructor")
final class OperationProgressView extends LinearLayout {
    private final TextView detail, stop;
    private final ProgressBar bar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Tasks.Progress progress;
    private Runnable cancelAction;
    private boolean showing, ticking;
    private final Runnable tick = this::update;
    OperationProgressView(Context context) {
        super(context); setOrientation(VERTICAL); setVisibility(GONE);
        setContentDescription("文件操作进度"); ThemeMaterial.paint(this, ThemeMaterial.Surface.BODY);
        int pad = Ui.dp(context, 12); setPadding(pad, Ui.dp(context, 3), Ui.dp(context, 4), Ui.dp(context, 5));
        LinearLayout row = Ui.row(context); row.setGravity(Gravity.CENTER_VERTICAL);
        detail = Ui.text(context, "", 12, Ui.TEXT); detail.setMaxLines(3); detail.setMinLines(2);
        detail.setContentDescription("操作进度详情"); row.addView(detail, new LayoutParams(0, -2, 1));
        stop = Ui.text(context, "停止", 13, Ui.ACCENT); stop.setGravity(Gravity.CENTER);
        stop.setContentDescription("停止文件操作"); stop.setBackground(Ui.ripple(android.graphics.Color.TRANSPARENT));
        stop.setOnClickListener(view -> {
            if (progress != null && progress.cancelled.compareAndSet(false, true)) {
                if (cancelAction != null) cancelAction.run();
                update();
            }
        });
        row.addView(stop, new LayoutParams(Ui.dp(context, 56), Ui.dp(context, 48))); addView(row);
        bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setIndeterminate(true); addView(bar, new LayoutParams(-1, Ui.dp(context, 4)));
    }
    void bind(Tasks.Progress value) { bind(value, null); }
    void bind(Tasks.Progress value, Runnable onCancel) {
        if (progress == value) return;
        clear(); progress = value; cancelAction = onCancel;
        if (isAttachedToWindow()) schedule(650);
    }
    void clear() {
        handler.removeCallbacks(tick); ticking = false; progress = null; cancelAction = null; showing = false;
        setVisibility(GONE);
    }
    private void schedule(long delay) {
        if (ticking || progress == null) return;
        ticking = true; handler.postDelayed(tick, delay);
    }
    private void update() {
        ticking = false;
        if (progress == null || !isAttachedToWindow()) return;
        Tasks.Progress.Snapshot snapshot = progress.snapshot();
        if (snapshot.finished()) { clear(); return; }
        if (!showing) { showing = true; setVisibility(VISIBLE); }
        detail.setText(format(snapshot));
        stop.setText(snapshot.cancelled() ? "停止中" : "停止"); stop.setEnabled(!snapshot.cancelled());
        boolean indeterminate = snapshot.percent() < 0 || snapshot.cancelled();
        if (bar.isIndeterminate() != indeterminate) bar.setIndeterminate(indeterminate);
        if (!indeterminate) bar.setProgress(snapshot.percent(), true);
        schedule(250);
    }
    static String format(Tasks.Progress.Snapshot value) {
        if (value.cancelled()) return "正在停止 · 等待当前安全边界完成";
        String first = value.phase() + (value.percent() >= 0 ? " · " + value.percent() + "%" : " · 估算中");
        String bytes = FileStore.size(value.bytes()) + (value.total() >= 0 ? " / " + FileStore.size(value.total()) : " 已处理");
        String speed = value.bytesPerSecond() > 0 ? FileStore.size((long)value.bytesPerSecond()) + "/s" : "速度估算中";
        String eta = value.remainingSeconds() >= 0 ? "预计剩余 " + duration(value.remainingSeconds()) : "预计时间估算中";
        return first + "\n" + bytes + " · " + speed + "\n" + eta;
    }
    private static String duration(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        if (seconds < 3600) return seconds / 60 + " 分 " + seconds % 60 + " 秒";
        return seconds / 3600 + " 小时 " + seconds / 60 % 60 + " 分";
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); schedule(showing ? 0 : 650); }
    @Override protected void onDetachedFromWindow() { handler.removeCallbacks(tick); ticking = false; super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (handler == null) return;
        if (visibility == VISIBLE) schedule(showing ? 0 : 650);
        else { handler.removeCallbacks(tick); ticking = false; }
    }
}
