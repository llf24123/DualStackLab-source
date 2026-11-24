package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import io.github.rosemoe.sora.text.UndoManager;
import org.json.*;
import java.util.*;

final class EditorDocuments {
    record Entry(String id,String document,String name,String intent,boolean pinned){}
    private record History(UndoManager undo,String baseline,EditorDrafts.State state){}
    private static final LinkedHashMap<String,History> histories=new LinkedHashMap<>(8,.75f,true);
    static List<Entry> list(Context context){List<Entry> values=new ArrayList<>();try{JSONArray data=new JSONArray(context.getSharedPreferences("editor-documents",0).getString("values","[]"));for(int i=0;i<data.length();i++){JSONObject e=data.getJSONObject(i);values.add(new Entry(e.getString("id"),e.getString("document"),e.getString("name"),e.getString("intent"),e.optBoolean("pinned")));}}catch(JSONException ignored){}return values;}
    static Entry find(Context context,String document){for(Entry e:list(context))if(e.document.equals(document))return e;return null;}
    private static void save(Context context,List<Entry> values){JSONArray array=new JSONArray();try{for(Entry e:values)array.put(new JSONObject().put("id",e.id).put("document",e.document).put("name",e.name).put("intent",e.intent).put("pinned",e.pinned));context.getSharedPreferences("editor-documents",0).edit().putString("values",array.toString()).apply();}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    static void remember(Context context,EditorDrafts.State state,Intent intent,UndoManager undo,String baseline){
        Intent destination=new Intent(intent);destination.setClass(context,EditorActivity.class);destination.removeExtra("savedForCaller");destination.removeExtra("preview");destination.putExtra("keptDraft",state.id());destination.putExtra("kept",true);
        List<Entry> values=list(context);Entry old=find(context,state.document());int index=old==null?values.size():values.indexOf(old);
        if(old!=null)values.remove(index);values.add(index,new Entry(state.id(),state.document(),intent.getStringExtra("name")==null?"文本文件":intent.getStringExtra("name"),destination.toUri(0),old!=null&&old.pinned));save(context,values);
        synchronized(histories){histories.put(state.id(),new History(undo,baseline,state));while(histories.size()>8)histories.remove(histories.keySet().iterator().next());}
    }
    static EditorDrafts.State state(String id){synchronized(histories){History history=histories.get(id);return history==null?null:history.state;}}
    static UndoManager undo(String id,String baseline){synchronized(histories){History history=histories.get(id);return history!=null&&Objects.equals(history.baseline,baseline)?history.undo:null;}}
    static void show(Activity activity,Runnable beforeOpen){
        List<Entry> values=list(activity);ListView list=new ListView(activity);List<String> labels=new ArrayList<>();
        Runnable names=()->{labels.clear();for(Entry e:values)labels.add((e.pinned?"★ ":"")+e.name);};names.run();ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,labels);list.setAdapter(adapter);
        AlertDialog dialog=MaterialDialogs.builder(activity).setTitle("保留的文件").setView(list).setNegativeButton("关闭",null).create();
        list.setOnItemClickListener((p,v,pos,id)->{try{Intent intent=Intent.parseUri(values.get(pos).intent,0);intent.setClass(activity,EditorActivity.class);beforeOpen.run();dialog.dismiss();activity.startActivity(intent);if(activity instanceof EditorActivity)activity.finish();}catch(Exception e){Tasks.showError(activity,e);}});
        DragOrder.install(list,(from,to)->{values.add(to,values.remove((int)from));names.run();adapter.notifyDataSetChanged();save(activity,values);});
        float[] down=new float[2];list.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){down[0]=event.getX();down[1]=event.getY();}
            if(event.getActionMasked()==MotionEvent.ACTION_UP&&event.getX()-down[0]>Ui.dp(activity,64)&&Math.abs(event.getY()-down[1])<Ui.dp(activity,32)){
                int pos=list.pointToPosition((int)down[0],(int)down[1]);if(pos<0)return false;Entry entry=values.get(pos);
                MaterialDialogs.builder(activity).setTitle(entry.name).setItems(new String[]{entry.pinned?"取消置顶":"置顶","关闭文件"},(d,n)->{
                    if(n==0){values.remove(entry);Entry changed=new Entry(entry.id,entry.document,entry.name,entry.intent,!entry.pinned);if(changed.pinned)values.add(0,changed);else values.add(changed);}
                    else{values.remove(entry);synchronized(histories){histories.remove(entry.id);}if(activity instanceof EditorActivity editor)editor.releaseRetention(entry.document);}
                    save(activity,values);names.run();adapter.notifyDataSetChanged();
                }).show();MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);list.onTouchEvent(cancel);cancel.recycle();return true;
            }return false;
        });dialog.show();
        if(values.isEmpty())Tasks.toast(activity,"在文本编辑器点击“保留文件”后会显示在这里");
    }
    private EditorDocuments(){}
}
