package org.example.filemanager;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** An anchored window: the compositor blurs the files underneath its surface only. */
final class FrostMoreMenu {
    private static final int[][] SECTIONS={
            {0,1,2,4,5,6,7,8,13,9,10}, {3,14,15,16,17,18}, {11,12}
    };
    private static final int[][] STATES={{android.R.attr.state_pressed},{android.R.attr.state_focused},
            {android.R.attr.state_hovered},{}};

    static Dialog show(Activity a,View anchor,String[] names,int[] icons,IntConsumer action) {
        Dialog dialog=GlassWindow.dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout menu=Ui.column(a);int inset=Ui.dp(a,6);menu.setPadding(inset,inset,inset,inset);
        for(int section=0;section<SECTIONS.length;section++){
            if(section>0){
                View divider=new View(a);divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                divider.setBackgroundColor(Ui.dark?0x35ffffff:0x31263642);
                LinearLayout.LayoutParams rule=new LinearLayout.LayoutParams(-1,Math.max(1,Ui.dp(a,1)));
                rule.setMargins(Ui.dp(a,16),Ui.dp(a,4),Ui.dp(a,16),Ui.dp(a,4));menu.addView(divider,rule);
            }
            for(int which:SECTIONS[section])if(which<names.length){
                View item=item(a,names[which],icons[which]);menu.addView(item);
                item.setOnClickListener(v->{dialog.dismiss();action.accept(which);});
            }
        }
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(false);scroll.setClipToPadding(false);
        scroll.setContentDescription("更多操作列表");scroll.addView(menu);
        if(Build.VERSION.SDK_INT>=28)scroll.setAccessibilityPaneTitle("更多操作");
        dialog.setContentView(scroll);Window w=dialog.getWindow();if(w==null)return dialog;
        w.setTitle("更多操作");w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        w.setDimAmount(0f);w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setElevation(Ui.dp(a,12));w.setWindowAnimations(Ui.motionEnabled()?R.style.FrostOverflowMotion:0);
        GlassWindow.installOverflow(w,scroll);place(w,anchor,menu);
        dialog.show();return dialog;
    }

    @android.annotation.SuppressLint("RtlHardcoded") // Window x is screen-relative; START/END is resolved below.
    private static void place(Window window,View anchor,LinearLayout menu) {
        Context c=anchor.getContext();Rect visible=new Rect();anchor.getWindowVisibleDisplayFrame(visible);
        int[] xy=new int[2];anchor.getLocationOnScreen(xy);
        int edge=Ui.dp(c,8);
        int width=Math.min(Math.max(1,visible.width()-2*edge),Math.min(Ui.dp(c,328),
                Math.max(Ui.dp(c,204),Math.round(visible.width()*.61f))));
        int x=anchor.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL?xy[0]:xy[0]+anchor.getWidth()-width;
        x=Math.max(visible.left+edge,Math.min(x,visible.right-edge-width));
        int y=Math.max(visible.top,xy[1]+anchor.getHeight()+Ui.dp(c,3));
        menu.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int below=visible.bottom-edge-y,above=xy[1]-visible.top-edge;
        if(visible.height()>=Ui.dp(c,480))below-=Ui.dp(c,64);
        boolean placeAbove=below<Ui.dp(c,96)&&above>below;
        int height=Math.max(1,Math.min(menu.getMeasuredHeight(),placeAbove?above:below));
        if(placeAbove)y=xy[1]-height;
        WindowManager.LayoutParams p=window.getAttributes();p.gravity=Gravity.TOP|Gravity.LEFT;
        p.x=x-visible.left;p.y=Math.max(0,y-visible.top);p.width=width;p.height=height;window.setAttributes(p);
    }

    private static View item(Context c,String title,int drawable) {
        LinearLayout row=Ui.row(c);int radius=Ui.dp(c,26);
        row.setMinimumHeight(Ui.dp(c,48));row.setPaddingRelative(Ui.dp(c,14),Ui.dp(c,7),Ui.dp(c,12),Ui.dp(c,7));
        row.setFocusable(true);row.setContentDescription(title);row.setBackground(pressSurface(c,radius));
        ColorStateList colors=new ColorStateList(STATES,new int[]{Ui.ACCENT,Ui.ACCENT,Ui.ACCENT,Ui.TEXT});
        ImageView icon=Ui.image(c,drawable,Ui.TEXT);icon.setImageTintList(colors);icon.setDuplicateParentStateEnabled(true);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(icon,new LinearLayout.LayoutParams(Ui.dp(c,22),Ui.dp(c,22)));
        TextView label=Ui.text(c,title,16,Ui.TEXT);label.setTextColor(colors);label.setDuplicateParentStateEnabled(true);
        label.setPaddingRelative(Ui.dp(c,12),0,0,0);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));return row;
    }

    private static RippleDrawable pressSurface(Context c,int radius){
        int[] colors=Ui.dark?new int[]{0xee414d59,0xd736444f,0xbd293641}:new int[]{0xe6f2f6fb,0xdbd8e1e9,0xc4e2e8ed};
        StateListDrawable states=new StateListDrawable();
        for(int i=0;i<STATES.length-1;i++){
            GradientDrawable active=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);
            active.setCornerRadius(radius);active.setStroke(Ui.dp(c,1),Ui.dark?0x8ab2bfca:0xe6ffffff);
            states.addState(STATES[i],active);
        }
        states.addState(STATES[3],new ColorDrawable(Color.TRANSPARENT));
        return new RippleDrawable(ColorStateList.valueOf(Ui.rippleColor()),states,Ui.background(Color.WHITE,radius));
    }
    private FrostMoreMenu() { }
}
