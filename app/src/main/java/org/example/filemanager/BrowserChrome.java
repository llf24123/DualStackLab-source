package org.example.filemanager;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.function.Consumer;

/** Lays the same working controls in the normal bars or above the panes as floating glass. */
@android.annotation.SuppressLint("ViewConstructor") // Created with its pane and toolbar, never inflated from XML.
final class BrowserChrome extends FrameLayout {
    interface Insets { void set(int top,int bottom); }
    private final Activity host;
    private final LinearLayout panes, normal, path, permission, actions;
    private final FrameLayout header;
    private final TextView title,subtitle,status;
    private final FrostBackdrop topSample,bottomSample;
    private final View divider,leftEdge,rightEdge;
    private final int normalHeight;
    private final Insets listInsets;
    private View statusGuard;
    private InlineFilterBar filter;
    private boolean frost,leftActive=true,watching;
    private int gap;
    private Consumer<Boolean> blurListener;

    BrowserChrome(Activity a, LinearLayout panes, FrameLayout header, LinearLayout normal, LinearLayout path,
                  TextView title,TextView subtitle,LinearLayout permission,TextView status,LinearLayout actions,
                  int normalHeight,Insets insets) {
        super(a);host=a;this.panes=panes;this.header=header;this.normal=normal;this.path=path;
        this.title=title;this.subtitle=subtitle;this.permission=permission;this.status=status;this.actions=actions;
        this.normalHeight=normalHeight;this.listInsets=insets;
        setClipChildren(false);setClipToPadding(false);header.setClipChildren(false);header.setClipToPadding(false);
        normal.setClipChildren(false);normal.setClipToPadding(false);
        topSample=new FrostBackdrop(a,panes,true);bottomSample=new FrostBackdrop(a,panes,false);
        divider=Ui.line(a);leftEdge=new View(a);rightEdge=new View(a);
        for(View v:new View[]{panes,topSample,bottomSample,leftEdge,rightEdge,header,permission,status,divider,actions})addView(v,new LayoutParams(-1,-2));
        setOnApplyWindowInsetsListener((v,i)->{int next=FileManagerSettings.gestureGap(a,i);if(next!=gap){gap=next;if(!frost)actions.setPadding(0,0,0,gap);requestLayout();}return i;});
    }
    private int dp(int n){return Ui.dp(getContext(),n);}
    private int headerSize(){return frost?Math.max(dp(64),normalHeight+dp(6)):normalHeight;}
    private int dockSize(){return frost?Math.max(dp(54),Math.round(dp(35)+Ui.dp(getContext(),17)*getResources().getConfiguration().fontScale)):dp(50)+gap;}
    private int bottomMargin(){return frost?Math.max(dp(8),gap):0;}
    private void measure(View v,int w,int h){v.measure(MeasureSpec.makeMeasureSpec(Math.max(0,w),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,h),MeasureSpec.EXACTLY));}
    @Override protected void onMeasure(int ws,int hs){
        int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h);
        int head=headerSize(),dock=dockSize(),b=bottomMargin();
        int perm=permission.getVisibility()==GONE?0:dp(48),msg=status.getVisibility()==GONE?0:dp(48);
        int top=frost&&perm==0?0:head+perm, bottom=frost&&msg==0?0:dock+b+msg+(frost?0:dp(1));
        int gutter=frost?Math.max(0,Math.min(dp(16),(w-5*dp(48)-dp(6))/2)):0;
        listInsets.set(top==0&&frost?head:0,bottom==0&&frost?dock+b+dp(6):0);
        measure(panes,w,h-top-bottom);measure(header,w,head);measure(permission,w,perm);measure(status,w,msg);
        measure(actions,w-2*gutter,dock);measure(divider,w,dp(1));measure(topSample,w,head);measure(bottomSample,w,dock);
        measure(leftEdge,w/2,dp(2));measure(rightEdge,w-w/2,dp(2));
    }
    private void place(View v,int x,int y){v.layout(x,y,x+v.getMeasuredWidth(),y+v.getMeasuredHeight());}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int w=r-l,h=b-t,head=header.getMeasuredHeight(),dockY=h-actions.getMeasuredHeight()-bottomMargin();
        int perm=permission.getMeasuredHeight(),msg=status.getMeasuredHeight();
        place(panes,0,frost&&perm==0?0:head+perm);place(header,0,0);place(topSample,0,0);place(permission,0,head);
        place(leftEdge,0,head-dp(2));place(rightEdge,w/2,head-dp(2));
        place(status,0,dockY-msg-(frost?0:dp(1)));place(divider,0,dockY-dp(1));
        place(actions,(w-actions.getMeasuredWidth())/2,dockY);place(bottomSample,0,dockY);
        if(changed)refreshGlass();
    }
    void apply(View guard){
        if(guard!=null)statusGuard=guard;
        frost=Ui.material==ThemeMaterial.FROST;int ink=frost?Ui.TEXT:Color.WHITE;
        if(frost)header.setBackgroundColor(Color.TRANSPARENT);else ThemeMaterial.paint(header,ThemeMaterial.Surface.HEADER);
        title.setTextColor(ink);subtitle.setTextColor(frost?Ui.MUTED:Ui.material==ThemeMaterial.CLASSIC?0xffc0d0cd:0xffe3ecef);
        if(statusGuard!=null){if(frost)statusGuard.setBackgroundColor(Ui.SURFACE);else ThemeMaterial.paint(statusGuard,ThemeMaterial.Surface.HEADER);}
        host.getWindow().setStatusBarColor(frost?Ui.SURFACE:Ui.INK);
        int flags=host.getWindow().getDecorView().getSystemUiVisibility();
        host.getWindow().getDecorView().setSystemUiVisibility(frost&&!Ui.dark?flags|SYSTEM_UI_FLAG_LIGHT_STATUS_BAR:flags&~SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        normal.setPadding(frost?dp(12):0,0,frost?dp(12):0,0);
        for(int i=0;i<normal.getChildCount();i++){
            View v=normal.getChildAt(i);boolean label=v==path;
            LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.gravity=Gravity.CENTER_VERTICAL;
            p.height=frost?(label?headerSize()-dp(12):dp(48)):(label?-2:dp(i==0?58:54));
            p.setMarginStart(frost&&label?dp(8):0);p.setMarginEnd(frost&&label?dp(8):0);v.setLayoutParams(p);
            if(label){path.setGravity(Gravity.CENTER_VERTICAL);path.setPadding(frost?dp(14):0,0,frost?dp(12):0,0);}
            else ((ImageView)v).setImageTintList(ColorStateList.valueOf(ink));
            if(frost){v.setBackground(label?FrostLensDrawable.header(host,Ui.dark):FrostLensDrawable.button(host,Ui.dark));v.setForeground(feedback(host,false));}
            else{v.setBackground(label?null:Ui.ripple(Color.TRANSPARENT));v.setForeground(null);}
            lift(v,frost?(label?4:3):0);
        }
        int side=frost?dp(3):0;actions.setPadding(side,0,side,frost?0:gap);actions.setClipToOutline(frost);
        if(frost)actions.setBackground(FrostLensDrawable.dock(host,Ui.dark));else ThemeMaterial.paint(actions,ThemeMaterial.Surface.FOOTER);
        lift(actions,frost?7:0);
        for(int i=0;i<actions.getChildCount();i++)command(actions.getChildAt(i));
        topSample.setVisibility(frost?VISIBLE:GONE);bottomSample.setVisibility(frost?VISIBLE:GONE);
        if(!frost){topSample.setBlurAllowed(false);bottomSample.setBlurAllowed(false);}
        divider.setVisibility(frost?GONE:VISIBLE);leftEdge.setVisibility(frost?VISIBLE:GONE);rightEdge.setVisibility(frost?VISIBLE:GONE);
        leftEdge.setBackgroundColor(leftActive?Ui.ACCENT:Ui.LINE);rightEdge.setBackgroundColor(leftActive?Ui.LINE:Ui.ACCENT);
        filter(filter);bottomSample.track(actions);observeCapability();
        requestLayout();refreshGlass();
    }
    private void lift(View v,int height){v.setElevation(dp(height));if(Build.VERSION.SDK_INT>=28){v.setOutlineAmbientShadowColor(0xff56646d);v.setOutlineSpotShadowColor(0xff4d5d68);}}
    void active(boolean left){leftActive=left;if(frost){Ui.fadeBackground(leftEdge,left?Ui.ACCENT:Ui.LINE);Ui.fadeBackground(rightEdge,left?Ui.LINE:Ui.ACCENT);}}
    void filter(InlineFilterBar next){filter=next;if(next!=null){
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)next.getLayoutParams();int x=frost?dp(12):0,y=frost?dp(6):0;
        p.setMargins(x,y,x,y);next.setLayoutParams(p);next.applyChrome(frost);next.setBackground(frost?FrostLensDrawable.header(host,Ui.dark):null);lift(next,frost?4:0);
    }
        if(next==null)topSample.track(normal.getChildAt(0),path,normal.getChildAt(2));else topSample.track(next);
        refreshGlass();
    }
    static void command(View v){
        commandFeedback(v,v.isSelected());
        if(v instanceof LinearLayout row && row.getChildCount()==2){
            ((ImageView)row.getChildAt(0)).setImageTintList(ColorStateList.valueOf(Ui.material!=ThemeMaterial.CLASSIC?Ui.ACCENT:Ui.TEXT));
            ((TextView)row.getChildAt(1)).setTextColor(Ui.TEXT);
        }
    }
    static void commandFeedback(View v,boolean on){
        v.setBackground(Ui.material==ThemeMaterial.FROST?feedback(v.getContext(),on):Ui.ripple(on?Ui.TINT:Color.TRANSPARENT));
    }
    private static RippleDrawable feedback(Context c,boolean on){
        int rad=Ui.dp(c,32);Drawable fill=on?Ui.background(Ui.TINT,rad):null;
        return new RippleDrawable(ColorStateList.valueOf(Ui.rippleColor()),fill,Ui.background(Color.WHITE,rad));
    }
    void refreshGlass(){if(frost){topSample.refresh();bottomSample.refresh();}}
    private void observeCapability(){
        if(!isAttachedToWindow()||Build.VERSION.SDK_INT<31){if(frost){topSample.setBlurAllowed(false);bottomSample.setBlurAllowed(false);}return;}
        if(frost&&!watching){watching=true;blurListener=on->{if(watching&&frost){topSample.setBlurAllowed(on);bottomSample.setBlurAllowed(on);}};Capability.listen(host,blurListener);}
        else if(!frost&&watching)stopWatching();
    }
    @androidx.annotation.RequiresApi(31)
    private static class Capability{
        static void listen(Activity a,Consumer<Boolean> listener){WindowManager wm=a.getWindowManager();listener.accept(wm.isCrossWindowBlurEnabled());wm.addCrossWindowBlurEnabledListener(a.getMainExecutor(),listener);}
        static void remove(Activity a,Consumer<Boolean> listener){a.getWindowManager().removeCrossWindowBlurEnabledListener(listener);}
    }
    private void stopWatching(){if(watching&&Build.VERSION.SDK_INT>=31){watching=false;Capability.remove(host,blurListener);blurListener=null;}}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();observeCapability();}
    @Override protected void onDetachedFromWindow(){stopWatching();super.onDetachedFromWindow();}
}
