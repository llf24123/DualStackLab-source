package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Environment;
import android.view.*;
import android.widget.*;
import java.io.File;

/** Storage destinations and tools, with the same presentation for local and removable volumes. */
final class NavigationDrawer {
    private static int canvas() { return SettingsDesign.page(); }
    private static int card() { return SettingsDesign.card(); }
    private static int accentTint() { return blend(card(), Ui.ACCENT, Ui.dark ? .17f : .09f); }
    private static int blend(int from, int to, float amount) {
        return Ui.blend(from, to, amount);
    }
    static Dialog show(Activity activity, Runnable root, Runnable internal, Runnable installed,
                       java.util.function.Consumer<RemovableStorage.Volume> removable, Runnable authorize,
                       java.util.function.Consumer<DirectoryPlaces.Entry> bookmark) {
        Dialog dialog = GlassWindow.dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body = Ui.column(activity);
        GradientDrawable backdrop = Ui.material.fill(ThemeMaterial.Surface.PAGE,Ui.dark); int corner=Ui.dp(activity,24);
        backdrop.setCornerRadii(new float[]{0,0,corner,corner,corner,corner,0,0});
        body.setBackground(backdrop); body.setClipToOutline(true);
        LinearLayout header=Ui.column(activity);
        header.setPadding(Ui.dp(activity,20),Ui.dp(activity,22),Ui.dp(activity,12),Ui.dp(activity,18));
        LinearLayout brandRow=Ui.row(activity);
        ImageView brand=Ui.image(activity,R.drawable.ic_app,Ui.ACCENT); brand.setImageTintList(null);
        brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brandRow.addView(brand,new LinearLayout.LayoutParams(Ui.dp(activity,38),Ui.dp(activity,44)));
        LinearLayout titles=Ui.column(activity); titles.setPadding(Ui.dp(activity,12),0,0,0);
        TextView title=Ui.text(activity,"双栈 Lab",22,Ui.TEXT); title.setTypeface(null,Typeface.BOLD); titles.addView(title);
        brandRow.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        ImageView theme=Ui.icon(activity,R.drawable.ic_theme,"主题切换",Ui.ACCENT,()->{dialog.dismiss();Ui.chooseTheme(activity);});
        theme.setBackground(ripple(activity,accentTint(),16));
        brandRow.addView(theme,new LinearLayout.LayoutParams(Ui.dp(activity,48),Ui.dp(activity,48)));
        header.addView(brandRow);body.addView(header);
        ScrollView scroll=new ScrollView(activity);scroll.setContentDescription("位置与工具列表");
        scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content=Ui.column(activity);content.setPadding(Ui.dp(activity,16),0,Ui.dp(activity,16),Ui.dp(activity,14));
        scroll.addView(content);
        content.addView(section(activity,"存储空间"));
        LinearLayout local=group(activity);content.addView(local);
        storage(activity,local,"内部存储",Environment.getExternalStorageDirectory(),dialog,internal);
        divider(activity,local);storage(activity,local,"根目录",new File("/"),dialog,root);
        LinearLayout removableSection=Ui.column(activity);removableSection.setVisibility(View.GONE);content.addView(removableSection);
        removableSection.setContentDescription("可移除存储分组");removableSection.addView(section(activity,"U 盘 / SD 卡"));
        LinearLayout external=group(activity);removableSection.addView(external);
        LinearLayout volumes=Ui.column(activity);external.addView(volumes);
        divider(activity,external);
        external.addView(row(activity,"授权 U 盘 / SD 卡","通过系统选择目录",R.drawable.ic_plus,()->{dialog.dismiss();authorize.run();}));
        content.addView(section(activity,"常用工具"));
        LinearLayout tools=group(activity);content.addView(tools);
        tools.addView(row(activity,"文本编辑器","文本、代码与保留的文件",R.drawable.ic_edit,()->{dialog.dismiss();EditorDocuments.show(activity,()->{});}));
        divider(activity,tools);
        tools.addView(row(activity,"终端模拟器","命令行与多会话",R.drawable.ic_terminal,()->{dialog.dismiss();TerminalSessions.open(activity,Environment.getExternalStorageDirectory().getPath());}));
        divider(activity,tools);
        tools.addView(row(activity,"公共数据目录","导出与备份文件",R.drawable.ic_folder,()->{
            dialog.dismiss();Tasks.run(activity,()->{
                File folder=FileManagerSettings.workspace(activity);
                if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException("无法创建公共数据目录，请在设置中选择可写目录");
                return folder;
            },folder->activity.startActivity(new Intent(activity,MainActivity.class).putExtra("navigatePath",folder.getPath())));
        }));
        divider(activity,tools);
        tools.addView(row(activity,"设备信息","系统、硬件与设备标识",R.drawable.ic_info,()->{dialog.dismiss();activity.startActivity(new Intent(activity,DeviceInfoActivity.class));}));
        divider(activity,tools);
        tools.addView(row(activity,"已安装应用","查看应用与安装信息",R.drawable.ic_package,()->{dialog.dismiss();installed.run();}));
        divider(activity,tools);
        tools.addView(row(activity,"网络存储","WebDAV、SMB 与 SFTP",R.drawable.ic_globe,()->{
            dialog.dismiss(); activity.startActivity(new Intent(activity,RemoteStorageActivity.class));
        }));
        if(FileManagerSettings.prefs(activity).getBoolean("sidebar-bookmarks",false)){
            content.addView(section(activity,"书签"));LinearLayout bookmarks=group(activity);content.addView(bookmarks);
            java.util.List<DirectoryPlaces.Entry> entries=new DirectoryPlaces(activity).entries(true);
            if(entries.isEmpty())bookmarks.addView(note(activity,"保存的书签会显示在这里"));
            for(DirectoryPlaces.Entry entry:entries){
                if(bookmarks.getChildCount()>0)divider(activity,bookmarks);
                LinearLayout shortcut=row(activity,entry.label(),null,R.drawable.ic_bookmark,()->{dialog.dismiss();bookmark.accept(entry);});
                if(Ui.material!=ThemeMaterial.CLASSIC)fileIcon((ImageView)shortcut.getChildAt(0),entry.path(),entry.directory());
                bookmarks.addView(shortcut);
            }
        }
        body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        // Keep settings reachable even with a long list of external volumes and bookmarks.
        LinearLayout footer=Ui.column(activity);footer.setPadding(Ui.dp(activity,16),Ui.dp(activity,10),Ui.dp(activity,16),Ui.dp(activity,14));
        LinearLayout settings=row(activity,"设置","外观、操作与偏好",R.drawable.ic_settings,()->{dialog.dismiss();activity.startActivityForResult(new Intent(activity,SettingsActivity.class),402);});
        settings.setBackground(ripple(activity,accentTint(),18));footer.addView(settings);body.addView(footer);
        dialog.setContentView(body);Window window=dialog.getWindow();if(window==null)return dialog;
        if(Ui.material==ThemeMaterial.FROST)GlassWindow.install(window,body,24,true);
        else window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.36f);
        window.setGravity(Gravity.START|Gravity.TOP);window.setWindowAnimations(Ui.motionEnabled()?R.style.NavigationDrawerAnimation:0);
        window.setNavigationBarColor(canvas());window.getDecorView().setSystemUiVisibility(Ui.dark?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        int screen=activity.getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min(Ui.dp(activity,340),Math.round(screen*.82f)),-1);
        int[] generation={0};
        Runnable reload=()->{
            int token=++generation[0];
            Tasks.run(activity,()->RemovableStorage.volumes(activity),found->{
                if(!dialog.isShowing()||token!=generation[0])return;
                renderRemovable(activity,dialog,removableSection,volumes,found,removable);
            },error->{
                if(!dialog.isShowing()||token!=generation[0])return;
                renderRemovable(activity,dialog,removableSection,volumes,java.util.List.of(),removable);
            });
        };
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){reload.run();}};
        IntentFilter changes=new IntentFilter();changes.addDataScheme("file");
        for(String action:new String[]{Intent.ACTION_MEDIA_MOUNTED,Intent.ACTION_MEDIA_UNMOUNTED,Intent.ACTION_MEDIA_EJECT,
                Intent.ACTION_MEDIA_REMOVED,Intent.ACTION_MEDIA_BAD_REMOVAL,Intent.ACTION_MEDIA_CHECKING,
                Intent.ACTION_MEDIA_NOFS,Intent.ACTION_MEDIA_UNMOUNTABLE})changes.addAction(action);
        if(Build.VERSION.SDK_INT>=33)activity.registerReceiver(receiver,changes,Context.RECEIVER_NOT_EXPORTED);else activity.registerReceiver(receiver,changes);
        dialog.setOnDismissListener(d->{generation[0]++;volumes.animate().cancel();activity.unregisterReceiver(receiver);});
        dialog.show();reload.run();return dialog;
    }
    /** Only the currently mounted system volumes create sidebar destinations; saved grants are not devices. */
    static void renderRemovable(Activity activity,Dialog dialog,LinearLayout section,LinearLayout volumes,
                                java.util.List<RemovableStorage.Volume> found,java.util.function.Consumer<RemovableStorage.Volume> open){
        volumes.animate().cancel();volumes.removeAllViews();
        for(RemovableStorage.Volume volume:found){
            if(!volume.mounted())continue;
            if(volumes.getChildCount()>0)divider(activity,volumes);
            LinearLayout destination=storage(activity,volumes,volume.title(),volume.directory(),dialog,()->open.accept(volume));
            destination.setContentDescription("可移除存储 "+volume.id());
        }
        section.setVisibility(volumes.getChildCount()==0?View.GONE:View.VISIBLE);
        if(section.getVisibility()==View.VISIBLE)Ui.fadeIn(volumes);
    }
    private static LinearLayout group(Activity activity){
        LinearLayout group=Ui.column(activity);GradientDrawable bg=Ui.material.card(activity,card(),18);
        if(Ui.material==ThemeMaterial.FROST)bg.setAlpha(188);
        group.setBackground(bg);group.setClipToOutline(true);return group;
    }
    private static RippleDrawable ripple(Activity activity,int color,int radius){
        return new RippleDrawable(ColorStateList.valueOf((Ui.ACCENT&0x00ffffff)|0x22000000),
                Ui.background(color,Ui.dp(activity,radius)),Ui.background(Color.WHITE,Ui.dp(activity,radius)));
    }
    private static TextView section(Activity activity,String title){
        TextView text=Ui.text(activity,title,12,Ui.MUTED);text.setTypeface(null,Typeface.BOLD);text.setLetterSpacing(.05f);
        text.setPadding(Ui.dp(activity,4),Ui.dp(activity,20),0,Ui.dp(activity,10));
        if(Build.VERSION.SDK_INT>=28)text.setAccessibilityHeading(true);return text;
    }
    private static TextView note(Activity activity,String title){
        TextView text=Ui.text(activity,title,12,Ui.MUTED);text.setPadding(Ui.dp(activity,14),Ui.dp(activity,14),Ui.dp(activity,14),Ui.dp(activity,14));return text;
    }
    private static void divider(Activity activity,LinearLayout parent){
        View line=new View(activity);line.setBackgroundColor(blend(card(),Ui.MUTED,.13f));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,Ui.dp(activity,1));params.leftMargin=Ui.dp(activity,60);params.rightMargin=Ui.dp(activity,14);parent.addView(line,params);
    }
    private static ImageView icon(Activity activity,int drawable){
        ImageView image=Ui.image(activity,drawable,Ui.ACCENT);image.setBackground(Ui.background(accentTint(),Ui.dp(activity,12)));
        image.setPadding(Ui.dp(activity,8),Ui.dp(activity,8),Ui.dp(activity,8),Ui.dp(activity,8));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if(drawable==R.drawable.ic_folder && Ui.material!=ThemeMaterial.CLASSIC)fileIcon(image,"",true);
        return image;
    }
    private static void fileIcon(ImageView image,String name,boolean directory){
        new FileIconTheme.Binding(image,new GradientDrawable(),7,6).bind(FileIconTheme.kind(name,directory,FileIconTheme.EntryPoint.PLACES),false);
    }
    private static ImageView arrow(Activity activity){
        ImageView arrow=Ui.image(activity,R.drawable.ic_back,Ui.MUTED);arrow.setRotation(180);arrow.setAlpha(.65f);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return arrow;
    }
    private static LinearLayout row(Activity activity,String name,String summary,int drawable,Runnable action){
        LinearLayout row=Ui.row(activity);row.setMinimumHeight(Ui.dp(activity,60));row.setPadding(Ui.dp(activity,14),Ui.dp(activity,12),Ui.dp(activity,12),Ui.dp(activity,12));
        row.addView(icon(activity,drawable),new LinearLayout.LayoutParams(Ui.dp(activity,34),Ui.dp(activity,34)));
        LinearLayout labels=Ui.column(activity);labels.setPadding(Ui.dp(activity,12),0,Ui.dp(activity,8),0);
        TextView text=Ui.text(activity,name,15,Ui.TEXT);text.setTypeface(null,Typeface.BOLD);labels.addView(text);
        if(summary!=null&&!summary.isEmpty()){TextView detail=Ui.text(activity,summary,11,Ui.MUTED);detail.setPadding(0,Ui.dp(activity,5),0,0);labels.addView(detail);}
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));row.addView(arrow(activity),new LinearLayout.LayoutParams(Ui.dp(activity,16),Ui.dp(activity,16)));
        row.setBackground(ripple(activity,Color.TRANSPARENT,0));row.setFocusable(true);row.setOnClickListener(v->action.run());return row;
    }
    private static LinearLayout storage(Activity activity,LinearLayout parent,String title,File file,Dialog dialog,Runnable action){
        LinearLayout row=Ui.column(activity);row.setPadding(Ui.dp(activity,14),Ui.dp(activity,14),Ui.dp(activity,14),Ui.dp(activity,14));
        row.setMinimumHeight(Ui.dp(activity,80));row.setBackground(ripple(activity,Color.TRANSPARENT,0));row.setFocusable(true);row.setOnClickListener(v->{dialog.dismiss();action.run();});
        LinearLayout heading=Ui.row(activity);
        heading.addView(icon(activity,file!=null&&"/".equals(file.getPath())?R.drawable.ic_home:R.drawable.ic_folder),new LinearLayout.LayoutParams(Ui.dp(activity,34),Ui.dp(activity,34)));
        TextView label=Ui.text(activity,title,15,Ui.TEXT);label.setTypeface(null,Typeface.BOLD);label.setPadding(Ui.dp(activity,12),0,Ui.dp(activity,6),0);
        heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));heading.addView(arrow(activity),new LinearLayout.LayoutParams(Ui.dp(activity,16),Ui.dp(activity,16)));row.addView(heading);
        ProgressBar capacity=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        capacity.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));capacity.setProgressBackgroundTintList(ColorStateList.valueOf(accentTint()));
        LinearLayout.LayoutParams track=new LinearLayout.LayoutParams(-1,Ui.dp(activity,5));track.topMargin=Ui.dp(activity,12);track.bottomMargin=Ui.dp(activity,8);row.addView(capacity,track);
        TextView value=Ui.text(activity,"读取容量…",11,Ui.MUTED);row.addView(value);capacity.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);parent.addView(row);
        if(file==null){capacity.setVisibility(View.GONE);value.setText("点按选择或授权目录");return row;}
        Tasks.IO.execute(()->{
            long total=0,free=0;try{total=file.getTotalSpace();free=file.getUsableSpace();}catch(SecurityException ignored){}
            long space=total,available=free;
            activity.runOnUiThread(()->{
                if(!dialog.isShowing()||row.getParent()==null)return;
                long used=Math.max(0,space-available);capacity.setProgress(space==0?0:(int)Math.min(100,(double)used*100/space));
                value.setText(space==0?"容量不可用":FileStore.size(available)+" 可用 / 共 "+FileStore.size(space));
            });
        });return row;
    }
    private NavigationDrawer(){}
}
