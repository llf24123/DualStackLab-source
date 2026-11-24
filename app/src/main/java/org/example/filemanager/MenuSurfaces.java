package org.example.filemanager;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.*;

/** Shared presentation for action menus; actions and selection remain owned by the caller. */
final class MenuSurfaces {
    static android.graphics.drawable.Drawable surface(Context c, int color, int radius) {
        int corner=SettingsDesign.radius(radius);
        return new RippleDrawable(ColorStateList.valueOf(Ui.rippleColor()),
                Ui.material==ThemeMaterial.CLASSIC||color==0?Ui.background(color, Ui.dp(c,corner)):Ui.material.card(c,color,corner), Ui.background(0xffffffff,Ui.dp(c,corner)));
    }
    static LinearLayout heading(Activity a, String title, String subtitle, String closeLabel, Runnable close) {
        LinearLayout row = Ui.row(a); row.setPadding(Ui.dp(a, 20), Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12));
        LinearLayout labels = Ui.column(a);
        TextView name = Ui.text(a, title, 19, Ui.TEXT); name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView detail = Ui.single(a, subtitle, 12, Ui.MUTED); detail.setPadding(0, Ui.dp(a, 5), 0, 0);
        labels.addView(detail); row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView dismiss = Ui.text(a, "×", 26, Ui.MUTED); dismiss.setGravity(Gravity.CENTER);
        dismiss.setContentDescription(closeLabel); dismiss.setBackground(surface(a, Ui.PANEL, 24));
        dismiss.setFocusable(true); dismiss.setOnClickListener(v -> close.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)); p.leftMargin = Ui.dp(a, 12);
        row.addView(dismiss, p); return row;
    }
    static LinearLayout action(Activity a, int iconId, String title, String subtitle, boolean enabled, boolean destructive) {
        LinearLayout row = Ui.row(a); row.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12));
        int color = destructive ? (Ui.dark ? 0xffffa4a0 : 0xffad3b37) : Ui.ACCENT;
        ImageView icon = Ui.image(a, iconId, color);
        icon.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
        icon.setBackground(Ui.background(destructive ? (Ui.dark ? 0xff432e30 : 0xfffcecea) : Ui.TINT, Ui.dp(a, 12)));
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36)));
        LinearLayout labels = Ui.column(a); labels.setPadding(Ui.dp(a, 10), 0, 0, 0);
        TextView name = Ui.text(a, title, 15, destructive ? color : Ui.TEXT); labels.addView(name);
        if (!subtitle.isEmpty()) { TextView detail = Ui.text(a, subtitle, 11, Ui.MUTED); detail.setPadding(0, Ui.dp(a, 3), 0, 0); labels.addView(detail); }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.setMinimumHeight(Ui.dp(a, 64)); row.setBackground(surface(a, Ui.PANEL, 14));
        row.setEnabled(enabled); row.setFocusable(enabled); row.setAlpha(enabled ? 1f : .38f);
        return row;
    }
    static ScrollView scroll(Activity a, View content, String description) {
        ScrollView scroll = new ScrollView(a) {
            @Override protected void onMeasure(int width, int height) {
                int max = Math.max(Ui.dp(a, 160), (int)(a.getResources().getDisplayMetrics().heightPixels * .78f));
                int available = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED ? max : MeasureSpec.getSize(height);
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.min(available, max), MeasureSpec.AT_MOST));
            }
        };
        scroll.setContentDescription(description); scroll.setClipToOutline(true);
        scroll.setBackground(Ui.material.card(a,Ui.SURFACE,24));
        scroll.addView(content); return scroll;
    }
    static void show(Activity a, Dialog dialog, View panel) {
        Window window = dialog.getWindow(); if (window == null) return;
        window.setWindowAnimations(Ui.motionEnabled() ? R.style.ActionMenuAnimation : 0);
        if(Ui.material==ThemeMaterial.FROST)GlassWindow.install(window,panel,24,false);
        else window.setBackgroundDrawableResource(android.R.color.transparent);
        dialog.show();
        window.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 24), Ui.dp(a, 540)), -2);
        window.setGravity(Gravity.CENTER);
        android.view.WindowManager.LayoutParams p = window.getAttributes(); p.y = 0; p.dimAmount = .32f; window.setAttributes(p);
    }
    private MenuSurfaces() { }
}
