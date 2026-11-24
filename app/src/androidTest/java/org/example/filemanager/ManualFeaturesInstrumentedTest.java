package org.example.filemanager;

import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ManualFeaturesInstrumentedTest {
    Context context; FileStore store; File work, left, right; UiDevice device; ActivityScenario<MainActivity> screen;
    Map<String, Map<String, ?>> old = new HashMap<>();
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); store = new FileStore(context);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for (String name : List.of("MainActivity", "appearance", "directory-places", RenameTemplates.PREFS, OpenMethods.PREFS)) {
            old.put(name, new HashMap<>(context.getSharedPreferences(name, 0).getAll())); context.getSharedPreferences(name, 0).edit().clear().commit();
        }
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "manual40-").toFile();
        left = new File(work, "left"); right = new File(work, "right"); assertTrue(left.mkdir()); assertTrue(right.mkdir());
        write(left,"1.txt","one"); write(left,"2.txt","two"); write(left,"10.txt","ten");
        context.getSharedPreferences("MainActivity", 0).edit().putString("left",left.getPath()).putString("right",right.getPath()).commit();
        context.getSharedPreferences("appearance", 0).edit().putString("mode","light").commit();
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close(); store.delete(FileStore.local(work));
        for (var prefs : old.entrySet()) {
            var edit = context.getSharedPreferences(prefs.getKey(),0).edit().clear();
            for (var e : prefs.getValue().entrySet()) {
                Object value=e.getValue(); if(value instanceof String s)edit.putString(e.getKey(),s);else if(value instanceof Boolean b)edit.putBoolean(e.getKey(),b);
                else if(value instanceof Integer n)edit.putInt(e.getKey(),n);else if(value instanceof Long n)edit.putLong(e.getKey(),n);
            }edit.commit();
        }
    }
    File write(File parent,String name,String data)throws IOException{File file=new File(parent,name);Files.write(file.toPath(),data.getBytes(java.nio.charset.StandardCharsets.UTF_8));return file;}
    RenameExpression expression(String value){return new RenameExpression(new RenameExpression.Config(value,RenameExpression.DEFAULT_DATE,"","",false));}
    Object field(Object o,String name){return Iteration38InstrumentedTest.field(o,name);}
    Object pane(MainActivity a,boolean l){return field(a,l?"left":"right");}
    List<FileStore.Node> entries(MainActivity a,boolean l){return (List<FileStore.Node>)field(pane(a,l),"entries");}
    void invoke(Object o,String name,Object...args){ManualAlignmentInstrumentedTest.invoke(o,name,args);}
    void launch(){screen=ActivityScenario.launch(MainActivity.class);await(a->entries(a,true).size()==3);}
    void await(Predicate<MainActivity> condition){boolean[] ok={false};long deadline=SystemClock.uptimeMillis()+10000;while(!ok[0]&&SystemClock.uptimeMillis()<deadline){screen.onActivity(a->ok[0]=condition.test(a));if(!ok[0])SystemClock.sleep(20);}assertTrue("UI condition timed out",ok[0]);}
    UiObject2 object(BySelector selector){UiObject2 value=device.wait(Until.findObject(selector),8000);assertNotNull(selector.toString(),value);return value;}
    void screenshot(String name){device.waitForIdle();SystemClock.sleep(Ui.MOTION_PANEL+150);assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null),"0640-"+name+".png")));}
    @Test public void expressionsHandlePaddingExtensionDirectoriesAndLiteralBraces()throws Exception{
        FileStore.Node a=FileStore.local(new File(left,"1.txt"));
        assertEquals("item08.txt",expression("item{z8}{S}").name(store,a,0,4));
        assertEquals("item11.txt",expression("item{z8}{S}").name(store,a,3,4));
        assertEquals("1.txt.bak",expression("{P}{S}.bak").name(store,a,0,1));
        assertEquals("{1}.txt",expression("{{{P}}}{S}").name(store,a,0,1));
        assertEquals("left",expression("{P}{S}").name(store,FileStore.local(left),0,1));
        FileStore.Node hidden=FileStore.local(write(work,".hidden","x"));assertEquals(".hidden",expression("{P}{S}").name(store,hidden,0,1));
        for(String value:List.of("{unknown}","{P","{9999999999999999999999999999}","abc}"))try{expression(value);fail(value);}catch(IllegalArgumentException expected){}
        try{expression("{9223372036854775807}").name(store,a,1,2);fail();}catch(IOException expected){}
    }
    @Test public void replaceSupportsCaptureGroupsAndBlocksComplexPatterns()throws Exception{
        FileStore.Node a=FileStore.local(new File(left,"10.txt"));
        var rule=new RenameExpression(new RenameExpression.Config("{P}{S}","yyyy","(\\d+)","no-$1",true));
        assertEquals("no-10.txt",rule.name(store,a,0,1));
        rule=new RenameExpression(new RenameExpression.Config("{P}{S}","yyyy","1","$literal",false));
        assertEquals("$literal0.txt",rule.name(store,a,0,1));
        rule=new RenameExpression(new RenameExpression.Config("a".repeat(2000)+"!","yyyy","(a+)+$","",true));
        long now=SystemClock.uptimeMillis();assertEquals("a".repeat(2000)+"!",rule.name(store,a,0,1));assertTrue(SystemClock.uptimeMillis()-now<3000);
        rule=new RenameExpression(new RenameExpression.Config("ab{P}","yyyy","(?<=a)b","",true));
        assertEquals("a10",rule.name(store,a,0,1));
    }
    @Test public void photoTimeUsesExifAndFallsBackToModification()throws Exception{
        File photo=new File(work,"photo.jpg");Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
        try(OutputStream output=new FileOutputStream(photo)){assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,output));}bitmap.recycle();
        ExifInterface exif=new ExifInterface(photo);exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2024:05:06 07:08:09");exif.saveAttributes();
        assertEquals("2024-05-06_07-08-09.jpg",expression("{E}{S}").name(store,FileStore.local(photo),0,1));
        FileStore.Node text=FileStore.local(new File(left,"1.txt"));assertEquals(expression("{T}").name(store,text,0,1),expression("{E}").name(store,text,0,1));
    }
    @Test public void apkMetadataReadsRealApplicationAndRejectsNonApks()throws Exception{
        File apk=new File(work,"sample.apk");Files.copy(new File(context.getApplicationInfo().sourceDir).toPath(),apk.toPath());
        var info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
        assertEquals(context.getPackageName()+"_"+info.versionName+"_"+info.getLongVersionCode()+".apk",expression("{AP}_{AV}_{AC}{S}").name(store,FileStore.local(apk),0,1));
        assertTrue(expression("{AN}{S}").name(store,FileStore.local(apk),0,1).endsWith(".apk"));
        var invalid=BatchRename.preview(store,List.of(FileStore.local(new File(left,"1.txt"))),expression("{AN}"));assertFalse(invalid.valid());assertTrue(invalid.items().get(0).error().contains("APK"));
    }
    @Test public void expressionPreviewChecksConflictsAndArchiveExecutionPreservesData()throws Exception{
        List<FileStore.Node> nodes=store.list(FileStore.local(left));
        assertFalse(BatchRename.preview(store,nodes,expression("same.txt")).valid());
        assertFalse(BatchRename.preview(store,nodes,expression("../{P}")).valid());
        File zip=new File(work,"items.zip");try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zip))){for(String name:List.of("one.txt","two.txt")){out.putNextEntry(new ZipEntry(name));out.write(name.getBytes());out.closeEntry();}}
        FileStore.Node root=FileStore.archiveNode(FileStore.local(zip),"",true,0,0);
        var plan=BatchRename.preview(store,root,store.list(root),expression("{P}_{z1}{S}"));assertTrue(plan.valid());BatchRename.execute(store,plan);
        try(ZipFile read=new ZipFile(zip)){assertNull(read.getEntry("one.txt"));assertNotNull(read.getEntry("one_1.txt"));assertEquals("two.txt",new String(read.getInputStream(read.getEntry("two_2.txt")).readAllBytes()));}
    }
    @Test public void templatesAndOpenMethodsRoundTripMergeAndLegacyBackups()throws Exception{
        var config=new RenameExpression.Config("{P}{S}.bak",RenameExpression.DEFAULT_DATE,"","",false);
        RenameTemplates.save(context,"备份",config);OpenMethods.remember(context,"x.TXT",new OpenMethods.Choice(0,"","","编辑文本"));
        context.getSharedPreferences(OpenMethods.PREFS,0).edit().putString("order","[2,0]").commit();
        byte[] bytes=SettingsBackup.export(context);var backup=SettingsBackup.read(new ByteArrayInputStream(bytes));
        RenameTemplates.remove(context,"备份");OpenMethods.forget(context,".txt");SettingsBackup.apply(context,backup,false);
        assertEquals(config,RenameTemplates.load(context).get("备份"));assertEquals(0,OpenMethods.find(context,"OTHER.TxT").action());assertEquals(2,(int)OpenMethods.order(context).get(0));
        OpenMethods.remember(context,"x.txt",new OpenMethods.Choice(1,"","","浏览压缩包"));SettingsBackup.apply(context,backup,true);assertEquals(1,OpenMethods.find(context,"x.txt").action());
        JSONObject legacy=new JSONObject(new String(bytes));legacy.remove("renameTemplates");legacy.remove("openMethods");legacy.remove("openOrder");
        SettingsBackup.apply(context,SettingsBackup.read(new ByteArrayInputStream(legacy.toString().getBytes())),false);assertEquals(1,OpenMethods.find(context,"x.txt").action());
        assertEquals(config,RenameTemplates.load(context).get("备份"));
    }
    @Test public void invalidBackupAndDefaultsAreRejectedWithoutChangingSettings()throws Exception{
        byte[] bytes=SettingsBackup.export(context);JSONObject broken=new JSONObject(new String(bytes));broken.put("openOrder",new JSONArray("[0,0]"));
        try{SettingsBackup.read(new ByteArrayInputStream(broken.toString().getBytes()));fail();}catch(IOException expected){}
        assertEquals("",OpenMethods.extension("README"));assertEquals("",OpenMethods.extension(".config"));assertEquals(".zip",OpenMethods.extension("a.ZIP"));
        try{OpenMethods.remember(context,"no-extension",new OpenMethods.Choice(0,"","","编辑文本"));fail();}catch(IOException expected){}
        assertTrue(OpenMethods.load(context).isEmpty());
        broken=new JSONObject(new String(bytes));broken.put("renameTemplates",new JSONObject().put("bad",new JSONObject().put("expression","{oops}").put("date","yyyy").put("find","").put("replacement","").put("regex",false)));
        try{SettingsBackup.read(new ByteArrayInputStream(broken.toString().getBytes()));fail();}catch(IOException expected){}
    }
    @Test public void externalIntentCarriesExactMimeAndReadGrant(){
        Uri uri=Uri.parse("content://"+context.getPackageName()+".files/shared/name.txt");ComponentName component=new ComponentName("test.app","test.app.Reader");
        Intent intent=OpenMethods.intent(uri,"text/plain",component);assertEquals(component,intent.getComponent());assertEquals("text/plain",intent.getType());
        assertEquals(uri,intent.getData());assertEquals(uri,intent.getClipData().getItemAt(0).getUri());assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0,intent.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }
    @Test public void inlineFilteringDoesNotCoverListAndCannotLeakToOtherPane(){
        launch();object(By.desc("同步另一窗口")).longClick();object(By.desc("快速过滤关键字")).setText("10");await(a->entries(a,true).size()==1);
        var bar=object(By.desc("顶栏快速过滤")).getVisibleBounds();var list=object(By.desc("左窗口文件列表")).getVisibleBounds();assertTrue(bar.bottom<=list.top);
        screenshot("filter-light");
        screen.onActivity(a->invoke(a,"activate",pane(a,false)));
        await(a->field(a,"inlineFilter")==null);screen.onActivity(a->{assertEquals("10",field(pane(a,true),"filter"));assertEquals("",field(pane(a,false),"filter"));});
        object(By.desc("同步另一窗口")).longClick();object(By.desc("快速过滤关键字")).setText("/[ ");object(By.desc("取消过滤")).click();
        screen.onActivity(a->assertEquals("",field(pane(a,false),"filter")));
    }
    @Test public void inlineFilterRecreationKeepsLastValidResultsAndBackCancels(){
        launch();object(By.desc("同步另一窗口")).longClick();object(By.desc("快速过滤关键字")).setText("10");await(a->entries(a,true).size()==1);
        screen.recreate();await(a->entries(a,true).size()==1);assertFalse(device.hasObject(By.desc("顶栏快速过滤")));
        object(By.desc("同步另一窗口")).longClick();object(By.desc("快速过滤关键字")).setText("2");await(a->entries(a,true).size()==1&&entries(a,true).get(0).name.equals("2.txt"));
        screen.onActivity(a->invoke(a,"handleBack"));await(a->entries(a,true).size()==1&&entries(a,true).get(0).name.equals("10.txt"));
        context.getSharedPreferences("appearance",0).edit().putString("mode","dark").commit();screen.recreate();await(a->entries(a,true).size()==1);
        object(By.desc("同步另一窗口")).longClick();screenshot("filter-dark");object(By.desc("清除过滤")).click();await(a->entries(a,true).size()==3);
    }
    @Test public void inlineFilterNavigationClearsSessionAndDoesNotReapplyOldQuery(){
        launch();object(By.desc("同步另一窗口")).longClick();object(By.desc("快速过滤关键字")).setText("2");
        screen.onActivity(a->invoke(pane(a,true),"navigate",FileStore.local(right)));
        await(a->field(a,"inlineFilter")==null&&((FileStore.Node)field(pane(a,true),"location")).file.equals(right));
        SystemClock.sleep(250);screen.onActivity(a->assertEquals("",field(pane(a,true),"filter")));
    }
    @Test public void renameTemplateUiPreviewsAndExecutesOnlyAfterConfirmation(){
        launch();screen.onActivity(a->BatchRename.show(a,new FileStore(a),FileStore.local(left),List.of(FileStore.local(new File(left,"1.txt")),FileStore.local(new File(left,"2.txt"))),plan->{Tasks.run(a,()->BatchRename.execute(new FileStore(a),plan),result->invoke(pane(a,true),"reload"));}));
        object(By.desc("文件名表达式")).setText("note_{z8}{S}");object(By.text("保存模板")).click();object(By.desc("模板名称")).setText("笔记");object(By.text("保存")).click();
        screenshot("rename-form");object(By.text("预览")).click();object(By.desc("批量重命名预览"));assertTrue(new File(left,"1.txt").exists());screenshot("rename-preview");
        object(By.text("执行")).click();await(a->entries(a,true).stream().anyMatch(n->n.name.equals("note_8.txt")));assertFalse(new File(left,"1.txt").exists());assertTrue(new File(left,"note_9.txt").exists());
    }
    @Test public void batchNumberingFollowsVisibleNaturalOrder() throws Exception {
        verifyVisibleNumbering(0, List.of("1.txt", "2.txt", "10.txt"), List.of("one", "two-two", "ten-ten-ten"));
    }
    @Test public void batchNumberingFollowsVisibleSizeOrder() throws Exception {
        verifyVisibleNumbering(2, List.of("10.txt", "2.txt", "1.txt"), List.of("ten-ten-ten", "two-two", "one"));
    }
    private void verifyVisibleNumbering(int sort, List<String> names, List<String> contents) throws Exception {
        write(left, "2.txt", "two-two"); write(left, "10.txt", "ten-ten-ten");
        context.getSharedPreferences("MainActivity", 0).edit().putInt("sort-left", sort).commit();
        launch();
        screen.onActivity(a -> {
            assertEquals(names, entries(a, true).stream().map(n -> n.name).toList());
            // Force a provider enumeration order different from the displayed order.
            List<FileStore.Node> raw = (List<FileStore.Node>) field(pane(a, true), "all");
            raw.clear(); raw.addAll(entries(a, true)); Collections.reverse(raw);
            invoke(pane(a, true), "selectAll");
        });
        object(By.desc("操作")).click(); object(By.desc("文件操作 批量重命名")).click();
        object(By.desc("文件名表达式")).setText("item_{1}{S}"); object(By.text("预览")).click();
        object(By.desc("批量重命名预览")); object(By.text("执行")).click();
        await(a -> entries(a, true).size() == 3 && entries(a, true).stream().allMatch(n -> n.name.startsWith("item_")));
        for (int i = 0; i < contents.size(); i++)
            assertEquals(contents.get(i), new String(Files.readAllBytes(new File(left, "item_" + (i + 1) + ".txt").toPath()), java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void builtinDefaultLongPressIsRememberedAndManagerCanDeleteIt()throws Exception{
        launch();screen.onActivity(a->invoke(a,"openWith",FileStore.local(new File(left,"1.txt"))));object(By.desc("打开方式 编辑文本")).longClick();object(By.text("设为默认打开方式")).click();
        await(a->OpenMethods.find(context,"a.txt")!=null);assertEquals(0,OpenMethods.find(context,"a.txt").action());object(By.text("1.txt")).click();object(By.desc("光标位置 · 跳转到行"));device.pressBack();
        try(ActivityScenario<OpenMethodsActivity> manager=ActivityScenario.launch(OpenMethodsActivity.class)){
            object(By.text(".txt"));screenshot("open-defaults");object(By.text(".txt")).longClick();object(By.text("删除默认方式")).click();object(By.textContains("尚未设置默认方式"));assertNull(OpenMethods.find(context,"a.txt"));
        }
    }
    @Test public void missingExternalDefaultFallsBackToChooser()throws Exception{
        OpenMethods.remember(context,"a.txt",new OpenMethods.Choice(-1,"not.installed/not.installed.Reader","text/plain","已卸载应用"));launch();object(By.text("1.txt")).click();object(By.desc("打开方式 编辑文本"));assertNull(OpenMethods.find(context,"a.txt"));
    }
    @Test public void externalPickerDefaultActuallyOpensFileAcrossApplicationUid()throws Exception{
        launch();screen.onActivity(a->invoke(a,"openWith",FileStore.local(new File(left,"1.txt"))));object(By.text("类型")).click();object(By.text("文本")).click();
        object(By.desc("外部打开方式 外部验证")).longClick();object(By.text("设为默认打开方式")).click();
        object(By.text("1.txt")).click();object(By.text("收到文件：one"));object(By.text("类型：text/plain"));screenshot("external-open");device.pressBack();
    }
    @Test public void reorderPersistsAndRendersInPicker(){
        launch();screen.onActivity(OpenMethods::reorder);object(By.text("查看图片")).click();object(By.text("上移")).click();await(a->OpenMethods.order(context).get(1)==2);object(By.text("关闭")).click();
        screen.onActivity(a->invoke(a,"openWith",FileStore.local(new File(left,"1.txt"))));
        assertTrue(object(By.desc("打开方式 查看图片")).getVisibleBounds().left<object(By.desc("打开方式 浏览压缩包")).getVisibleBounds().left);
        screenshot("open-grid");
    }
}
