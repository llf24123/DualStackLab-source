package org.example.filemanager;

import android.content.*;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import io.github.rosemoe.sora.widget.CodeEditor;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FileManualUiInstrumentedTest {
    Context context;UiDevice device;File work;Map<String,Map<String,?>> preferences=new HashMap<>();
    @Before public void setup()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());work=Files.createTempDirectory(context.getCacheDir().toPath(),"manual41-ui-").toFile();
        for(String name:List.of("appearance","editor-documents","file-manager","editor-display","MainActivity","directory-places")){preferences.put(name,new HashMap<>(context.getSharedPreferences(name,0).getAll()));context.getSharedPreferences(name,0).edit().clear().commit();}
        context.getSharedPreferences("appearance",0).edit().putString("mode","light").commit();
    }
    @After public void cleanup()throws Exception{
        for(var prefs:preferences.entrySet()){var edit=context.getSharedPreferences(prefs.getKey(),0).edit().clear();for(var item:prefs.getValue().entrySet()){Object value=item.getValue();if(value instanceof String v)edit.putString(item.getKey(),v);else if(value instanceof Boolean v)edit.putBoolean(item.getKey(),v);else if(value instanceof Integer v)edit.putInt(item.getKey(),v);else if(value instanceof Long v)edit.putLong(item.getKey(),v);else if(value instanceof Float v)edit.putFloat(item.getKey(),v);}edit.commit();}
        try(var paths=Files.walk(work.toPath())){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
    File file(String name,String text)throws IOException{File f=new File(work,name);Files.write(f.toPath(),text.getBytes(StandardCharsets.UTF_8));return f;}
    Object field(Object object,String name){return Iteration38InstrumentedTest.field(object,name);}
    void invoke(Object object,String method,Object...args){ManualAlignmentInstrumentedTest.invoke(object,method,args);}
    <T extends android.app.Activity> void waitFor(ActivityScenario<T> scenario,Predicate<T> condition){long until=SystemClock.uptimeMillis()+15000;boolean[] result={false};while(SystemClock.uptimeMillis()<until&&!result[0]){scenario.onActivity(a->result[0]=condition.test(a));if(!result[0])SystemClock.sleep(30);}assertTrue("activity condition timeout",result[0]);}
    UiObject2 object(BySelector selector){UiObject2 found=device.wait(Until.findObject(selector),5000);assertNotNull(selector.toString(),found);return found;}
    void screenshot(String name){device.waitForIdle();SystemClock.sleep(300);assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null),"0641-"+name+".png")));}
    Intent hex(FileStore.Node node){return new Intent(context,HexActivity.class).putExtra("node",node.key()).putExtra("name",node.name);}
    HexSession session(HexActivity activity){return (HexSession)field(activity,"session");}
    @Test public void hexEditsSavesAndRejectsExternalChanges()throws Exception{
        File f=file("sample.bin","abcdefgh");
        try(ActivityScenario<HexActivity> screen=ActivityScenario.launch(hex(FileStore.local(f)))){
            waitFor(screen,a->session(a).document!=null&&!session(a).busy);
            screenshot("hex-light");screen.onActivity(a->session(a).replace(new byte[]{'X','Y'},false));waitFor(screen,a->!session(a).busy);
            assertEquals("abcdefgh",new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            screen.onActivity(a->session(a).save(a));waitFor(screen,a->!session(a).busy);assertEquals("XYcdefgh",new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            Files.write(f.toPath(),"external".getBytes(StandardCharsets.UTF_8));screen.onActivity(a->session(a).replace(new byte[]{'Z'},false));waitFor(screen,a->!session(a).busy);screen.onActivity(a->session(a).save(a));
            object(By.textContains("原文件已在外部改变"));assertEquals("external",new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));object(By.text("确定")).click();
        }
    }
    @Test public void hexCanEditArchiveEntryAndPreservesOtherEntries()throws Exception{
        File zip=new File(work,"data.zip");try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zip))){for(String name:List.of("a.bin","keep.txt")){out.putNextEntry(new ZipEntry(name));out.write("original".getBytes(StandardCharsets.UTF_8));out.closeEntry();}}
        FileStore.Node entry=FileStore.archiveNode(FileStore.local(zip),"a.bin",false,8,0);
        try(ActivityScenario<HexActivity> screen=ActivityScenario.launch(hex(entry))){waitFor(screen,a->session(a).document!=null&&!session(a).busy);screen.onActivity(a->session(a).replace(new byte[]{'X'},false));waitFor(screen,a->!session(a).busy);screen.onActivity(a->session(a).save(a));waitFor(screen,a->!session(a).busy);
            assertEquals("Xriginal",new String(ArchiveOps.bytes(zip,"a.bin",100),StandardCharsets.UTF_8));assertEquals("original",new String(ArchiveOps.bytes(zip,"keep.txt",100),StandardCharsets.UTF_8));}
    }
    @Test public void hexSearchInspectorAndDarkLayout()throws Exception{
        context.getSharedPreferences("appearance",0).edit().putString("mode","dark").commit();byte[] data=new byte[512];for(int i=0;i<data.length;i++)data[i]=(byte)i;File f=new File(work,"bytes.bin");Files.write(f.toPath(),data);
        try(ActivityScenario<HexActivity> screen=ActivityScenario.launch(hex(FileStore.local(f)))){waitFor(screen,a->session(a).document!=null&&!session(a).busy);screenshot("hex-dark");screen.onActivity(a->invoke(a,"search"));object(By.text("搜索与替换"));screenshot("hex-search");object(By.text("取消")).click();screen.onActivity(a->invoke(a,"inspect"));object(By.text("高字节优先（大端）"));screenshot("hex-inspector");}
    }
    @Test public void hexRetainsUnsavedWorkThroughRecreation()throws Exception{
        File f=file("recreate.bin","abc");try(ActivityScenario<HexActivity> screen=ActivityScenario.launch(hex(FileStore.local(f)))){waitFor(screen,a->session(a).document!=null&&!session(a).busy);screen.onActivity(a->session(a).replace(new byte[]{'Z'},false));waitFor(screen,a->!session(a).busy);screen.recreate();waitFor(screen,a->session(a).document!=null&&!session(a).busy);screen.onActivity(a->{assertTrue(session(a).document.dirty());assertEquals('Z',session(a).page[0]);});}
    }
    @Test public void textCompareShowsActualDifferenceAndThemes()throws Exception{
        File a=file("old.txt","title\nremoved\nsame\nold\n"),b=file("new.txt","title\nsame\nnew\nadded\n");Intent intent=new Intent(context,FileCompareActivity.class).putExtra("left",a.getPath()).putExtra("right",b.getPath()).putExtra("mode","text");
        for(String theme:List.of("light","dark")){context.getSharedPreferences("appearance",0).edit().putString("mode",theme).commit();try(ActivityScenario<FileCompareActivity> screen=ActivityScenario.launch(intent)){waitFor(screen,x->!((Boolean)field(x,"busy"))&&((List<?>)field(x,"rows")).size()>0);screenshot("compare-"+theme);object(By.textContains("removed"));object(By.textContains("added"));}}
    }
    @Test public void retainedEditorRestoresTextAndUndoHistory()throws Exception{
        File f=file("retained.txt","before");Intent intent=new Intent(context,EditorActivity.class);FileStore.local(f).put(intent);
        try(ActivityScenario<EditorActivity> screen=ActivityScenario.launch(intent)){waitFor(screen,a->(Boolean)field(a,"loaded"));screen.onActivity(a->{CodeEditor editor=(CodeEditor)field(a,"editor");editor.getText().insert(0,6," after");invoke(a,"keepFile");});}
        try(ActivityScenario<EditorActivity> screen=ActivityScenario.launch(intent)){waitFor(screen,a->(Boolean)field(a,"loaded"));screen.onActivity(a->{CodeEditor editor=(CodeEditor)field(a,"editor");assertEquals("before after",editor.getText().toString());assertTrue(editor.canUndo());editor.undo();assertEquals("before",editor.getText().toString());invoke(a,"keepFile");});screenshot("editor-retained");screen.onActivity(a->invoke(a,"menu"));object(By.text("保留的文件")).click();object(By.text("retained.txt"));screenshot("editor-documents");}
        assertEquals("before",new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
    }
    @Test public void lineEndingChoiceChangesOnlySavedBytes()throws Exception{
        File f=file("line.txt","one\ntwo\n");Intent intent=new Intent(context,EditorActivity.class);FileStore.local(f).put(intent);
        try(ActivityScenario<EditorActivity> screen=ActivityScenario.launch(intent)){waitFor(screen,a->(Boolean)field(a,"loaded"));screen.onActivity(a->invoke(a,"lineEnding"));object(By.text("CRLF · Windows")).click();waitFor(screen,a->"\r\n".equals(field(a,"outputLineEnding")));screen.onActivity(a->{assertEquals("one\ntwo\n",((CodeEditor)field(a,"editor")).getText().toString());invoke(a,"save");});waitFor(screen,a->!((Boolean)field(a,"saving")));assertEquals("one\r\ntwo\r\n",new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}
    }
    @Test public void commentToggleCanBeUndone()throws Exception{
        File f=file("code.sh","echo one\necho two\n");Intent intent=new Intent(context,EditorActivity.class);FileStore.local(f).put(intent);
        try(ActivityScenario<EditorActivity> screen=ActivityScenario.launch(intent)){waitFor(screen,a->(Boolean)field(a,"loaded"));screen.onActivity(a->{CodeEditor editor=(CodeEditor)field(a,"editor");editor.setSelectionRegion(0,0,1,8);invoke(a,"toggleComment");assertEquals("# echo one\n# echo two\n",editor.getText().toString());editor.undo();assertEquals("echo one\necho two\n",editor.getText().toString());});}
    }
    @Test public void interactiveTerminalStartsSystemShell()throws Exception{
        try(ActivityScenario<TerminalActivity> screen=ActivityScenario.launch(new Intent(context,TerminalActivity.class).putExtra("interactive",true).putExtra("cwd",work.getPath()))){waitFor(screen,a->field(a,"session")!=null);screen.onActivity(a->((com.termux.terminal.TerminalSession)field(a,"session")).write("printf 'interactive-ok\\n'\r"));waitFor(screen,a->a.terminalText().contains("interactive-ok"));screen.onActivity(a->((com.termux.terminal.TerminalSession)field(a,"session")).write("exit\r"));waitFor(screen,a->!((com.termux.terminal.TerminalSession)field(a,"session")).isRunning());}
    }
    @Test public void gestureMarginAddsRequestedSpace()throws Exception{
        context.getSharedPreferences("file-manager",0).edit().putInt("gesture-mode",1).putInt("gesture-gap",28).commit();context.getSharedPreferences("MainActivity",0).edit().putString("left",work.getPath()).putString("right",work.getPath()).commit();
        try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){waitFor(screen,a->((View)field(a,"actions")).getPaddingBottom()==Ui.dp(a,28));screen.onActivity(a->{View actions=(View)field(a,"actions");assertEquals(Ui.dp(a,78),actions.getLayoutParams().height);});screenshot("bookmark-gap");}
    }
    private void searchSettings(ActivityScenario<SettingsActivity> screen,String query) {
        screen.onActivity(a->{EditText search=find(a.getWindow().getDecorView(),EditText.class);assertNotNull(search);search.setText(query);});
        device.waitForIdle();
    }
    private <T extends View> T find(View view,Class<T> type) {
        if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){T result=find(group.getChildAt(i),type);if(result!=null)return result;}return null;
    }
    private TextView textView(View view,String value) {
        if(view instanceof TextView text&&text.getText().toString().equals(value))return text;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){TextView found=textView(group.getChildAt(i),value);if(found!=null)return found;}return null;
    }
    @Test public void groupedSettingsSearchAndOptionsActuallyApply()throws Exception{
        try(ActivityScenario<SettingsActivity> screen=ActivityScenario.launch(SettingsActivity.class)){
            object(By.desc("搜索设置"));screenshot("settings-light");
            searchSettings(screen,"文件名最大行数");object(By.descStartsWith("文件名最大行数，")).click();object(By.text("8 行")).click();
            waitFor(screen,a->FileListDisplay.lines(a)==8);
            searchSettings(screen,"界面动画");object(By.descStartsWith("界面动画，")).click();waitFor(screen,a->!FileManagerSettings.prefs(a).getBoolean("animations",true));assertFalse(Ui.motionEnabled());
            searchSettings(screen,"not-a-setting-41");object(By.text("没有匹配的设置"));
            searchSettings(screen,"文本编辑器");object(By.text("没有匹配的设置"));
        }
        try(ActivityScenario<SettingsActivity> screen=ActivityScenario.launch(SettingsActivity.editorIntent(context))){
            searchSettings(screen,"Tab 宽度");object(By.text("Tab 宽度"));screenshot("settings-editor");
        }
    }
    @Test public void editorDisplayPreferencesAffectRealEditor()throws Exception{
        context.getSharedPreferences("editor-display",0).edit().putBoolean("wordwrap",true).putBoolean("auto-indent",false)
                .putBoolean("bracket-completion",false).putBoolean("smooth",true).putBoolean("special",false)
                .putFloat("textSize",18.5f).putInt("tabWidth",8).commit();
        File f=file("appearance.txt","one\ntwo\n");Intent intent=new Intent(context,EditorActivity.class);FileStore.local(f).put(intent);
        try(ActivityScenario<EditorActivity> screen=ActivityScenario.launch(intent)){
            waitFor(screen,a->(Boolean)field(a,"loaded"));screen.onActivity(a->{CodeEditor editor=(CodeEditor)field(a,"editor");
                assertTrue(editor.isWordwrap());assertEquals(8,editor.getTabWidth());assertFalse(editor.getProps().autoIndent);
                assertFalse(editor.getProps().symbolPairAutoCompletion);assertTrue(editor.getProps().disableTextExtracting);assertFalse(editor.isRenderFunctionCharacters());
                float scaled=a.getResources().getDisplayMetrics().density*a.getResources().getConfiguration().fontScale;
                assertEquals(18.5f,editor.getTextSizePx()/scaled,.1f);
            });
        }
    }
    @Test public void retainedCleanFileLoadsExternalUpdatesAndCanSave()throws Exception{
        File f=file("clean-retained.txt","first");Intent intent=new Intent(context,EditorActivity.class);FileStore.local(f).put(intent);
        try(ActivityScenario<EditorActivity> screen=ActivityScenario.launch(intent)){waitFor(screen,a->(Boolean)field(a,"loaded"));screen.onActivity(a->invoke(a,"keepFile"));}
        Files.write(f.toPath(),"external update".getBytes(StandardCharsets.UTF_8));
        try(ActivityScenario<EditorActivity> screen=ActivityScenario.launch(intent)){
            waitFor(screen,a->(Boolean)field(a,"loaded"));screen.onActivity(a->{CodeEditor editor=(CodeEditor)field(a,"editor");assertEquals("external update",editor.getText().toString());editor.getText().insert(0,15," + edited");invoke(a,"save");});
            waitFor(screen,a->!((Boolean)field(a,"saving")));assertEquals("external update + edited",new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
        }
    }
    @Test public void hexKeyboardKeepsFortyEightDpTouchWidth()throws Exception{
        File f=file("keys.bin","0123456789");
        try(ActivityScenario<HexActivity> screen=ActivityScenario.launch(hex(FileStore.local(f)))){
            waitFor(screen,a->session(a).document!=null&&!session(a).busy);
            screen.onActivity(a->{LinearLayout keys=(LinearLayout)field(a,"keypad");for(int i=0;i<keys.getChildCount();i++){
                LinearLayout row=(LinearLayout)keys.getChildAt(i);for(int j=0;j<row.getChildCount();j++){View key=row.getChildAt(j);assertTrue(key.getWidth()>=Ui.dp(a,48));assertTrue(key.getHeight()>=Ui.dp(a,48));}
            }});
        }
    }
    @Test public void multilineNamesOnlyExpandLongRows()throws Exception{
        String longName="very-long-file-name-".repeat(6)+".txt";file("short.txt","x");file(longName,"x");
        context.getSharedPreferences("MainActivity",0).edit().putString("left",work.getPath()).putString("right",work.getPath()).commit();
        int[] originalHeight={0};
        try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
            waitFor(screen,a->textView((View)field(a,"left"),"short.txt")!=null);
            screen.onActivity(a->originalHeight[0]=((View)textView((View)field(a,"left"),"short.txt").getParent().getParent()).getHeight());
            FileManagerSettings.prefs(context).edit().putInt("name-lines",8).commit();screen.recreate();
            waitFor(screen,a->textView((View)field(a,"left"),longName)!=null&&textView((View)field(a,"left"),longName).getLineCount()>1);
            screen.onActivity(a->{TextView shortText=textView((View)field(a,"left"),"short.txt"),longText=textView((View)field(a,"left"),longName);
                assertEquals(originalHeight[0],((View)shortText.getParent().getParent()).getHeight());assertTrue(longText.getHeight()>shortText.getHeight());
            });screenshot("multiline-names");
        }
    }
    @Test public void fileMenuReorderingKeepsActionsMappedCorrectly()throws Exception{
        File f=file("property-target.txt","content");context.getSharedPreferences("MainActivity",0).edit().putString("left",work.getPath()).putString("right",work.getPath()).commit();
        FileManagerSettings.prefs(context).edit().putString("menu-order","[7,3,0,1]").commit();
        try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
            waitFor(screen,a->textView((View)field(a,"left"),f.getName())!=null);
            screen.onActivity(a->{Object pane=field(a,"left");((Set<String>)field(pane,"selected")).add(f.getPath());invoke(a,"selectionMenu");});
            object(By.text("属性")).click();object(By.textContains("property-target.txt"));object(By.textContains("SHA-256"));
            assertTrue(f.exists());object(By.text("关闭")).click();
        }
    }
}
