package org.example.filemanager;

import android.content.Intent;
import com.termux.terminal.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Parked PTYs keep running while the directory screen is visible and can be reattached. */
final class TerminalSessions {
    private static final Map<String,Entry> entries=new LinkedHashMap<>();
    static final class Entry implements TerminalSessionClient {
        final String id; String title; final Intent intent; final TerminalSession session;
        ExecutableFile.Prepared prepared; File marker; volatile File outputLog; final File logs;
        final String cwd,arguments; final boolean root,pipe,stopped;
        boolean claimed,finished;
        Entry(String id,String title,Intent intent,TerminalSession session,ExecutableFile.Prepared prepared,File marker,File outputLog,
                File logs,String cwd,String arguments,boolean root,boolean pipe,boolean stopped){
            this.id=id;this.title=title;this.intent=new Intent(intent);this.session=session;this.prepared=prepared;this.marker=marker;
            this.outputLog=outputLog;this.logs=logs;this.cwd=cwd;this.arguments=arguments;this.root=root;this.pipe=pipe;this.stopped=stopped;
        }
        @Override public void onSessionFinished(TerminalSession from){
            synchronized(this){if(claimed||finished)return;finished=true;}
            localizeFinished(from,stopped);
            String text=from.getEmulator()==null?"":from.getEmulator().getScreen().getTranscriptText();
            ExecutableFile.Prepared cleanup;File pid;
            synchronized(this){cleanup=prepared;prepared=null;pid=marker;marker=null;}
            trimFinished();
            Tasks.IO.execute(()->{try{if(outputLog==null){File log=File.createTempFile("terminal-output-",".txt",logs);Files.write(log.toPath(),text.getBytes(StandardCharsets.UTF_8));outputLog=log;}}
                catch(IOException e){android.util.Log.w("TerminalSessions","保存终端日志失败",e);}
                finally{try{if(cleanup!=null)cleanup.close();if(pid!=null)Files.deleteIfExists(pid.toPath());}catch(IOException e){android.util.Log.w("TerminalSessions","清理终端临时文件失败",e);}}});
        }
        public void onTextChanged(TerminalSession s){}public void onTitleChanged(TerminalSession s){}
        public void onCopyTextToClipboard(TerminalSession s,String text){}public void onPasteTextFromClipboard(TerminalSession s){}
        public void onBell(TerminalSession s){}public void onColorsChanged(TerminalSession s){}public void onTerminalCursorStateChange(boolean state){}
        public void setTerminalShellPid(TerminalSession s,int pid){}public Integer getTerminalCursorStyle(){return 0;}
        public void logError(String tag,String text){android.util.Log.e(tag,text);}public void logWarn(String tag,String text){android.util.Log.w(tag,text);}
        public void logInfo(String tag,String text){android.util.Log.i(tag,text);}public void logDebug(String tag,String text){android.util.Log.d(tag,text);}
        public void logVerbose(String tag,String text){android.util.Log.v(tag,text);}public void logStackTraceWithMessage(String tag,String text,Exception e){android.util.Log.e(tag,text,e);}
        public void logStackTrace(String tag,Exception e){android.util.Log.e(tag,e.getMessage(),e);}
    }
    static synchronized void park(Entry entry){entries.put(entry.id,entry);entry.session.updateTerminalSessionClient(entry);if(!entry.session.isRunning())entry.onSessionFinished(entry.session);trimFinished();}
    private static synchronized void trimFinished(){int finished=0;for(Entry entry:entries.values())if(!entry.session.isRunning())finished++;Iterator<Entry> iterator=entries.values().iterator();while(finished>12&&iterator.hasNext())if(!iterator.next().session.isRunning()){iterator.remove();finished--;}}
    static synchronized Entry take(String id){Entry entry=entries.remove(id);if(entry!=null)synchronized(entry){entry.claimed=true;}return entry;}
    static synchronized List<Entry> list(){return new ArrayList<>(entries.values());}
    static synchronized void rename(String id,String title){Entry entry=entries.get(id);if(entry!=null)entry.title=title;}
    static synchronized void forgetFinished(String id){Entry entry=entries.get(id);if(entry!=null&&!entry.session.isRunning())entries.remove(id);}
    static void localizeFinished(TerminalSession session,boolean stopped){
        TerminalEmulator emulator=session.getEmulator();if(emulator==null)return;
        String transcript=emulator.getScreen().getTranscriptText();
        if(transcript.contains("[进程已结束 - 按回车关闭]"))return;
        int exit=session.getExitStatus();String upstream="[Process completed"+(exit>0?" (code "+exit+")":exit<0?" (signal "+-exit+")":"")+" - press Enter]";
        int lines=(upstream.length()-1)/Math.max(1,emulator.mColumns);
        byte[] bytes=("\r"+(lines>0?"\u001b["+lines+"A":"")+"\u001b[J"+(stopped?"已停止":"exit "+exit)+"\r\n\r\n[进程已结束 - 按回车关闭]").getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes,bytes.length);
    }
    static void open(android.app.Activity activity,String cwd){
        List<Entry> available=list();
        java.util.function.Consumer<String> launch=id->{Intent intent=new Intent(activity,TerminalActivity.class).putExtra("interactive",true).putExtra("cwd",cwd);if(id!=null)intent.putExtra("terminal-session",id);activity.startActivity(intent);};
        if(available.isEmpty()){launch.accept(null);return;}
        String[] labels=new String[available.size()+1];labels[0]="＋ 新建 Shell 会话";
        for(int i=0;i<available.size();i++){Entry e=available.get(i);labels[i+1]=e.title+(e.session.isRunning()?" · 运行中":" · 已结束")+"\n"+e.cwd;}
        MaterialDialogs.builder(activity).setTitle("终端会话").setItems(labels,(d,n)->launch.accept(n==0?null:available.get(n-1).id))
                .setNegativeButton("取消",null).setNeutralButton("清理已结束会话",(d,n)->{for(Entry e:available)forgetFinished(e.id);}).show();
    }
    private TerminalSessions(){}
}
