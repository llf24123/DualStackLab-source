package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import java.io.*;
import java.util.*;

/** Dedicated, cancellable ZIP maintenance screen. */
public class ArchiveToolsActivity extends LabActivity {
    private static final int MERGE_DIRECTORY=21;
    private ArchiveTools tools;
    private final Tasks.Progress progress=new Tasks.Progress();
    private LinearLayout controls;
    private ScrollView scroll;
    private ScreenState screenState;
    public static class ScreenState extends ViewModel {
        boolean initialized,hasDraft;
        String draft="",report="",status="就绪";
        int scroll,selectionStart,selectionEnd;
    }
    private final List<LinearLayout> cards=new ArrayList<>();
    private TextView metadata,status,report;
    private EditText comment;
    private Button save,merge,verify,stop;
    private ProgressBar meter;
    private boolean busy=true;
    private String pendingName,pendingDirectory;
    private int generation;
    private volatile long lastProgressUi;
    static boolean supports(String name){return name!=null&&name.toLowerCase(Locale.ROOT).matches(".*\\.(zip|apk|apks|xapk|apkm|jar|aar|aab)$");}
    static void open(Activity activity,FileStore.Node source){Intent intent=new Intent(activity,ArchiveToolsActivity.class);source.put(intent);activity.startActivity(intent);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);screenState=new ViewModelProvider(this).get(ScreenState.class);
        if(!screenState.initialized&&state!=null){screenState.hasDraft=state.getBoolean("comment-present");screenState.draft=state.getString("comment-draft","");screenState.report=state.getString("report","");screenState.status=state.getString("status","就绪");screenState.scroll=state.getInt("scroll");screenState.selectionStart=state.getInt("selection-start");screenState.selectionEnd=state.getInt("selection-end");}
        screenState.initialized=true;LinearLayout body=Ui.column(this);body.addView(Ui.toolbar(this,"归档工具",this::finish));
        scroll=new ScrollView(this);controls=Ui.column(this);controls.setPadding(dp(16),dp(12),dp(16),dp(24));scroll.addView(controls);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        metadata=Ui.text(this,"正在读取 ZIP 信息…",14,Ui.MUTED);metadata.setPadding(dp(4),dp(8),dp(4),dp(16));controls.addView(metadata);
        LinearLayout annotations=card("ZIP 注释","附加说明随 ZIP 保存；只修改归档注释。");
        comment=Ui.input(this,"为压缩包添加说明");comment.setContentDescription("ZIP 注释输入框");comment.setSingleLine(false);comment.setMinLines(3);comment.setMaxLines(8);comment.setGravity(Gravity.TOP|Gravity.START);comment.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);annotations.addView(comment);
        comment.setFilters(new InputFilter[]{new InputFilter.LengthFilter(65535)});comment.setSaveEnabled(false);if(screenState.hasDraft)comment.setText(screenState.draft);
        save=button(annotations,"保存注释",this::saveComment);
        LinearLayout splitCard=card("合并 ZIP 分卷","把 .z01、.z02 等分卷保存为一个 ZIP 文件，原分卷保留。");merge=button(splitCard,"合并为普通 ZIP",this::askMerge);
        LinearLayout checkCard=card("完整性测试","逐项读取并检查长度、CRC32 或 AES 认证，显示损坏条目。");verify=button(checkCard,"开始校验",this::askVerify);
        status=Ui.text(this,"准备归档副本…",13,Ui.MUTED);status.setPadding(dp(4),dp(18),dp(4),dp(8));controls.addView(status);
        meter=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);meter.setIndeterminate(true);controls.addView(meter,new LinearLayout.LayoutParams(-1,dp(5)));
        stop=button(controls,"停止",()->{progress.cancelled.set(true);stop.setEnabled(false);status.setText("正在停止…");});
        report=Ui.text(this,"",13,Ui.TEXT);report.setTextIsSelectable(true);report.setPadding(dp(4),dp(14),dp(4),dp(8));controls.addView(report);
        report.setContentDescription("归档校验报告");report.setSaveEnabled(false);report.setText(screenState.report);
        Ui.install(this,body);setBusy(true);pendingName=state==null?null:state.getString("merge-name");pendingDirectory=state==null?null:state.getString("merge-directory");
        int token=++generation;
        Tasks.IO.execute(()->{
            ArchiveTools loaded=null;Exception failure=null;
            try{loaded=progress.run(()->new ArchiveTools(getApplicationContext(),new FileStore(this).fromIntent(getIntent()),progress.cancelled::get));}catch(Exception e){failure=e;}
            ArchiveTools result=loaded;Exception error=failure;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||token!=generation){if(result!=null)Tasks.IO.execute(result::close);return;}
                tools=result;setBusy(false);
                if(error!=null){status.setText(progress.cancelled.get()?"读取已停止":"无法打开归档");report.setText(message(error));return;}
                metadata.setText(tools.source.name+"\n"+tools.entries+" 项 · "+(tools.split?tools.volumes+" 个分卷":"普通 ZIP")+(tools.encrypted?" · 已加密":""));
                comment.setText(screenState.hasDraft?screenState.draft:tools.comment());
                if(screenState.hasDraft){int length=comment.length();comment.setSelection(Math.min(length,Math.max(0,screenState.selectionStart)),Math.min(length,Math.max(0,screenState.selectionEnd)));}
                status.setText(screenState.status);report.setText(screenState.report);scroll.post(()->scroll.scrollTo(0,screenState.scroll));mergePickedDirectory();
            });
        });
    }
    private int dp(int value){return Ui.dp(this,value);}
    private LinearLayout card(String title,String detail){
        LinearLayout card=Ui.column(this);cards.add(card);card.setPadding(dp(16),dp(16),dp(16),dp(12));styleCard(card);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(12);controls.addView(card,params);
        TextView heading=Ui.text(this,title,17,Ui.TEXT);heading.setTypeface(null,android.graphics.Typeface.BOLD);card.addView(heading);
        TextView summary=Ui.text(this,detail,12,Ui.MUTED);summary.setPadding(0,dp(7),0,dp(10));card.addView(summary);return card;
    }
    private Button button(LinearLayout parent,String title,Runnable action){Button button=new Button(this);button.setText(title);button.setAllCaps(false);button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());parent.addView(button,new LinearLayout.LayoutParams(-1,-2));return button;}
    private void setBusy(boolean value){
        busy=value;boolean ready=tools!=null&&!busy;
        save.setEnabled(ready&&!tools.split);comment.setEnabled(ready&&!tools.split);merge.setEnabled(ready&&tools.split);verify.setEnabled(ready);
        stop.setVisibility(value?View.VISIBLE:View.GONE);stop.setEnabled(value);meter.setVisibility(value?View.VISIBLE:View.GONE);
    }
    private void saveComment(){
        if(busy||tools==null)return;String value=comment.getText().toString();
        if(value.equals(tools.comment())){Tasks.toast(this,"注释未改变");return;}
        Runnable save=()->execute("保存 ZIP 注释",()->{tools.saveComment(value,progress.cancelled::get);return "注释已保存";});
        FileStore.Node signed=null;
        for(FileStore.Node node=tools.source;node!=null;node=node.container){String name=node.name.toLowerCase(Locale.ROOT);if(name.endsWith(".apk")){signed=node;break;}if(signed==null&&name.matches(".*\\.(aab|jar|apks|xapk|apkm)$"))signed=node;}
        if(signed==null){save.run();return;}
        boolean apk=signed.name.toLowerCase(Locale.ROOT).endsWith(".apk");
        String detail=apk?"修改 "+signed.name+" 的 ZIP 注释会改变 APK v2/v3 签名覆盖的数据；使用这些签名的安装包将无法通过原签名校验，需要重新签名。":"修改 "+signed.name+" 会改变容器文件的字节和摘要。对签名的影响取决于签名方式，本操作不会重新签名。";
        MaterialDialogs.builder(this).setTitle("确认修改应用包注释").setMessage(detail).setNegativeButton("取消",null).setPositiveButton("修改并保存",(d,n)->save.run()).show();
    }
    private void askVerify(){
        if(!tools.encrypted){verify(new char[0]);return;}
        EditText password=Ui.input(this,"ZIP 密码");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        MaterialDialogs.builder(this).setTitle("输入 ZIP 密码").setView(password).setNegativeButton("取消",null).setPositiveButton("校验",(d,n)->verify(password.getText().toString().toCharArray())).show();
    }
    private void verify(char[] password){execute("读取并校验",()->{try{return tools.verify(password,progress.cancelled::get,this::updateProgress).text();}finally{Arrays.fill(password,'\0');}});}
    private void askMerge(){
        EditText name=Ui.input(this,"合并后的文件名.zip");String original=tools.source.name;int dot=original.lastIndexOf('.');name.setText((dot>0?original.substring(0,dot):original)+"-merged.zip");
        AlertDialog dialog=MaterialDialogs.builder(this).setTitle("合并 ZIP 分卷").setView(name).setNegativeButton("取消",null).setNeutralButton("选择目录",null).setPositiveButton("保存到原目录",null).create();
        dialog.setOnShowListener(d->{
            dialog.getButton(-1).setEnabled(tools.store.parent(tools.source)!=null&&tools.store.parent(tools.source).container==null);
            dialog.getButton(-1).setOnClickListener(v->{if(!validName(name))return;dialog.dismiss();merge(tools.store.parent(tools.source),pendingName);});
            dialog.getButton(-3).setOnClickListener(v->{if(!validName(name))return;dialog.dismiss();startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),MERGE_DIRECTORY);});
        });dialog.show();
    }
    private boolean validName(EditText input){
        String name=input.getText().toString().trim();try{FileStore.checkName(name);if(!name.toLowerCase(Locale.ROOT).endsWith(".zip"))throw new IOException("名称需以 .zip 结尾");pendingName=name;return true;}
        catch(IOException e){input.setError(e.getMessage());return false;}
    }
    private void merge(FileStore.Node destination,String name){execute("合并 ZIP 分卷",()->"已保存：\n"+tools.merge(destination,name,progress.cancelled::get,this::updateProgress).path());}
    private void execute(String title,Tasks.Job<String> action){
        if(busy||tools==null)return;progress.cancelled.set(false);progress.transferred.set(0);lastProgressUi=0;setBusy(true);status.setText(title);meter.setIndeterminate(true);
        Tasks.run(this,Tasks.FILES,()->progress.run(action),result->{setBusy(false);status.setText("操作结束");report.setText(result);setResult(RESULT_OK);},error->{setBusy(false);status.setText(progress.cancelled.get()?"已停止":"操作失败");report.setText(message(error));});
    }
    private void updateProgress(ArchiveTools.Progress value){
        long now=android.os.SystemClock.uptimeMillis();if(now-lastProgressUi<100&&value.checked()!=value.total())return;lastProgressUi=now;
        runOnUiThread(()->{if(isFinishing()||isDestroyed()||!busy)return;status.setText(value.phase()+" · "+value.checked()+" / "+value.total()+" 项\n"+FileStore.size(value.bytes())+(value.totalBytes()>0?" / "+FileStore.size(value.totalBytes()):"")+"\n"+value.entry());meter.setIndeterminate(value.totalBytes()<=0);if(value.totalBytes()>0)meter.setProgress((int)Math.min(100,100d*value.bytes()/value.totalBytes()));});
    }
    private String message(Exception error){return Objects.requireNonNullElse(error.getMessage(),error.getClass().getSimpleName());}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=MERGE_DIRECTORY||result!=RESULT_OK||data==null||data.getData()==null||pendingName==null)return;
        try{
            var uri=data.getData();int grant=0;
            if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)grant|=Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)grant|=Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try{getContentResolver().takePersistableUriPermission(uri,grant);}catch(SecurityException ignored){}
            pendingDirectory=android.provider.DocumentsContract.buildDocumentUriUsingTree(uri,android.provider.DocumentsContract.getTreeDocumentId(uri)).toString();
            mergePickedDirectory();
        }catch(Exception e){Tasks.showError(this,e);}
    }
    private void mergePickedDirectory(){
        if(tools==null||busy||pendingDirectory==null||pendingName==null)return;
        var document=android.net.Uri.parse(pendingDirectory);String name=pendingName;pendingDirectory=null;
        execute("合并 ZIP 分卷",()->"已保存：\n"+tools.merge(tools.store.document(document),name,progress.cancelled::get,this::updateProgress).path());
    }
    private void rememberScreen(){
        if(screenState==null||comment==null)return;
        if(tools!=null||screenState.hasDraft){screenState.hasDraft=true;screenState.draft=comment.getText().toString();screenState.selectionStart=comment.getSelectionStart();screenState.selectionEnd=comment.getSelectionEnd();}
        screenState.report=report.getText().toString();screenState.scroll=scroll.getScrollY();
        if(tools!=null)screenState.status=busy?"页面已恢复，请核对上次操作结果":status.getText().toString();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        rememberScreen();super.onSaveInstanceState(state);state.putString("merge-name",pendingName);state.putString("merge-directory",pendingDirectory);
        state.putBoolean("comment-present",screenState.hasDraft);state.putString("comment-draft",screenState.draft);state.putString("report",screenState.report);state.putString("status",screenState.status);
        state.putInt("scroll",screenState.scroll);state.putInt("selection-start",screenState.selectionStart);state.putInt("selection-end",screenState.selectionEnd);
    }
    private void styleCard(View card){ToolAppearance.card(card,Ui.dark?0xff232b2b:Color.WHITE,18,18);}
    @Override protected void onPaletteChanged(){for(LinearLayout card:cards)styleCard(card);}
    @Override protected void onDestroy(){rememberScreen();generation++;progress.cancelled.set(true);ArchiveTools closing=tools;if(closing!=null)Tasks.IO.execute(closing::close);super.onDestroy();}
}
