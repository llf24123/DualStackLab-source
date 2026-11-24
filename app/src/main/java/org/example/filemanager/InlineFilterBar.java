package org.example.filemanager;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;

/** A filter session is bound to the pane which opened it, never to the changing active pane. */
@android.annotation.SuppressLint("ViewConstructor")
final class InlineFilterBar extends LinearLayout {
    interface Listener { void apply(String text, boolean regex); void close(boolean cancel); }
    private final EditText input;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private boolean explicitRegex, appliedRegex;
    private boolean closed;
    private String applied;
    private final Runnable update = this::apply;

    InlineFilterBar(Activity activity, String value, boolean regex, Listener listener) {
        super(activity); this.listener = listener; explicitRegex = regex; appliedRegex = regex; applied = value;
        setGravity(Gravity.CENTER_VERTICAL); setContentDescription("顶栏快速过滤");
        TextView cancel = button("取消", "取消过滤"); cancel.setOnClickListener(v -> cancel());
        input = Ui.input(activity, regex ? "正则表达式" : "过滤文件名");
        input.setTextColor(Color.WHITE); input.setHintTextColor(0xffe1e7e9); input.setText(value);
        input.setContentDescription("快速过滤关键字"); input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addView(input, new LayoutParams(0, -1, 1));
        TextView clear = button("×", "清除过滤"); clear.setTextSize(26);
        clear.setOnClickListener(v -> clearAndFinish());
        TextView done = button("完成", "完成过滤"); done.setOnClickListener(v -> { if (apply()) close(false); });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
                if (apply()) close(false); return true;
            } return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { handler.removeCallbacks(update); handler.postDelayed(update, 100); }
            public void afterTextChanged(Editable text) { }
        });
    }
    private TextView button(String text, String description) {
        TextView view = Ui.text(getContext(), text, 13, Color.WHITE); view.setGravity(Gravity.CENTER);
        view.setContentDescription(description); view.setTooltipText(description); view.setBackground(Ui.ripple(Color.TRANSPARENT));
        view.setFocusable(true); addView(view, new LayoutParams(Ui.dp(getContext(), 48), -1)); return view;
    }
    void applyChrome(boolean frost) {
        int ink=frost?Ui.TEXT:Color.WHITE;
        for(int i=0;i<getChildCount();i++)if(getChildAt(i) instanceof TextView t)t.setTextColor(ink);
        input.setHintTextColor(frost?Ui.MUTED:0xffe1e7e9);
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(frost?Ui.ACCENT:0xffe1e7e9));
    }
    private boolean apply() {
        handler.removeCallbacks(update); if (closed) return false;
        String text = input.getText().toString();
        try { FileNameFilter.compile(text, explicitRegex); input.setError(null); }
        catch (java.util.regex.PatternSyntaxException invalid) { input.setError("正则表达式无效，请检查括号和转义"); return false; }
        if (!text.equals(applied) || explicitRegex != appliedRegex) { applied = text; appliedRegex = explicitRegex; listener.apply(text, explicitRegex); }
        return true;
    }
    void focus() {
        input.requestFocus(); input.setSelection(input.length());
        handler.post(() -> { if (!closed) ((InputMethodManager)getContext().getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT); });
    }
    void cancel() { close(true); }
    void clearAndFinish() { explicitRegex = false; input.setText(""); finishLastValid(); }
    void finishLastValid() { apply(); close(false); }
    private void close(boolean cancel) {
        if (closed) return;
        ((InputMethodManager)getContext().getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        dispose(); listener.close(cancel);
    }
    void dispose() { closed = true; handler.removeCallbacksAndMessages(null); }
}
