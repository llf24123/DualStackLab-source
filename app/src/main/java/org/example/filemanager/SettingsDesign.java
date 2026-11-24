package org.example.filemanager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;

/** The settings surface is deliberately independent of the compact file-list styling. */
final class SettingsDesign {
    static int page() { return Ui.dark ? Ui.SURFACE : Ui.PANEL; }
    static int card() { return Ui.dark ? Ui.PANEL : Ui.SURFACE; }
    static int tint() { return blend(card(), Ui.ACCENT, Ui.dark ? .15f : .09f); }
    static int line() { return blend(card(), Ui.MUTED, .14f); }
    static int radius(int normal) {return switch(Ui.material){case PAPER->Math.min(8,normal);case TIDE->Math.min(14,normal);default->normal;};}
    static int blend(int base, int color, float amount) {
        return Ui.blend(base, color, amount);
    }
    static RippleDrawable ripple(Context context, int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(Ui.rippleColor()),
                Ui.background(color, Ui.dp(context, radius)), Ui.background(Color.WHITE, Ui.dp(context, radius)));
    }
    static LinearLayout card(Context context) {
        LinearLayout view = Ui.column(context);
        view.setBackground(Ui.material.card(context,card(),radius(20)));
        view.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(context, 16);
        view.setLayoutParams(params);
        return view;
    }
    static TextView label(Context context, String text) {
        TextView label = Ui.text(context, text, 12, Ui.MUTED);
        label.setTypeface(null, Typeface.BOLD);
        label.setLetterSpacing(Ui.material==ThemeMaterial.CLASSIC?.04f:0f);
        if(Ui.material!=ThemeMaterial.CLASSIC){
            label.setTextColor(Ui.ACCENT);
            android.graphics.drawable.GradientDrawable mark=Ui.background(Ui.ACCENT,Ui.dp(context,2));
            mark.setBounds(0,0,Ui.dp(context,3),Ui.dp(context,13));label.setCompoundDrawablesRelative(mark,null,null,null);
            label.setCompoundDrawablePadding(Ui.dp(context,8));
        }
        label.setPadding(Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 10));
        if (android.os.Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        return label;
    }
    static ImageView icon(Context context, int drawable) {
        ImageView icon = Ui.image(context, drawable, Ui.ACCENT);
        icon.setPadding(Ui.dp(context, 10), Ui.dp(context, 10), Ui.dp(context, 10), Ui.dp(context, 10));
        icon.setBackground(Ui.material==ThemeMaterial.CLASSIC?Ui.background(tint(),Ui.dp(context,13)):Ui.material.card(context,tint(),radius(13)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }
    static ImageView chevron(Context context) {
        ImageView arrow = Ui.image(context, R.drawable.ic_chevron_down, Ui.MUTED);
        arrow.setRotation(-90);
        arrow.setAlpha(.65f);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return arrow;
    }
    static void separator(LinearLayout card, int inset) {
        Context context = card.getContext();
        View line = new View(context);
        line.setBackgroundColor(line());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(context, 1)));
        params.leftMargin = Ui.dp(context, inset);
        params.rightMargin = Ui.dp(context, 16);
        card.addView(line, params);
    }
    static boolean themed() { return Ui.material != ThemeMaterial.CLASSIC; }
    static void chrome(android.app.Activity activity, LinearLayout root, LinearLayout toolbar) {
        boolean ink=Ui.material==ThemeMaterial.TIDE||Ui.material==ThemeMaterial.PAPER;
        ThemeMaterial.paint(root,ThemeMaterial.Surface.PAGE);
        if(ink){ThemeMaterial.paint(toolbar,ThemeMaterial.Surface.HEADER);ThemeMaterial.paint(root.getChildAt(0),ThemeMaterial.Surface.HEADER);}
        else{toolbar.setBackgroundColor(Color.TRANSPARENT);root.getChildAt(0).setBackgroundColor(Color.TRANSPARENT);}
        ((TextView)toolbar.getChildAt(1)).setTextColor(ink?Color.WHITE:Ui.TEXT);
        for(int i=0;i<toolbar.getChildCount();i++)if(toolbar.getChildAt(i) instanceof ImageView icon){
            icon.setImageTintList(ColorStateList.valueOf(ink?Color.WHITE:Ui.TEXT));
            if(Ui.material==ThemeMaterial.FROST){
                icon.setBackground(new android.graphics.drawable.InsetDrawable(FrostLensDrawable.button(activity,Ui.dark),Ui.dp(activity,5)));
                icon.setForeground(ripple(activity,Color.TRANSPARENT,25));
            }else{icon.setBackground(Ui.ripple(Color.TRANSPARENT));icon.setForeground(null);}
        }
        var window=activity.getWindow();window.setStatusBarColor(ink?Ui.INK:page());window.setNavigationBarColor(page());
        window.getDecorView().setSystemUiVisibility(Ui.dark?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR|(ink?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
    }
    static LinearLayout form(Context c) {
        LinearLayout form = Ui.column(c);
        form.setPadding(Ui.dp(c,20),Ui.dp(c,8),Ui.dp(c,20),Ui.dp(c,4));
        return form;
    }
    static EditText field(Context c, String hint) {
        EditText input = Ui.input(c,hint);
        styleField(input);return input;
    }
    static void styleField(EditText input) {
        if (themed()) {
            Context c=input.getContext();
            int corners=radius(12);
            GradientDrawable focus=Ui.material.card(c,card(),corners);
            focus.setStroke(Ui.dp(c,2),Ui.ACCENT);
            StateListDrawable states=new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused},focus);
            states.addState(new int[0],Ui.material.card(c,card(),corners));
            input.setBackgroundTintList(null);input.setBackground(states);
            input.setPadding(Ui.dp(c,12),Ui.dp(c,9),Ui.dp(c,12),Ui.dp(c,9));
        }
    }
    static View framedField(EditText field) {
        if (!themed()) return field;
        LinearLayout form=form(field.getContext());form.addView(field);return form;
    }
    static SeekBar slider(Context c, String title) {
        SeekBar bar=new SeekBar(c);bar.setContentDescription(title);bar.setMinimumHeight(Ui.dp(c,48));
        if (themed()) {
            bar.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
            bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        }
        return bar;
    }
    static Drawable glyph(Context c, int resource, int color, int size) {
        Drawable glyph=c.getDrawable(resource).mutate();glyph.setTint(color);
        glyph.setBounds(0,0,Ui.dp(c,size),Ui.dp(c,size));return glyph;
    }
    static Button command(Context c, String title, int icon) {
        Button action=new Button(c);action.setText(title);action.setMinimumHeight(Ui.dp(c,48));
        if (themed()) {
            action.setStateListAnimator(null);action.setAllCaps(false);action.setLetterSpacing(0);action.setTextSize(15);
            action.setTextColor(Ui.TEXT);action.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            action.setBackgroundTintList(null);action.setBackground(ripple(c,Color.TRANSPARENT,0));
            action.setCompoundDrawablesRelative(glyph(c,icon,Ui.ACCENT,21),null,null,null);
            action.setCompoundDrawablePadding(Ui.dp(c,14));action.setPadding(Ui.dp(c,16),Ui.dp(c,9),Ui.dp(c,16),Ui.dp(c,9));
            action.setMinimumHeight(Ui.dp(c,54));
        }
        return action;
    }
    static ArrayAdapter<String> sortRows(ListView list, List<String> names) {
        Context c=list.getContext();ArrayAdapter<String> adapter;
        if (!themed()) adapter=new ArrayAdapter<>(c,android.R.layout.simple_list_item_1,names);
        else {
            list.setBackgroundColor(Color.TRANSPARENT);list.setCacheColorHint(Color.TRANSPARENT);
            list.setDivider(new ColorDrawable(line()));list.setDividerHeight(Ui.dp(c,1));
            list.setSelector(ripple(c,Color.TRANSPARENT,0));list.setPadding(Ui.dp(c,12),0,Ui.dp(c,12),0);
            adapter=new ArrayAdapter<>(c,android.R.layout.simple_list_item_1,names){
                @Override public View getView(int position, View convertView, ViewGroup parent){
                    TextView row=(TextView)super.getView(position,convertView,parent);
                    if(convertView==null){
                        row.setTextSize(15);row.setTextColor(Ui.TEXT);row.setMinHeight(Ui.dp(c,52));
                        row.setCompoundDrawablesRelative(null,null,glyph(c,R.drawable.ic_menu,Ui.MUTED,16),null);
                        row.setCompoundDrawablePadding(Ui.dp(c,12));
                    }
                    return row;
                }
            };
        }
        list.setAdapter(adapter);return adapter;
    }
    static void enter(View view, int direction) {
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationX(0f);
        if (!Ui.motionEnabled()) return;
        view.setAlpha(.65f);
        view.setTranslationX(Ui.dp(view.getContext(), 10) * Integer.signum(direction));
        view.animate().alpha(1f).translationX(0f).setDuration(Ui.MOTION_PANEL).setInterpolator(Ui.EASING).start();
    }
    private SettingsDesign() { }
}
