package org.example.filemanager;

import android.app.*;
import android.content.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

final class SyntaxFiles {
    static void install(Activity activity,FileStore.Node node){Tasks.run(activity,()->{FileStore store=new FileStore(activity);try(InputStream in=store.read(node)){byte[] data=FileStore.readBytes(in,MtsxSyntax.LIMIT+1);return MtsxSyntax.install(activity,data);}},syntax->Tasks.toast(activity,"已安装语法："+syntax.name));}
    static void choose(Activity activity,Consumer<MtsxSyntax> selected,Runnable builtin,Runnable importFile){
        Tasks.run(activity,()->{List<MtsxSyntax> syntaxes=new ArrayList<>();for(File file:MtsxSyntax.installed(activity))try{syntaxes.add(MtsxSyntax.read(file));}catch(IOException broken){android.util.Log.w("SyntaxFiles","跳过损坏语法 "+file.getName(),broken);}return syntaxes;},syntaxes->{
            List<String> labels=new ArrayList<>(List.of("按文件类型自动选择","导入 .mtsx 语法文件","管理已安装语法"));for(MtsxSyntax syntax:syntaxes)labels.add(syntax.name);
            MaterialDialogs.builder(activity).setTitle("语法高亮").setItems(labels.toArray(String[]::new),(d,n)->{if(n==0)builtin.run();else if(n==1)importFile.run();else if(n==2)manage(activity);else selected.accept(syntaxes.get(n-3));}).show();
        });
    }
    static void manage(Activity activity){Tasks.run(activity,()->{List<File> files=MtsxSyntax.installed(activity);List<String> names=new ArrayList<>();for(File file:files)try{names.add(MtsxSyntax.read(file).name);}catch(IOException broken){names.add("损坏的语法 · "+file.getName());}return new Object[]{files,names};},result->{
        @SuppressWarnings("unchecked") List<File> files=(List<File>)result[0];@SuppressWarnings("unchecked") List<String> names=(List<String>)result[1];
        MaterialDialogs.builder(activity).setTitle("已安装语法 · 点击删除").setItems(names.toArray(String[]::new),(d,n)->MaterialDialogs.builder(activity).setTitle("删除语法“"+names.get(n)+"”？").setNegativeButton("取消",null).setPositiveButton("删除",(x,w)->Tasks.run(activity,()->{java.nio.file.Files.delete(files.get(n).toPath());return true;},done->Tasks.toast(activity,"已删除语法"))).show()).setNegativeButton("关闭",null).show();
    });}
    private SyntaxFiles(){}
}
