package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import java.io.*;

public class BackupActivity extends LabActivity {
    private static final int EXPORT = 1, IMPORT = 2;
    private boolean working;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);showPage();
    }
    @Override protected void onPaletteChanged(){showPage();}
    private void showPage(){
        LinearLayout body = Ui.column(this),toolbar=Ui.toolbar(this,"书签与设置备份",this::finish);
        body.addView(toolbar);
        LinearLayout content = Ui.column(this); ScrollView scroll = new ScrollView(this); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        boolean styled=Ui.material!=ThemeMaterial.CLASSIC;
        if(styled){int edge=Ui.dp(this,16);content.setPadding(edge,Ui.dp(this,8),edge,edge);scroll.setFillViewport(true);
            ThemeMaterial.paint(scroll,ThemeMaterial.Surface.PAGE);content.addView(SettingsDesign.label(this,"备份内容"));}
        TextView note = Ui.text(this, "备份书签、最近目录、首页路径、排序、过滤、主题、改名模板及默认打开方式。导入后，外部存储需要重新授权，外部打开应用也需在本设备安装。", 15, Ui.TEXT);
        int pad = Ui.dp(this, 20); note.setPadding(pad, pad, pad, pad); content.addView(note);
        if(styled){note.setPadding(Ui.dp(this,6),0,Ui.dp(this,6),Ui.dp(this,12));note.setLineSpacing(Ui.dp(this,3),1);
            content.addView(SettingsDesign.label(this,"备份文件"));}
        LinearLayout transfers=styled?SettingsDesign.card(this):content;if(styled)content.addView(transfers);
        for (int code : new int[]{EXPORT, IMPORT}) {
            if(styled&&code==IMPORT)SettingsDesign.separator(transfers,50);
            Button button=SettingsDesign.command(this,code==EXPORT?"导出备份":"导入备份",code==EXPORT?R.drawable.ic_send:R.drawable.ic_download);
            button.setOnClickListener(v -> {
                if (working) return;
                Intent picker = new Intent(code == EXPORT ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType(code == EXPORT ? "application/json" : "*/*");
                if (code == EXPORT) picker.putExtra(Intent.EXTRA_TITLE, "DualStackLab-settings.json");
                try { startActivityForResult(picker, code); } catch (Exception error) { Tasks.showError(this, error); }
            }); transfers.addView(button);
        }
        if(styled)content.addView(SettingsDesign.label(this,"本机备份"));
        LinearLayout local=styled?SettingsDesign.card(this):content;if(styled)content.addView(local);
        Button workspace=SettingsDesign.command(this,"备份到公共数据目录",R.drawable.ic_save);local.addView(workspace);
        workspace.setOnClickListener(v -> { if (working) return; working=true;
            Tasks.run(this, () -> {
                java.io.File directory=new java.io.File(FileManagerSettings.workspace(this), "backups");
                if (!directory.isDirectory()&&!directory.mkdirs()) throw new IOException("无法创建备份目录");
                java.io.File file=new java.io.File(directory,"settings-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS",java.util.Locale.ROOT).format(new java.util.Date())+".json");
                java.nio.file.Files.write(file.toPath(),SettingsBackup.export(this),java.nio.file.StandardOpenOption.CREATE_NEW);return file;
            },file->{working=false;Tasks.toast(this,"已备份到 "+file.getPath());},error->{working=false;Tasks.showError(this,error);});
        });
        Ui.install(this, body);if(styled)SettingsDesign.chrome(this,body,toolbar);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        var uri = data.getData(); working = true;
        if (request == EXPORT) Tasks.run(this, () -> {
            byte[] bytes = SettingsBackup.export(this);
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("无法写入备份"); out.write(bytes);
            } return true;
        }, done -> { working = false; Tasks.toast(this, "备份已导出"); }, error -> { working = false; Tasks.showError(this, error); });
        else if (request == IMPORT) Tasks.run(this, () -> SettingsBackup.read(getContentResolver().openInputStream(uri)), backup -> {
            working = false;
            long needs = backup.bookmarks().stream().filter(e -> SettingsBackup.needsGrant(this, e)).count();
            MaterialDialogs.builder(this).setTitle("导入设置备份")
                    .setMessage("书签 " + backup.bookmarks().size() + " 个 · 最近目录 " + backup.recent().size() + " 个\n需重新授权的书签 " + needs + " 个\n合并保留已有书签；替换使用备份中的目录清单。两种方式均导入备份中的设置。")
                    .setNegativeButton("取消", null).setNeutralButton("合并导入", (d, w) -> apply(backup, true))
                    .setPositiveButton("替换导入", (d, w) -> apply(backup, false)).show();
        }, error -> { working = false; Tasks.showError(this, error); });
    }
    private void apply(SettingsBackup.Data data, boolean merge) {
        working = true;
        Tasks.run(this, () -> { SettingsBackup.apply(this, data, merge); return true; }, done -> {
            setResult(RESULT_OK); Tasks.toast(this, "设置已导入"); finish();
        }, error -> { working = false; Tasks.showError(this, error); });
    }
}
