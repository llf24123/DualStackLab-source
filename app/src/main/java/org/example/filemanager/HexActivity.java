package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.ViewModelProvider;
import java.io.*;
import java.util.*;

public class HexActivity extends LabActivity {
    private HexSession session;
    private TextView status, name;
    private HexGrid grid;
    private LinearLayout keypad, searchActions;
    private boolean disposed;
    private final List<View> controls = new ArrayList<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); session = new ViewModelProvider(this).get(HexSession.class);
        LinearLayout body = Ui.column(this), top = Ui.toolbar(this, "Hex 编辑器", this::leave);
        top.addView(Ui.icon(this, R.drawable.ic_back, "撤销", Color.WHITE, () -> { if (ready()) session.run(() -> session.document.undo()); }));
        View redo = Ui.icon(this, R.drawable.ic_back, "重做", Color.WHITE, () -> { if (ready()) session.run(() -> session.document.redo()); }); redo.setRotation(180); top.addView(redo);
        top.addView(Ui.icon(this, R.drawable.ic_save, "保存", Color.WHITE, () -> { if (ready()) session.save(this); }));
        top.addView(Ui.icon(this, R.drawable.ic_more, "Hex 菜单", Color.WHITE, this::menu)); body.addView(top);
        name = Ui.single(this, getIntent().getStringExtra("name"), 12, Ui.MUTED); name.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0); body.addView(name);
        ScrollView scroll = new ScrollView(this); grid = new HexGrid(); scroll.addView(grid); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        searchActions = Ui.row(this); ThemeMaterial.paint(searchActions,ThemeMaterial.Surface.FOOTER); addButton(searchActions, "上个", () -> session.find(false)); addButton(searchActions, "下个", () -> session.find(true));
        addButton(searchActions, "替换", () -> { if (session.selectedLength() == 0) { Tasks.toast(this, "先找到匹配内容"); return; } session.run(() -> {
            byte[] actual = session.document.read(session.start(), session.query.length);
            if (!Arrays.equals(actual, session.query) || session.selectedLength() != session.query.length) throw new IOException("当前选区不是搜索结果");
            long at = session.start(); session.document.replace(at, session.selectedLength(), session.replacement); session.cursor = at; session.anchor = -1;
        }); });
        addButton(searchActions, "全部", () -> MaterialDialogs.builder(this).setTitle("替换全部匹配？").setMessage("可以撤销，点击保存后才写回文件。")
                .setNegativeButton("取消", null).setPositiveButton("替换", (d,w) -> { if (ready()) session.run(() -> { long n = session.document.replaceAll(session.query, session.replacement, session.cancelled::get); session.anchor = -1; session.message = "已替换 " + n + " 处"; }); }).show());
        addButton(searchActions, "×", () -> { session.query = null; render(); }); body.addView(searchActions);
        keypad = Ui.column(this); ThemeMaterial.paint(keypad,ThemeMaterial.Surface.FOOTER);
        for (int row = 0; row < 2; row++) {
            LinearLayout line = Ui.row(this); for (int col = 0; col < 8; col++) { int digit = row * 8 + col; addButton(line, Integer.toHexString(digit).toUpperCase(Locale.ROOT), () -> session.digit(digit)); }
            keypad.addView(line);
        }
        HorizontalScrollView keyScroll = new HorizontalScrollView(this); keyScroll.setFillViewport(true); keyScroll.setHorizontalScrollBarEnabled(false);
        keypad.setMinimumWidth(Ui.dp(this, 384)); keyScroll.addView(keypad, new HorizontalScrollView.LayoutParams(-1,-2)); body.addView(keyScroll);
        LinearLayout navigation = Ui.row(this); ThemeMaterial.paint(navigation,ThemeMaterial.Surface.FOOTER); addButton(navigation, "键盘", () -> keypad.setVisibility(keypad.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        addButton(navigation, "上一页", () -> session.move(session.pageStart - HexSession.PAGE, false));
        addButton(navigation, "下一页", () -> session.move(session.pageStart + HexSession.PAGE, false));
        addButton(navigation, "定位", this::go); addButton(navigation, "选择", this::range); body.addView(navigation);
        status = Ui.text(this, "读取中…", 11, Ui.MUTED); status.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4)); body.addView(status);
        Ui.install(this, body); session.listener = this::render;
        if (session.document == null && !session.busy) session.open(this, getIntent().getStringExtra("node")); else render();
    }
    private void addButton(LinearLayout line, String text, Runnable click) {
        TextView button = Ui.text(this, text, 13, Ui.TEXT); button.setGravity(Gravity.CENTER); button.setMinimumHeight(Ui.dp(this, 48));
        button.setBackground(Ui.ripple(Color.TRANSPARENT)); button.setContentDescription("Hex " + text); button.setOnClickListener(v -> { if (ready()) click.run(); });
        line.addView(button, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1)); controls.add(button);
    }
    private boolean ready() { return session.document != null && !session.busy; }
    private void render() {
        if (disposed) return;
        for (View v : controls) v.setEnabled(ready());
        searchActions.setVisibility(session.query == null ? View.GONE : View.VISIBLE); grid.invalidate(); grid.requestLayout();
        if (session.saved) setResult(RESULT_OK);
        if (session.document != null) {
            name.setText((session.document.dirty() ? "● " : "") + session.source.name);
            status.setText((session.busy ? session.saving ? "正在保存…" : "处理中…" : session.message) + "\n偏移 0x" + Long.toHexString(session.cursor).toUpperCase(Locale.ROOT)
                    + " · 选中 " + session.selectedLength() + " · 大小 " + session.document.size() + " 字节" + (session.nibble < 0 ? "" : " · 输入 " + Integer.toHexString(session.nibble).toUpperCase(Locale.ROOT) + "_"));
        }
        if (session.error != null) { Exception error = session.error; session.error = null; status.setText(error.getMessage()); Tasks.showError(this, error); }
    }
    private void menu() {
        if (session.busy) { if (!session.saving) MaterialDialogs.builder(this).setItems(new String[]{"停止当前读取或搜索"}, (d,w) -> session.cancelled.set(true)).show(); return; }
        if (!ready()) return;
        MaterialDialogs.builder(this).setItems(new String[]{"搜索与替换", "检查", "选择范围", "复制", "复制为…", "粘贴", "粘贴从…", "插入数据", "删除选区", "取消选择"}, (d,n) -> {
            switch(n) {
                case 0 -> search(); case 1 -> inspect(); case 2 -> range(); case 3 -> copy(-1); case 4 -> copyAs();
                case 5 -> { if (session.clipboard == null) Tasks.toast(this, "请先在 Hex 编辑器复制数据"); else session.replace(session.clipboard, true); }
                case 6 -> paste(true); case 7 -> paste(false);
                case 8 -> { if (session.selectedLength() == 0) Tasks.toast(this, "先选择要删除的数据"); else session.replace(new byte[0], true); }
                case 9 -> { session.anchor = -1; render(); }
            }
        }).show();
    }
    private void copyAs() {
        MaterialDialogs.builder(this).setTitle("复制为…").setItems(new String[]{"十六进制", "UTF-8 文本", "Base64", "SHA-256", "SHA-1", "MD5", "CRC32"}, (d,n) -> copy(n)).show();
    }
    private void copy(int format) {
        long start = session.start(), count = session.selectedLength(); if (count == 0) count = Math.min(1, session.document.size() - start);
        if (count > HexDocument.CLIP_LIMIT) { Tasks.toast(this, "单次复制最多 1 MiB，请缩小选区"); return; }
        int length = (int)count;
        session.run(() -> {
            byte[] bytes = session.document.read(start, length);
            if (format < 0) session.clipboard = bytes;
            else {
                String text = HexDocument.format(bytes, format);
                session.main.post(() -> { if (!disposed) ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Hex", text)); });
            } session.message = "已复制 " + length + " 字节";
        });
    }
    private Spinner formats(LinearLayout body) {
        Spinner spinner = new Spinner(this); spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"十六进制", "UTF-8 文本", "Base64"})); body.addView(spinner); return spinner;
    }
    private void paste(boolean clipboard) {
        LinearLayout body = panel(); Spinner type = formats(body); EditText text = Ui.input(this, "内容"); text.setSingleLine(false); body.addView(text);
        if (clipboard) { ClipboardManager cb = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE); if (cb.hasPrimaryClip()) text.setText(cb.getPrimaryClip().getItemAt(0).coerceToText(this)); }
        AlertDialog dialog = MaterialDialogs.builder(this).setTitle(clipboard ? "粘贴从…" : "插入数据").setView(body).setNegativeButton("取消", null).setPositiveButton("插入 / 替换选区", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> { if (!ready()) return; try { byte[] bytes = HexDocument.parse(text.getText().toString(), type.getSelectedItemPosition()); if (bytes.length > HexDocument.CLIP_LIMIT) throw new IOException("单次最多 1 MiB"); dialog.dismiss(); session.replace(bytes, true); } catch (IOException e) { text.setError(e.getMessage()); } })); dialog.show();
    }
    private LinearLayout panel() { LinearLayout body = Ui.column(this); int p = Ui.dp(this, 16); body.setPadding(p, p, p, p); return body; }
    private static long offset(String text) { String s = text.trim(); return s.toLowerCase(Locale.ROOT).startsWith("0x") ? Long.parseLong(s.substring(2),16) : Long.parseLong(s); }
    private void go() {
        EditText field = Ui.input(this, "十进制或 0x 十六进制偏移");
        AlertDialog d = MaterialDialogs.builder(this).setTitle("跳转到偏移").setView(field).setNegativeButton("取消", null).setPositiveButton("跳转", null).create();
        d.setOnShowListener(x -> d.getButton(-1).setOnClickListener(v -> { if (!ready()) return; try { long at = offset(field.getText().toString()); if (at < 0 || at > session.document.size()) throw new NumberFormatException(); d.dismiss(); session.move(at, false); } catch (NumberFormatException e) { field.setError("偏移超出文件范围"); } })); d.show();
    }
    private void range() {
        LinearLayout body = panel(); EditText start = Ui.input(this, "起始偏移"), end = Ui.input(this, "结束偏移（包含该字节）"); body.addView(start); body.addView(end);
        start.setText(Long.toString(session.cursor)); end.setText(Long.toString(session.cursor));
        AlertDialog d = MaterialDialogs.builder(this).setTitle("选择数据范围").setView(body).setNegativeButton("取消", null).setPositiveButton("选择", null).create();
        d.setOnShowListener(x -> d.getButton(-1).setOnClickListener(v -> { if (!ready()) return; try {
            long a = offset(start.getText().toString()), b = offset(end.getText().toString());
            if (a < 0 || b < a || b >= session.document.size()) throw new NumberFormatException();
            d.dismiss(); session.anchor = b; session.move(a, true);
        } catch (NumberFormatException e) { end.setError("选择范围无效"); } })); d.show();
    }
    private void search() {
        if (!ready()) return;
        LinearLayout body = panel(); Spinner type = formats(body); EditText find = Ui.input(this, "搜索内容"), replace = Ui.input(this, "替换为（留空为删除）"); body.addView(find); body.addView(replace);
        if (session.query != null) find.setText(HexDocument.hex(session.query));
        AlertDialog d = MaterialDialogs.builder(this).setTitle("搜索与替换").setView(body).setNegativeButton("取消", null).setPositiveButton("查找", null).create();
        d.setOnShowListener(x -> d.getButton(-1).setOnClickListener(v -> { if (!ready()) return; try {
            byte[] q = HexDocument.parse(find.getText().toString(), type.getSelectedItemPosition()), r = HexDocument.parse(replace.getText().toString(), type.getSelectedItemPosition());
            if (q.length == 0 || q.length > HexDocument.CLIP_LIMIT || r.length > HexDocument.CLIP_LIMIT) throw new IOException("搜索不能为空，内容最多 1 MiB");
            session.query = q; session.replacement = r; d.dismiss(); session.cursor = 0; session.anchor = -1;
            session.run(() -> { long p = session.document.find(q, 0, session.document.size(), false, session.cancelled::get); if (p < 0) session.message = "未找到匹配内容"; else { session.cursor = p; session.anchor = p + q.length - 1; } });
        } catch (IOException e) { find.setError(e.getMessage()); } })); d.show();
    }
    private void inspect() {
        if (!ready()) return;
        int length = (int)Math.min(8, session.selectedLength() > 0 ? session.selectedLength() : session.document.size() - session.cursor);
        long start = session.start();
        session.run(() -> {
            byte[] bytes = session.document.read(start, length);
            session.main.post(() -> { if (disposed) return;
                LinearLayout body = panel(); CheckBox endian = new CheckBox(this); endian.setText("高字节优先（大端）"); endian.setMinHeight(Ui.dp(this,48)); body.addView(endian);
                TextView values = Ui.text(this, HexDocument.inspect(bytes, false), 16, Ui.TEXT); values.setTextIsSelectable(true); body.addView(values);
                endian.setOnCheckedChangeListener((b,on) -> values.setText(HexDocument.inspect(bytes, on)));
                ScrollView scroll = new ScrollView(this); scroll.addView(body); MaterialDialogs.builder(this).setTitle("检查").setView(scroll).setPositiveButton("关闭", null).show();
            });
        });
    }
    private void leave() {
        if (session.busy) { Tasks.toast(this, session.saving ? "正在保存，请稍候" : "可在菜单中停止当前操作"); return; }
        if (session.document != null && session.document.dirty()) MaterialDialogs.builder(this).setTitle("修改尚未保存").setNegativeButton("继续编辑", null)
                .setNeutralButton("放弃修改", (d,w) -> finish()).setPositiveButton("保存", (d,w) -> session.save(this)).show();
        else finish();
    }
    @Override protected void handleBack() { leave(); }
    @Override protected void onPaletteChanged() { if (grid != null) grid.invalidate(); }
    @Override protected void onDestroy() { disposed = true; session.listener = null; super.onDestroy(); }

    private final class HexGrid extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); final GestureDetector gestures;
        float downX, downY; boolean edge;
        HexGrid() {
            super(HexActivity.this); setContentDescription("十六进制数据，点击字节定位，长按选择；也可用底部选择按钮输入范围"); setFocusable(true);
            paint.setTypeface(Typeface.MONOSPACE);
            gestures = new GestureDetector(HexActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapUp(MotionEvent e) { if (ready()) { long at = hit(e); session.move(at, session.anchor >= 0); } return true; }
                @Override public void onLongPress(MotionEvent e) { if (ready()) { long at = hit(e); session.move(at, false); session.anchor = session.cursor; render(); menu(); } }
            });
        }
        float row() { return Ui.dp(HexActivity.this, 28); }
        long hit(MotionEvent e) {
            int row = Math.max(0, (int)(e.getY()/row()) - 1); float unit = getWidth()/40f;
            int col = e.getX() < unit*32 ? Math.max(0, Math.min(7, (int)((e.getX()-unit*8)/(unit*3)))) : Math.max(0, Math.min(7, (int)((e.getX()-unit*32)/unit)));
            return Math.min(session.document.size(), session.pageStart + row*8L + col);
        }
        @Override protected void onMeasure(int w,int h) { setMeasuredDimension(MeasureSpec.getSize(w), (int)(row()*(Math.max(1,(session.page.length+7)/8)+2))); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float unit = getWidth()/40f; paint.setTextSize(unit*1.55f); float height = row();
            int stripe=ToolAppearance.byteStripe(), selection=ToolAppearance.byteSelection();
            canvas.drawColor(Ui.SURFACE); paint.setColor(Ui.MUTED); paint.setTextAlign(Paint.Align.CENTER);
            for(int i=0;i<8;i++) canvas.drawText(Integer.toString(i), unit*(9.5f+i*3),height*.73f,paint);
            for(int r=0;r<=session.page.length/8;r++) {
                float y = (r+1)*height;
                if((r&1)==1) { paint.setColor(stripe);canvas.drawRect(0,y,getWidth(),y+height,paint); }
                paint.setTextAlign(Paint.Align.RIGHT);paint.setColor(Ui.MUTED); paint.setTextSize(unit*1.35f);
                canvas.drawText(String.format(Locale.ROOT,"%06X",session.pageStart+r*8L),unit*7.6f,y+height*.73f,paint);
                paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(unit*1.55f);
                for(int c=0;c<8;c++) {
                    int i=r*8+c; long absolute=session.pageStart+i;
                    if(i>session.page.length)break;
                    boolean selected=absolute==session.cursor || session.anchor>=0 && absolute>=session.start() && absolute<session.start()+session.selectedLength();
                    if(selected) {paint.setColor(selection);canvas.drawRect(unit*(8+c*3),y,unit*(11+c*3),y+height,paint);}
                    if(i==session.page.length)break;
                    int value=session.page[i]&255;paint.setColor(Ui.TEXT);
                    canvas.drawText(String.format(Locale.ROOT,"%02X",value),unit*(9.5f+c*3),y+height*.73f,paint);
                    paint.setTextSize(unit*1.3f);canvas.drawText(value>=32&&value<127?Character.toString((char)value):"·",unit*(32.5f+c),y+height*.73f,paint);paint.setTextSize(unit*1.55f);
                }
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN) { downX=e.getX();downY=e.getY();edge=downX<Ui.dp(HexActivity.this,24)||downX>getWidth()-Ui.dp(HexActivity.this,24); }
            if(edge&&e.getActionMasked()==MotionEvent.ACTION_UP&&Math.abs(e.getX()-downX)>Ui.dp(HexActivity.this,64)&&Math.abs(e.getY()-downY)<Ui.dp(HexActivity.this,48)) { if(downX<getWidth()/2)search();else inspect();return true; }
            return gestures.onTouchEvent(e)||super.onTouchEvent(e);
        }
    }
}
