package org.example.filemanager;

import android.app.*;
import android.content.Context;
import android.widget.*;
import org.json.*;
import java.io.IOException;
import java.util.*;

final class EditorPreferences {
    static final String[] MENU={"自动换行","等宽字体","跳转到行","重新选择读取编码","转换保存编码","撤销","重做","保存","语法高亮","保留的文件","保留文件并最小化","切换注释","替换当前行","保存换行符","显示特殊字符","流畅模式","格式化 JSON","压缩 JSON","设置"};
    static final String[] SYMBOLS={"Tab","{","}","(",")","[","]","<",">","\"","'",";",":","=","+","-","*","/","\\","|","&","!","?","$","#","%","_",".",",","@","~","^","`"};
    static List<Integer> order(String encoded)throws IOException{
        try{JSONArray array=new JSONArray(encoded);List<Integer> order=new ArrayList<>();for(int i=0;i<array.length();i++){Object value=array.get(i);if(!(value instanceof Integer n)||n<0||n>=MENU.length||order.contains(n))throw new IOException("编辑器菜单顺序无效");order.add(n);}for(int i=0;i<MENU.length;i++)if(!order.contains(i))order.add(i);return order;}catch(JSONException e){throw new IOException("编辑器菜单顺序无效",e);}
    }
    static List<Integer> order(Context context){try{return order(context.getSharedPreferences("editor-display",0).getString("menu-order","[]"));}catch(IOException e){try{return order("[]");}catch(IOException impossible){throw new AssertionError(impossible);}}}
    static String[] symbols(String text)throws IOException{
        String[] result=text.trim().split("\\s+");if(text.isBlank()||result.length>80)throw new IOException("请输入 1–80 个符号或短语，以空格分隔");
        for(String value:result)if(value.length()>24||value.indexOf('\0')>=0)throw new IOException("每个快捷符号最多 24 字符");return result;
    }
    static String[] symbols(Context context){try{return symbols(context.getSharedPreferences("editor-display",0).getString("symbols",String.join(" ",SYMBOLS)));}catch(IOException e){return SYMBOLS;}}
    static void editSymbols(Activity activity){EditText input=Ui.input(activity,"符号之间用空格分隔，Tab 表示制表键");input.setSingleLine(false);input.setText(String.join(" ",symbols(activity)));
        AlertDialog dialog=MaterialDialogs.builder(activity).setTitle("编辑快捷符号栏").setView(input).setNegativeButton("取消",null).setNeutralButton("恢复默认",(d,n)->activity.getSharedPreferences("editor-display",0).edit().remove("symbols").apply()).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{symbols(input.getText().toString());activity.getSharedPreferences("editor-display",0).edit().putString("symbols",input.getText().toString().trim()).apply();dialog.dismiss();}catch(IOException e){input.setError(e.getMessage());}}));dialog.show();
    }
    static void reorder(Activity activity){List<Integer> order=order(activity);List<String> names=new ArrayList<>();Runnable labels=()->{names.clear();for(int id:order)names.add(MENU[id]);};labels.run();
        ListView list=new ListView(activity);ArrayAdapter<String> adapter=SettingsDesign.sortRows(list,names);
        Runnable save=()->{activity.getSharedPreferences("editor-display",0).edit().putString("menu-order",new JSONArray(order).toString()).apply();labels.run();adapter.notifyDataSetChanged();};
        DragOrder.install(list,(from,to)->{order.add(to,order.remove((int)from));save.run();});
        list.setOnItemClickListener((p,v,at,id)->MaterialDialogs.builder(activity).setTitle(names.get(at)).setItems(new String[]{"上移","下移","置顶"},(d,n)->{int to=n==0?Math.max(0,at-1):n==1?Math.min(order.size()-1,at+1):0;order.add(to,order.remove(at));save.run();}).show());
        MaterialDialogs.builder(activity).setTitle("编辑器菜单排序").setView(list).setNegativeButton("关闭",null).setNeutralButton("恢复默认",(d,n)->activity.getSharedPreferences("editor-display",0).edit().remove("menu-order").apply()).show();
    }
    private EditorPreferences(){}
}
