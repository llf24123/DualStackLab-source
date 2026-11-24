package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;

final class FileManagerSettings {
    static android.content.SharedPreferences prefs(Context context) { return context.getSharedPreferences("file-manager",0); }
    static File workspace(Context context) { return new File(prefs(context).getString("workspace",new File(Environment.getExternalStorageDirectory(),"DualStackLab").getPath())); }
    static void root(Activity activity) {
        root(activity, false);
    }
    static void rootCommand(Activity activity) {
        root(activity, true);
    }
    private static void root(Activity activity, boolean commandOnly) {
        LinearLayout body=Ui.column(activity);body.setPadding(Ui.dp(activity,20),0,Ui.dp(activity,20),0);
        CheckBox enabled=new CheckBox(activity);enabled.setText("自动获取 ROOT 权限");enabled.setMinHeight(Ui.dp(activity,48));enabled.setChecked(prefs(activity).getBoolean("root",true));body.addView(enabled);
        if(commandOnly)enabled.setVisibility(View.GONE);
        EditText command=Ui.input(activity,"su 程序路径，例如 su 或 /debug_ramdisk/su");command.setText(prefs(activity).getString("su","su"));body.addView(command);
        AlertDialog dialog=MaterialDialogs.builder(activity).setTitle(commandOnly?"自定义 su 命令":"ROOT 设置").setView(body).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String value=command.getText().toString().trim();
            if(value.isEmpty()||value.indexOf('\0')>=0||value.indexOf('\n')>=0||(!value.equals("su")&&!new File(value).isAbsolute())) {command.setError("填写 su 或程序的绝对路径");return;}
            prefs(activity).edit().putBoolean("root",enabled.isChecked()).putString("su",value).apply();RootShell.configure(value);RootAccess.setAutomatic(enabled.isChecked());dialog.dismiss();Tasks.toast(activity,"ROOT 设置已生效");
        }));dialog.show();
    }
    static void chooseWorkspace(Activity activity) {
        EditText path=Ui.input(activity,"公共数据目录");path.setText(workspace(activity).getPath());
        AlertDialog dialog=MaterialDialogs.builder(activity).setTitle("公共数据目录").setMessage("导出的配置、语法文件、安装包可放在这里，卸载后仍保留。更改路径不会移动已有文件。")
                .setView(path).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{File file=new File(path.getText().toString().trim());if(!file.isAbsolute()){path.setError("请输入绝对路径");return;}
            dialog.getButton(-1).setEnabled(false);Tasks.run(activity,()->{File target=file.getCanonicalFile();if(!target.isDirectory()&&!target.mkdirs())throw new IOException("无法创建目录");
                File probe=File.createTempFile(".dualstack-check-",".tmp",target);java.nio.file.Files.delete(probe.toPath());return target;
            },target->{prefs(activity).edit().putString("workspace",target.getPath()).apply();dialog.dismiss();Tasks.toast(activity,"公共数据目录已更新");},error->{dialog.getButton(-1).setEnabled(true);path.setError(error.getMessage());});
        }));dialog.show();
    }
    static void gesture(Activity activity,Runnable changed) {
        AlertDialog.Builder dialog=MaterialDialogs.builder(activity);
        Context c=SettingsDesign.themed()?dialog.getContext():activity;
        LinearLayout body=SettingsDesign.themed()?SettingsDesign.form(c):Ui.column(c);
        Spinner mode=gestureModes(c);mode.setSelection(prefs(activity).getInt("gesture-mode",0));body.addView(mode);
        TextView label=Ui.text(c,"",15,Ui.TEXT);
        if(SettingsDesign.themed()) label.setPadding(0,Ui.dp(c,18),0,Ui.dp(c,2));
        body.addView(label);
        SeekBar size=SettingsDesign.slider(c,"底部工具栏下边距");size.setMax(22);size.setProgress(prefs(activity).getInt("gesture-gap",12)-6);body.addView(size);
        Runnable update=()->{String value=(size.getProgress()+6)+" dp";label.setText("底部工具栏下边距："+value);if(Build.VERSION.SDK_INT>=30)size.setStateDescription(value);};
        update.run();size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){update.run();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        View form=body;
        if(SettingsDesign.themed()){ScrollView scroll=new ScrollView(c);scroll.addView(body);form=scroll;}
        dialog.setTitle("书签上滑手势").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{prefs(activity).edit().putInt("gesture-mode",mode.getSelectedItemPosition()).putInt("gesture-gap",size.getProgress()+6).apply();changed.run();}).show();
    }
    private static Spinner gestureModes(Context c) {
        String[] modes={"自动识别全面屏手势","总是增加下边距","不增加下边距"};
        if(!SettingsDesign.themed()) {
            Spinner mode=new Spinner(c);mode.setAdapter(new ArrayAdapter<>(c,android.R.layout.simple_spinner_dropdown_item,modes));return mode;
        }
        Spinner mode=new Spinner(c,Spinner.MODE_DROPDOWN);
        int radius=SettingsDesign.radius(12),side=Ui.dp(c,14);
        mode.setMinimumHeight(Ui.dp(c,48));mode.setBackgroundTintList(null);
        mode.setBackground(Ui.material.card(c,SettingsDesign.card(),radius));
        mode.setForeground(SettingsDesign.ripple(c,android.graphics.Color.TRANSPARENT,radius));
        mode.setPopupBackgroundDrawable(Ui.material.card(c,SettingsDesign.card(),radius));
        ArrayAdapter<String> items=new ArrayAdapter<>(c,android.R.layout.simple_spinner_item,modes){
            private void styleRow(TextView row){
                row.setTextColor(Ui.TEXT);row.setTextSize(15);row.setSingleLine(false);row.setEllipsize(null);
                row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);row.setMinHeight(Ui.dp(c,48));
                row.setPaddingRelative(side,Ui.dp(c,6),side,Ui.dp(c,6));
                ViewGroup.LayoutParams params=row.getLayoutParams();if(params!=null)params.height=ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            @Override public View getView(int position,View reuse,ViewGroup parent){
                TextView row=(TextView)super.getView(position,reuse,parent);
                styleRow(row);
                row.setCompoundDrawablesRelative(null,null,SettingsDesign.glyph(c,R.drawable.ic_chevron_down,Ui.ACCENT,18),null);
                row.setCompoundDrawablePadding(Ui.dp(c,8));return row;
            }
            @Override public View getDropDownView(int position,View reuse,ViewGroup parent){
                TextView row=(TextView)super.getDropDownView(position,reuse,parent);
                styleRow(row);return row;
            }
        };
        items.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);mode.setAdapter(items);return mode;
    }
    static int gestureGap(Context c, WindowInsets insets) {
        int mode=prefs(c).getInt("gesture-mode",0);boolean enabled=mode==1;
        if(mode==0&&insets!=null&&Build.VERSION.SDK_INT>=30)enabled=insets.getInsets(WindowInsets.Type.tappableElement()).bottom==0&&insets.getInsets(WindowInsets.Type.systemGestures()).bottom>0;
        else if(mode==0&&insets!=null&&Build.VERSION.SDK_INT>=29)enabled=insets.getTappableElementInsets().bottom==0&&insets.getSystemGestureInsets().bottom>0;
        return enabled?Ui.dp(c,Math.max(6,Math.min(28,prefs(c).getInt("gesture-gap",12)))):0;
    }
    private FileManagerSettings(){}
}
