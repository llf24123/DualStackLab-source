package org.example.filemanager;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.view.Window;
import android.widget.EditText;

/** A single platform alert skin, including alerts opened two or three menus deep. */
final class MaterialDialogs {
    static AlertDialog.Builder builder(Context context) {
        if(Ui.material==ThemeMaterial.CLASSIC)return new AlertDialog.Builder(context);
        AlertDialog.Builder builder=new AlertDialog.Builder(context,Ui.material.alertTheme(Ui.dark)){
            @Override public AlertDialog.Builder setView(View view){
                if(!(view instanceof EditText input))return super.setView(view);
                SettingsDesign.styleField(input);return super.setView(SettingsDesign.framedField(input));
            }
            @Override public AlertDialog create(){
                AlertDialog alert=super.create();Window window=alert.getWindow();if(window==null)return alert;
                Context c=getContext();int radius=switch(Ui.material){case FROST->23;case PAPER->10;default->16;};
                int inset=Ui.dp(c,12);int base=Ui.dark?Ui.PANEL:Ui.SURFACE;
                GradientDrawable card=Ui.material.card(c,base,radius);
                window.setBackgroundDrawable(new InsetDrawable(card,inset,Ui.dp(c,5),inset,Ui.dp(c,5)));
                window.setElevation(Ui.dp(c,9));window.setDimAmount(Ui.dark?.48f:.28f);
                View decor=window.getDecorView();decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                    @Override public void onViewAttachedToWindow(View v){
                        ColorStateList text=new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[0]},
                                new int[]{Ui.blend(base,Ui.MUTED,.68f),Ui.ACCENT});
                        for(int which:new int[]{-1,-2,-3}){var b=alert.getButton(which);if(b!=null)b.setTextColor(text);}
                        if(alert.getListView()!=null)alert.getListView().setCacheColorHint(Color.TRANSPARENT);
                        v.removeOnAttachStateChangeListener(this);
                    }
                    @Override public void onViewDetachedFromWindow(View v){}
                });return alert;
            }
        };
        if(Ui.accentIndex>=0)builder.getContext().getTheme().applyStyle(LabActivity.accentOverlay(Ui.dark,Ui.accentIndex),true);
        return builder;
    }
    private MaterialDialogs(){}
}
