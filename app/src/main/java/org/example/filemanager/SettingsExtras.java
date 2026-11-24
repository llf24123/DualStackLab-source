package org.example.filemanager;

import android.content.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Optional fields keep older settings backups readable. All values are validated before applying. */
final class SettingsExtras {
    static final List<String> GROUPS = List.of("file-manager", "editor-display", "terminal-appearance");
    private static final Set<String> FLAGS = Set.of("root","auto-shizuku","seconds","short-year","app-badges","animations","sidebar-bookmarks","bookmarks-top","gesture-side","terminal-awake","editor-backup","preserve-time","list-permissions");
    private static final Set<String> EDITOR_FLAGS = Set.of("wordwrap","monospace","lineNumbers","special","smooth","word-boundaries","pin-lines","wrap-arrows","whitespace","auto-indent","tabs","indent-guides","bracket-completion","highlight","magnifier","pinch-zoom","symbol-bar","unicode-special","detect-external","double-back","completion-enter");
    static JSONObject export(Context c) throws Exception {
        JSONObject result = new JSONObject();
        for (String group : GROUPS) result.put(group, new JSONObject(c.getSharedPreferences(group,0).getAll()));
        JSONObject syntax = new JSONObject();
        for (File file : MtsxSyntax.installed(c)) syntax.put(file.getName(), new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        result.put("syntax",syntax); validate(result); return result;
    }
    static void validate(JSONObject data) throws Exception {
        for (Iterator<String> keys=data.keys();keys.hasNext();) { String key=keys.next();if(!GROUPS.contains(key)&&!key.equals("syntax"))throw new IOException("未知扩展设置："+key); }
        for(String group:GROUPS){JSONObject values=data.optJSONObject(group);if(data.has(group)&&values==null)throw new IOException("设置分组无效："+group);if(values==null)continue;
            for(Iterator<String> keys=values.keys();keys.hasNext();){String key=keys.next();Object v=values.get(key);boolean valid=false;
                if(group.equals("file-manager")){
                    if(FLAGS.contains(key))valid=v instanceof Boolean;
                    else if(key.equals("name-lines"))valid=integer(v,1,8);
                    else if(key.equals("accent"))valid=integer(v,0,4);
                    else if(key.equals("gesture-mode"))valid=integer(v,0,2);
                    else if(key.equals("gesture-gap"))valid=integer(v,6,28);
                    else if(key.startsWith("startup-")&&(key.equals("startup-left")||key.equals("startup-right")))valid=v instanceof String s&&Set.of("home","last").contains(s);
                    else if(key.equals("su"))valid=v instanceof String s&&!s.contains("\n")&&!s.contains("\0")&&s.length()<4096&&(s.equals("su")||new File(s).isAbsolute());
                    else if(key.equals("workspace"))valid=v instanceof String s&&!s.contains("\0")&&s.length()<4096&&new File(s).isAbsolute();
                    else if(key.equals("date-format")&&v instanceof String s){valid=!s.isBlank()&&s.length()<=80;new java.text.SimpleDateFormat(s,Locale.CHINA);}
                    else if(key.equals("menu-order")&&v instanceof String s){FileMenuOrder.parse(s);valid=true;}
                }else if(group.equals("editor-display")){
                    if(EDITOR_FLAGS.contains(key))valid=v instanceof Boolean;
                    else if(key.equals("textSize"))valid=v instanceof Number n&&Float.isFinite(n.floatValue())&&n.floatValue()>=9&&n.floatValue()<=32;
                    else if(key.equals("tabWidth"))valid=v instanceof Integer n&&(n==2||n==4||n==8);
                    else if(key.equals("line-spacing"))valid=integer(v,0,3);
                    else if(key.equals("completion-rows"))valid=integer(v,2,10);
                    else if(key.equals("symbols")&&v instanceof String s){EditorPreferences.symbols(s);valid=true;}
                    else if(key.equals("menu-order")&&v instanceof String s){EditorPreferences.order(s);valid=true;}
                    else if(key.equals("retention"))valid=integer(v,0,2);
                    else if(key.equals("paging-mib"))valid=v instanceof Integer n&&Set.of(1,2,4,8,16).contains(n);
                    else if(key.equals("smooth-threshold"))valid=v instanceof Integer n&&Set.of(50000,100000,200000,500000,1000000).contains(n);
                }else if(key.equals("textSize"))valid=integer(v,10,30);
                if(!valid)throw new IOException("设置值无效："+group+" / "+key);
            }
        }
        JSONObject syntax=data.optJSONObject("syntax");if(data.has("syntax")&&syntax==null)throw new IOException("语法备份无效");
        if(syntax!=null){if(syntax.length()>128)throw new IOException("语法文件过多");for(Iterator<String> keys=syntax.keys();keys.hasNext();){String name=keys.next();Object v=syntax.get(name);
            if(!name.matches("[0-9A-Fa-f]{64}\\.mtsx")||!(v instanceof String s)||s.getBytes(StandardCharsets.UTF_8).length>MtsxSyntax.LIMIT)throw new IOException("语法文件备份无效");
            MtsxSyntax parsed=MtsxSyntax.parse((String)v);String expected=HexDocument.hex(java.security.MessageDigest.getInstance("SHA-256").digest(parsed.name.getBytes(StandardCharsets.UTF_8)))+".mtsx";
            if(!expected.equals(name))throw new IOException("语法文件名称与内容不匹配");
        }}
    }
    private static boolean integer(Object v,int min,int max){return v instanceof Integer n&&n>=min&&n<=max;}
    record Snapshot(Map<String,Map<String,?>> preferences, Map<String,byte[]> syntax) { }
    // Rollback must preserve damaged local files too, so importing a valid backup can repair them.
    static Snapshot snapshot(Context c) throws IOException {
        Map<String,Map<String,?>> preferences=new LinkedHashMap<>();
        for(String group:GROUPS)preferences.put(group,new HashMap<>(c.getSharedPreferences(group,0).getAll()));
        Map<String,byte[]> syntax=new LinkedHashMap<>();
        for(File file:MtsxSyntax.installed(c))syntax.put(file.getName(),Files.readAllBytes(file.toPath()));
        return new Snapshot(preferences,syntax);
    }
    static void restore(Context c,Snapshot snapshot) throws IOException {
        if(snapshot==null)return;
        IOException failure=null;
        for(var group:snapshot.preferences.entrySet()){
            var edit=c.getSharedPreferences(group.getKey(),0).edit().clear();
            for(var item:group.getValue().entrySet()){
                String key=item.getKey();Object value=item.getValue();
                if(value instanceof Boolean v)edit.putBoolean(key,v);else if(value instanceof String v)edit.putString(key,v);
                else if(value instanceof Integer v)edit.putInt(key,v);else if(value instanceof Long v)edit.putLong(key,v);
                else if(value instanceof Float v)edit.putFloat(key,v);
            }
            if(!edit.commit())failure=new IOException("恢复设置失败："+group.getKey());
        }
        for(File file:MtsxSyntax.installed(c))if(!snapshot.syntax.containsKey(file.getName()))Files.deleteIfExists(file.toPath());
        for(var item:snapshot.syntax.entrySet())Files.write(new File(MtsxSyntax.folder(c),item.getKey()).toPath(),item.getValue());
        RootShell.configure(FileManagerSettings.prefs(c).getString("su","su"));RootAccess.setAutomatic(FileManagerSettings.prefs(c).getBoolean("root",true));
        if(failure!=null)throw failure;
    }
    static void apply(Context c,JSONObject data,boolean merge)throws Exception{
        if(data==null)return;validate(data);
        for(String group:GROUPS){JSONObject values=data.optJSONObject(group);if(values==null)continue;SharedPreferences prefs=c.getSharedPreferences(group,0);SharedPreferences.Editor edit=prefs.edit();if(!merge)edit.clear();
            for(Iterator<String> keys=values.keys();keys.hasNext();){String key=keys.next();Object v=values.get(key);
                if(v instanceof Boolean b)edit.putBoolean(key,b);else if(v instanceof String s)edit.putString(key,s);else if(group.equals("editor-display")&&key.equals("textSize"))edit.putFloat(key,((Number)v).floatValue());else edit.putInt(key,((Number)v).intValue());
            }if(!edit.commit())throw new IOException("设置写入失败："+group);
        }
        JSONObject syntax=data.optJSONObject("syntax");if(syntax!=null){Set<String> incoming=new HashSet<>();for(Iterator<String> keys=syntax.keys();keys.hasNext();){String name=keys.next();incoming.add(name);File target=new File(MtsxSyntax.folder(c),name);if(!merge||!target.exists())MtsxSyntax.install(c,syntax.getString(name).getBytes(StandardCharsets.UTF_8));}
            if(!merge)for(File file:MtsxSyntax.installed(c))if(!incoming.contains(file.getName()))Files.deleteIfExists(file.toPath());
        }
        RootShell.configure(FileManagerSettings.prefs(c).getString("su","su"));RootAccess.setAutomatic(FileManagerSettings.prefs(c).getBoolean("root",true));
    }
    private SettingsExtras() { }
}
