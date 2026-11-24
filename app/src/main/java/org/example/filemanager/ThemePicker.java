package org.example.filemanager;

import android.app.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.EnumMap;

/** Stages each material's icon choice along with the mode; only Apply writes preferences. */
public final class ThemePicker extends DialogFragment {
    private static final String TAG="appearance-picker";
    private static final String[] MODES={"light","dark","system"};
    private static final String[] LABELS={"浅色","深色","跟随系统"};
    private ThemeMaterial selected;
    private String mode;
    private final TextView[] buttons=new TextView[MODES.length];
    private final ThemeMaterial[] styles=ThemeMaterial.values();
    private final LinearLayout[] options=new LinearLayout[styles.length];
    private final Sample[] samples=new Sample[styles.length];
    private final ImageView[] ticks=new ImageView[styles.length];
    private final EnumMap<ThemeMaterial,IconStyle> icons=new EnumMap<>(ThemeMaterial.class);
    private final IconStyle[] choices=IconStyle.values();
    private final TextView[] iconButtons=new TextView[choices.length];
    private ScrollView scroll;
    private TextView iconHeading,iconNote;
    private LinearLayout iconOptions,iconPreview;
    private ImageView previewFolder,previewFile;

    static String modeLabel(String mode) {
        return "dark".equals(mode)?LABELS[1]:"light".equals(mode)?LABELS[0]:LABELS[2];
    }
    static void show(Activity a) { show(a,false); }
    static void showIcons(Activity a) { show(a,true); }
    private static void show(Activity a,boolean focusIcons) {
        FragmentManager fm=a.getFragmentManager();
        if (!a.isFinishing() && !fm.isStateSaved() && fm.findFragmentByTag(TAG)==null) {
            ThemePicker picker=new ThemePicker();Bundle args=new Bundle();args.putBoolean("focus-icons",focusIcons);
            picker.setArguments(args);picker.show(fm,TAG);
        }
    }
    @Override public Dialog onCreateDialog(Bundle state) {
        Activity a=getActivity();
        mode=state==null?Ui.themeMode(a):state.getString("mode",Ui.themeMode(a));
        selected=state==null?null:ThemeMaterial.fromId(state.getString("material"));
        if(selected==null)selected=ThemeMaterial.current(a);
        for(ThemeMaterial m:styles){IconStyle staged=state==null?null:IconStyle.fromId(state.getString(m.id+"-icons"));
            icons.put(m,m==ThemeMaterial.CLASSIC?IconStyle.FOLLOW:staged==null?IconStyle.forMaterial(a,m):staged);}
        LinearLayout body=Ui.column(a); body.setPadding(dp(18),dp(2),dp(18),dp(8));
        body.addView(heading("明暗模式"));
        LinearLayout modes=Ui.row(a); modes.setPadding(dp(3),dp(3),dp(3),dp(3));
        modes.setBackground(Ui.background(Ui.PANEL,dp(8))); body.addView(modes);
        for(int i=0;i<MODES.length;i++) {
            String id=MODES[i]; TextView b=Ui.text(a,LABELS[i],14,Ui.TEXT); buttons[i]=b;
            b.setGravity(Gravity.CENTER); b.setMinHeight(dp(48)); b.setPadding(dp(2),dp(4),dp(2),dp(4));
            choice(b); b.setOnClickListener(v->{mode=id;refresh();}); modes.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        }
        body.addView(heading("界面材质"));
        Configuration cfg=getResources().getConfiguration();
        // Short, wide windows can display all four materials beside each other.
        boolean wideStrip=cfg.screenHeightDp<500 && cfg.screenWidthDp>=760 && cfg.fontScale<=1.15f;
        int columns=wideStrip?4:cfg.screenWidthDp<330 || cfg.fontScale>=1.5f ? 1:2;
        LinearLayout pair=null;
        for(int i=0;i<styles.length;i++) {
            ThemeMaterial style=styles[i];
            if(i%columns==0) {pair=Ui.row(a);pair.setGravity(Gravity.TOP);body.addView(pair);}
            LinearLayout item=Ui.column(a); options[i]=item; item.setPadding(dp(7),dp(7),dp(7),dp(5));
            choice(item); item.setContentDescription("材质："+style.title);
            Sample sample=new Sample(a,style); samples[i]=sample; item.addView(sample,new LinearLayout.LayoutParams(-1,dp(wideStrip?34:57)));
            LinearLayout caption=Ui.row(a);caption.setGravity(Gravity.CENTER_VERTICAL);caption.setMinimumHeight(dp(34));
            TextView name=Ui.text(a,style.title,14,Ui.TEXT);name.setTypeface(null,Typeface.BOLD);
            caption.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            ImageView tick=Ui.image(a,R.drawable.ic_check,Ui.ACCENT);ticks[i]=tick;caption.addView(tick,new LinearLayout.LayoutParams(dp(17),dp(17)));
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);item.addView(caption);
            item.setOnClickListener(v->{selected=style;refresh();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.bottomMargin=dp(8);
            if(i%columns!=0)p.setMarginStart(dp(wideStrip?5:8));pair.addView(item,p);
        }
        iconHeading=heading("文件图标样式");body.addView(iconHeading);
        LinearLayout sampleRow=Ui.row(a);sampleRow.setGravity(Gravity.CENTER_VERTICAL);
        iconPreview=Ui.row(a);iconPreview.setGravity(Gravity.CENTER);iconPreview.setPadding(dp(5),dp(4),dp(5),dp(4));
        previewFolder=previewIcon(a);previewFile=previewIcon(a);iconPreview.addView(previewFolder,new LinearLayout.LayoutParams(dp(30),dp(30)));
        LinearLayout.LayoutParams nextIcon=new LinearLayout.LayoutParams(dp(30),dp(30));nextIcon.setMarginStart(dp(7));iconPreview.addView(previewFile,nextIcon);
        iconPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);sampleRow.addView(iconPreview);
        iconNote=Ui.text(a,"",13,Ui.MUTED);iconNote.setPadding(dp(10),0,0,0);sampleRow.addView(iconNote,new LinearLayout.LayoutParams(0,-2,1));body.addView(sampleRow);
        iconOptions=Ui.column(a);iconOptions.setPadding(0,dp(7),0,0);body.addView(iconOptions);
        int iconColumns=cfg.screenWidthDp<380 || cfg.fontScale>=1.25f ? 2:4;
        if(cfg.fontScale>=1.8f && cfg.screenWidthDp<360)iconColumns=1;
        LinearLayout row=null;
        for(int i=0;i<choices.length;i++){
            if(i%iconColumns==0){row=Ui.row(a);row.setGravity(Gravity.TOP);iconOptions.addView(row);}
            IconStyle choice=choices[i];TextView b=Ui.text(a,choice.title,14,Ui.TEXT);iconButtons[i]=b;
            b.setMinHeight(dp(48));b.setPadding(dp(2),dp(5),dp(2),dp(5));b.setGravity(Gravity.CENTER);
            b.setContentDescription("图标样式："+choice.title);choice(b);
            b.setOnClickListener(v->{if(selected!=ThemeMaterial.CLASSIC){icons.put(selected,choice);refresh();}});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.bottomMargin=dp(4);
            if(i%iconColumns!=0)p.setMarginStart(dp(4));row.addView(b,p);
        }
        scroll=new ScrollView(a);scroll.addView(body);refresh();
        boolean focusIcons=getArguments()!=null && getArguments().getBoolean("focus-icons");
        int lastY=state==null?-1:state.getInt("scroll",-1);
        if(lastY>=0 || focusIcons)scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){
            @Override public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){
                v.removeOnLayoutChangeListener(this); scroll.scrollTo(0,lastY>=0?lastY:iconHeading.getTop());
            }
        });
        return MaterialDialogs.builder(a).setTitle("主题外观").setView(scroll)
                .setNegativeButton("取消",null).setPositiveButton("应用",(d,w)->{
                    boolean changed=!mode.equals(Ui.themeMode(a)) || selected!=ThemeMaterial.current(a);
                    SharedPreferences.Editor edit=a.getSharedPreferences("appearance",0).edit();
                    for(ThemeMaterial m:styles)if(m!=ThemeMaterial.CLASSIC&&icons.get(m)!=IconStyle.forMaterial(a,m)){
                        IconStyle.write(edit,m,icons.get(m));changed=true;
                    }
                    if(!changed)return;edit.putString("mode",mode).putString("material",selected.id).apply();
                    if(a instanceof LabActivity lab) lab.applyAppearance(); else a.recreate();
                }).create();
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);out.putString("mode",mode);if(selected!=null)out.putString("material",selected.id);
        if(scroll!=null)out.putInt("scroll",scroll.getScrollY());
        icons.forEach((m,v)->out.putString(m.id+"-icons",v.id));
    }
    private ImageView previewIcon(Context a){ImageView v=new ImageView(a);v.setScaleType(ImageView.ScaleType.FIT_CENTER);return v;}
    private int dp(int n) { return Ui.dp(getActivity(),n); }
    private TextView heading(String title) {
        TextView v=Ui.text(getActivity(),title,13,Ui.MUTED);v.setTypeface(null,Typeface.BOLD);
        v.setPadding(0,dp(16),0,dp(8));if(android.os.Build.VERSION.SDK_INT>=28)v.setAccessibilityHeading(true);return v;
    }
    private void choice(View v) {
        v.setFocusable(true);v.setForeground(SettingsDesign.ripple(getActivity(),Color.TRANSPARENT,8));
        v.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(RadioButton.class.getName());
                info.setCheckable(true);info.setChecked(host.isSelected());
            }
        });
    }
    private void refresh() {
        boolean night="dark".equals(mode) || ("system".equals(mode) &&
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        for(int i=0;i<buttons.length;i++){
            boolean checked=MODES[i].equals(mode);TextView b=buttons[i];b.setSelected(checked);
            b.setBackground(Ui.background(checked?Ui.SURFACE:Color.TRANSPARENT,dp(6)));
            b.setTextColor(checked?Ui.ACCENT:Ui.MUTED); b.setTypeface(null,checked?Typeface.BOLD:Typeface.NORMAL);
        }
        for(int i=0;i<styles.length;i++){
            boolean checked=selected==styles[i];LinearLayout v=options[i];v.setSelected(checked);
            GradientDrawable frame=Ui.background(checked?Ui.TINT:Ui.PANEL,dp(8));frame.setStroke(dp(1),checked?Ui.ACCENT:Ui.LINE);
            v.setBackground(frame); ticks[i].setVisibility(checked?View.VISIBLE:View.INVISIBLE);samples[i].preview(night,icons.get(styles[i]));
        }
        boolean fixed=selected==ThemeMaterial.CLASSIC;IconStyle style=icons.get(selected);
        iconOptions.setVisibility(fixed?View.GONE:View.VISIBLE);
        iconNote.setText(fixed?"经典 · 固定使用原版图标":selected.title+" · "+style.title);
        GradientDrawable previewBg=Ui.background(selected.tones(night).surface(),dp(8));previewBg.setStroke(dp(1),selected.tones(night).line());iconPreview.setBackground(previewBg);
        FileIconTheme.preview(getActivity(),previewFolder,selected,night,style,true);
        FileIconTheme.preview(getActivity(),previewFile,selected,night,style,false);
        for(int i=0;i<choices.length;i++){
            boolean checked=choices[i]==style;TextView b=iconButtons[i];b.setSelected(checked);b.setTextColor(checked?Ui.ACCENT:Ui.TEXT);
            GradientDrawable base=Ui.background(checked?Ui.TINT:Ui.PANEL,dp(8));if(checked)base.setStroke(dp(1),Ui.ACCENT);b.setBackground(base);
        }
    }

    /** Small schematic drawn from the same toolbar drawable, file colours and pane geometry as the browser. */
    private static final class Sample extends View {
        final ThemeMaterial style; final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private ThemeMaterial.Tones tones; private GradientDrawable header; private boolean night;private IconStyle iconStyle;
        private final ImageView folder;
        Sample(Context c, ThemeMaterial style) {super(c);this.style=style;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            folder=new ImageView(c);folder.setScaleType(ImageView.ScaleType.FIT_CENTER);}
        void preview(boolean dark,IconStyle selectedIcons){
            if(tones!=null&&night==dark&&iconStyle==selectedIcons)return;
            iconStyle=selectedIcons;FileIconTheme.preview(getContext(),folder,style,dark,selectedIcons,true);
            int side=Ui.dp(getContext(),28),spec=MeasureSpec.makeMeasureSpec(side,MeasureSpec.EXACTLY);folder.measure(spec,spec);folder.layout(0,0,side,side);
            night=dark;tones=style.tones(dark);header=style.fill(ThemeMaterial.Surface.HEADER,dark);
            setBackground(Ui.background(tones.surface(),Ui.dp(getContext(),5)));setClipToOutline(true);invalidate();
        }
        private void rect(Canvas c,float x,float y,float w,float h,int color) {
            paint.setColor(color);c.drawRoundRect(x,y,x+w,y+h,1.4f,1.4f,paint);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);float width=getWidth(),height=getHeight();boolean glass=style==ThemeMaterial.FROST;
            if(!glass){header.setBounds(0,0,getWidth(),Math.round(height*.32f));header.draw(c);}
            int s=c.save();c.scale(width/150f,height/57f);
            if(glass){int face=night?0xff3d4247:0xffe5e9ec;paint.setColor(face);c.drawCircle(10,9,6.5f,paint);c.drawRoundRect(22,2,129,16,7,7,paint);c.drawCircle(140,9,6.5f,paint);
                rect(c,30,7,36,2,tones.text());rect(c,30,11,20,1.3f,tones.muted());paint.setColor(face);c.drawRoundRect(13,47,137,56,4.5f,4.5f,paint);
                for(int i=0;i<5;i++)rect(c,27+i*23,50,3,3,tones.muted());
            }else{rect(c,7,6,29,2.5f,0xfff1f5f4);rect(c,7,11,16,1.4f,0xffc7d1d1);}
            rect(c,73.5f,19,1,glass?27:38,tones.line());
            for(int side=0;side<2;side++)for(int row=0;row<(side==0&&!glass?3:2);row++) {
                float x=side*75+6,y=22+row*11;int save=c.save();c.translate(x,y);c.scale(9f/folder.getWidth(),8f/folder.getHeight());folder.draw(c);c.restoreToCount(save);
                rect(c,x+11,y+1,row==1?25:38,2.2f,tones.muted());rect(c,x+11,y+5,17,1.5f,tones.line());
            }
            c.restoreToCount(s);
        }
    }
}
