package org.example.filemanager;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.util.function.Consumer;

/** A floating panel gets a real compositor blur when available, otherwise an opaque surface. */
final class GlassWindow {
    static Dialog dialog(Activity a) {
        if (Ui.material!=ThemeMaterial.FROST) return new Dialog(a);
        return new Dialog(a,Ui.dark?R.style.GlassPanelDark:R.style.GlassPanelLight);
    }
    static void install(Window window, View panel, int radiusDp, boolean drawer) {
        Context c=panel.getContext(); int radius=Ui.dp(c,radiusDp);
        GradientDrawable fill=Ui.material.card(c,SettingsDesign.card(),radiusDp);
        GradientDrawable clip=Ui.background(Color.TRANSPARENT,radius);
        if(drawer){float[] corners={0,0,radius,radius,radius,radius,0,0}; fill.setCornerRadii(corners);clip.setCornerRadii(corners);}
        window.setBackgroundDrawable(fill);
        panel.setBackground(clip);panel.setClipToOutline(true);
        if(Build.VERSION.SDK_INT>=31) Api31.bind(window,fill,Ui.dp(c,18),190);
    }

    static void installOverflow(Window window, View panel) {
        Context c=panel.getContext();int radius=Ui.dp(c,27);
        int[] tones=Ui.dark ? new int[]{0xff343a41,0xff191e25,0xff2b323a}
                : new int[]{0xffffffff,0xfff0f4f7,0xfffafcfd};
        GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.TL_BR,tones);
        fill.setCornerRadius(radius);fill.setStroke(Ui.dp(c,1),Ui.dark?0xff8795a1:Color.WHITE);fill.setDither(true);
        window.setBackgroundDrawable(fill);
        GradientDrawable rim=Ui.background(Color.TRANSPARENT,radius);
        rim.setStroke(Ui.dp(c,1),Ui.dark?0x50ffffff:0xb5ffffff);
        panel.setBackground(Ui.background(Color.TRANSPARENT,radius));panel.setForeground(rim);panel.setClipToOutline(true);
        if(Build.VERSION.SDK_INT>=31) Api31.bind(window,fill,Ui.dp(c,14),Ui.dark?179:157);
    }
    @androidx.annotation.RequiresApi(31)
    private static final class Api31 {
        static void bind(Window window,GradientDrawable fill,int radius,int blurAlpha){
            View decor=window.getDecorView(); WindowManager manager=window.getWindowManager();
            if(manager==null)return;
            decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                private boolean listening;
                private final Consumer<Boolean> update=enabled->{
                    if(!listening)return;
                    fill.setAlpha(enabled?blurAlpha:255);
                    window.setBackgroundBlurRadius(enabled?radius:0);
                };
                @Override public void onViewAttachedToWindow(View v){
                    listening=true; update.accept(manager.isCrossWindowBlurEnabled());
                    manager.addCrossWindowBlurEnabledListener(v.getContext().getMainExecutor(),update);
                }
                @Override public void onViewDetachedFromWindow(View v){
                    listening=false; manager.removeCrossWindowBlurEnabledListener(update);
                }
            });
        }
    }
    private GlassWindow() { }
}
