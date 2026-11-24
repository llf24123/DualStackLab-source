package org.example.filemanager;

import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;
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
public class ManualAlignmentInstrumentedTest {
    Context context; UiDevice device; File work, left, right; FileStore store;
    ActivityScenario<MainActivity> screen;
    Map<String, Map<String, ?>> preferences = new HashMap<>();

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        store = new FileStore(context);
        for (String name : List.of("MainActivity", "directory-places", "appearance"))
            preferences.put(name, new HashMap<>(context.getSharedPreferences(name, 0).getAll()));
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "manual39-").toFile();
        left = new File(work, "left"); right = new File(work, "right"); assertTrue(left.mkdir()); assertTrue(right.mkdir());
        for (String name : List.of("1.txt", "2.txt", "10.txt", "image.png")) Files.write(new File(left, name).toPath(), new byte[]{1});
        context.getSharedPreferences("MainActivity", 0).edit().clear().putString("left", left.getPath()).putString("right", right.getPath()).commit();
        context.getSharedPreferences("directory-places", 0).edit().clear().putString("bookmarks", "[]").commit();
        context.getSharedPreferences("appearance", 0).edit().putString("mode", "light").commit();
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close(); store.delete(FileStore.local(work));
        for (var prefs : preferences.entrySet()) {
            var edit = context.getSharedPreferences(prefs.getKey(), 0).edit().clear();
            for (var item : prefs.getValue().entrySet()) {
                Object value = item.getValue(); String key = item.getKey();
                if (value instanceof String s) edit.putString(key, s); else if (value instanceof Boolean b) edit.putBoolean(key,b);
                else if (value instanceof Integer n) edit.putInt(key,n); else if (value instanceof Long n) edit.putLong(key,n);
            } edit.commit();
        }
    }
    static Object field(Object o, String name) { return Iteration38InstrumentedTest.field(o,name); }
    static Object invoke(Object o, String method, Object... args) {
        try { for (var m : o.getClass().getDeclaredMethods()) if (m.getName().equals(method) && m.getParameterCount()==args.length) {
            m.setAccessible(true); return m.invoke(o,args);
        } throw new NoSuchMethodException(method); } catch (Exception e) { throw new AssertionError(e); }
    }
    Object pane(MainActivity a, boolean leftPane) { return field(a,leftPane ? "left" : "right"); }
    List<FileStore.Node> entries(MainActivity a, boolean leftPane) { return (List<FileStore.Node>)field(pane(a,leftPane),"entries"); }
    UiObject2 object(BySelector selector) { UiObject2 view=device.wait(Until.findObject(selector),8000); assertNotNull(selector.toString(),view); return view; }
    void launch() { screen=ActivityScenario.launch(MainActivity.class); await(a->entries(a,true).size()==4); }
    void await(Predicate<MainActivity> ready) {
        boolean[] done={false}; long until=SystemClock.uptimeMillis()+8000;
        while(!done[0]&&SystemClock.uptimeMillis()<until) {screen.onActivity(a->done[0]=ready.test(a));if(!done[0])SystemClock.sleep(20);} assertTrue("UI condition timed out",done[0]);
    }
    void count(int n) { await(a->entries(a,true).size()==n); }
    void ready(boolean leftPane, String key) { await(a-> key.equals(field(pane(a,leftPane),"recordedDirectory")) && field(pane(a,leftPane),"loading")==null); }
    void navigate(boolean leftPane,FileStore.Node node) { screen.onActivity(a->invoke(pane(a,leftPane),"navigate",node)); ready(leftPane,node.key()); }
    void capture(String name) { assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null),"0639-"+name+".png"))); }
    File zip(File parent, String name, String entry, byte[] data) throws Exception {
        File file=new File(parent,name);
        try(var out=new ZipOutputStream(new FileOutputStream(file))) {out.putNextEntry(new ZipEntry(entry));out.write(data);out.closeEntry();}return file;
    }
    void openBookmark(String name,boolean directory) {
        int[] generation={0};screen.onActivity(a->generation[0]=(int)field(pane(a,true),"historyGeneration"));
        screen.onActivity(a->invoke(a,"directorySheet")); object(By.desc((directory?"目录快捷项 ":"文件书签 ")+name)).click();
        assertTrue(device.wait(Until.gone(By.desc("最近与书签面板")),8000));
        await(a->(int)field(pane(a,true),"historyGeneration")>generation[0]);
    }
    @Test public void filterSyntaxHandlesNegationRegexUnicodeAndLiteralPrefixes() {
        assertTrue(FileNameFilter.compile("ZIP",false).matches("file.zip"));
        assertFalse(FileNameFilter.compile("!zip",false).matches("FILE.ZIP"));
        assertTrue(FileNameFilter.compile("!zip",false).matches("notes.txt"));
        assertTrue(FileNameFilter.compile("/\\d",false).matches("img12.png"));
        assertTrue(FileNameFilter.compile("!/\\d",false).matches("image.png"));
        assertFalse(FileNameFilter.compile("!/\\d",false).matches("img12.png"));
        assertTrue(FileNameFilter.compile("/Ä",false).matches("ä.txt"));
        assertTrue(FileNameFilter.compile("\\!zip",false).matches("!zip.txt"));
        assertFalse(FileNameFilter.compile("\\!zip",false).matches("zip.txt"));
        assertTrue(FileNameFilter.compile("!zip",true).matches("!zip.txt"));
        assertFalse(FileNameFilter.compile("!zip",true).matches("readme.txt"));
        for(String prefix:List.of("","!","/","!/"))assertTrue(FileNameFilter.compile(prefix,false).matches("anything"));
    }
    @Test public void quickFilterRejectsBadRegexWithoutReplacingValidList() {
        launch(); object(By.desc("同步另一窗口")).longClick();
        object(By.desc("快速过滤关键字")).setText("!txt");count(1);
        object(By.desc("快速过滤关键字")).setText("/\\d");count(3);
        object(By.desc("快速过滤关键字")).setText("/["); object(By.text("完成")).click();
        assertTrue(device.hasObject(By.desc("顶栏快速过滤"))); count(3);
        screen.onActivity(a->assertEquals("/\\d",field(pane(a,true),"filter"))); capture("filter-error");
        object(By.desc("快速过滤关键字")).setText("!/\\d");count(1); object(By.text("完成")).click();
        screen.recreate();count(1);screen.onActivity(a->assertEquals("image.png",entries(a,true).get(0).name));
        object(By.desc("同步另一窗口")).longClick();object(By.desc("清除过滤")).click();count(4);
    }
    @Test public void historyJumpPreservesTheInterveningForwardDestinations() throws Exception {
        File child = new File(left, "child"), grand = new File(child, "grand");
        assertTrue(grand.mkdirs()); launchWithExtraFolder();
        navigate(true, FileStore.local(child)); ready(true, child.getPath());
        navigate(true, FileStore.local(grand)); ready(true, grand.getPath());
        object(By.desc("后退")).longClick(); object(By.text(left.getPath())).click(); ready(true, left.getPath());
        object(By.desc("前进")).click(); ready(true, child.getPath());
        object(By.desc("前进")).click(); ready(true, grand.getPath());
    }
    private void launchWithExtraFolder() {
        screen = ActivityScenario.launch(MainActivity.class);
        await(a -> entries(a, true).size() == 5);
    }
    @Test public void clearingNoMatchesAlsoClosesInlineEditorAndPersistsEmptyRule() {
        launch(); object(By.desc("同步另一窗口")).longClick();
        object(By.desc("快速过滤关键字")).setText("no-such-file"); count(0);
        object(By.textContains("点按清除过滤")).click(); count(4);
        assertFalse(device.hasObject(By.desc("顶栏快速过滤")));
        screen.recreate(); count(4);
    }
    @Test public void quickFilterRetainsExplicitRegexAndCancelRestoresIt() {
        launch();screen.onActivity(a->invoke(a,"filterDialog"));
        object(By.clazz(EditText.class)).setText("^1");object(By.text("正则表达式")).click();object(By.text("应用")).click();count(2);
        object(By.desc("同步另一窗口")).longClick();assertEquals("^1",object(By.desc("快速过滤关键字")).getText());
        screen.onActivity(a -> assertEquals(true, field(pane(a,true),"regex")));
        object(By.desc("快速过滤关键字")).setText("^2");count(1);object(By.text("取消")).click();count(2);
        screen.onActivity(a->{assertEquals("^1",field(pane(a,true),"filter"));assertEquals(true,field(pane(a,true),"regex"));});
    }
    @Test public void parentLongPressOpensJumpWithoutNavigatingAndMissingPathFallsBack() {
        launch();object(By.desc("上一级")).longClick();object(By.desc("跳转路径"));
        screen.onActivity(a->assertEquals(left.getPath(),((FileStore.Node)field(pane(a,true),"location")).path()));
        object(By.desc("跳转路径")).setText(new File(right,"absent/deeper").getPath()); object(By.text("确定")).click();ready(true,right.getPath());
        screen.onActivity(a->assertEquals(right.getPath(),((FileStore.Node)field(pane(a,false),"location")).path()));capture("path-jump");
    }
    @Test public void archiveSyncLocatesContainerAndPreservesSourceAndHistory() throws Exception {
        File zip=zip(work,"sample.zip","folder/inside.txt",new byte[]{7}); launch();
        FileStore.Node inside=FileStore.archiveNode(FileStore.local(zip),"folder/",true,0,0);navigate(true,inside);
        screen.onActivity(a->invoke(a,"synchronizeOtherPane"));ready(false,work.getPath());
        screen.onActivity(a->{
            assertSame(pane(a,true),field(a,"active"));assertEquals(inside.key(),((FileStore.Node)field(pane(a,true),"location")).key());
            assertTrue(((Set<?>)field(pane(a,false),"resultHighlights")).contains(zip.getPath()));
            assertEquals(1,((Deque<?>)field(pane(a,false),"backHistory")).size());
        });capture("archive-sync");
        screen.onActivity(a->invoke(a,"synchronizeOtherPane"));ready(false,work.getPath());
        screen.onActivity(a->{assertEquals(1,((Deque<?>)field(pane(a,false),"backHistory")).size());invoke(pane(a,false),"back");});ready(false,right.getPath());
    }
    @Test public void nestedArchiveSyncLocatesInnerArchiveInOuterPackage() throws Exception {
        File inner=zip(work,"inner.zip","nested/payload.txt",new byte[]{8});
        File outer=zip(work,"outer.zip","sub/inner.zip",Files.readAllBytes(inner.toPath()));launch();
        FileStore.Node source=FileStore.archiveNode(FileStore.local(outer),"sub/inner.zip",false,0,0);
        navigate(true,FileStore.archiveNode(source,"nested/",true,0,0));
        screen.onActivity(a->invoke(a,"synchronizeOtherPane"));
        FileStore.Node parent=FileStore.archiveNode(FileStore.local(outer),"sub/",true,0,0);ready(false,parent.key());
        screen.onActivity(a->{assertEquals("inner.zip",entries(a,false).get(0).name);assertTrue(((Set<?>)field(pane(a,false),"resultHighlights")).contains(source.key()));});
    }
    @Test public void fileLongPressCanCreateBookmarkAndClickLocatesWithoutOpeningEditor() {
        launch();object(By.text("2.txt")).longClick();object(By.desc("文件操作 添加书签")).click();
        await(a->new DirectoryPlaces(context).entries(true).size()==1);
        DirectoryPlaces.Entry entry=new DirectoryPlaces(context).entries(true).get(0);assertFalse(entry.directory());assertEquals(left.getPath(),entry.parentKey());
        navigate(true,FileStore.local(right));openBookmark("2.txt",false);ready(true,left.getPath());
        screen.onActivity(a->assertTrue(((Set<?>)field(pane(a,true),"resultHighlights")).contains(new File(left,"2.txt").getPath())));
        capture("file-bookmark");assertEquals(context.getPackageName(),device.getCurrentPackageName());
    }
    @Test public void hiddenAndMissingFileBookmarksKeepPaneUsable() throws Exception {
        context.getSharedPreferences("MainActivity",0).edit().putBoolean("showHidden",false).commit();
        File hidden=new File(left,".secret.txt");Files.write(hidden.toPath(),new byte[]{1});
        DirectoryPlaces places=new DirectoryPlaces(context);places.bookmark(DirectoryPlaces.Entry.from(FileStore.local(hidden),FileStore.local(left)));
        launch();openBookmark(hidden.getName(),false);await(a->entries(a,true).stream().anyMatch(n->n.name.equals(hidden.getName())));
        screen.onActivity(a->assertTrue(((Set<?>)field(pane(a,true),"resultHighlights")).contains(hidden.getPath())));
        Files.delete(hidden.toPath());openBookmark(hidden.getName(),false);count(4);ready(true,left.getPath());
        screen.onActivity(a->{assertTrue(((View)field(pane(a,true),"list")).isEnabled());invoke(pane(a,true),"up");});ready(true,work.getPath());
    }
    @Test public void archiveFileBookmarkFindsItsMember() throws Exception {
        File zip=zip(work,"bookmarked.zip","folder/readme.txt",new byte[]{5});
        FileStore.Node parent=FileStore.archiveNode(FileStore.local(zip),"folder/",true,0,0), file=FileStore.archiveNode(FileStore.local(zip),"folder/readme.txt",false,1,0);
        new DirectoryPlaces(context).bookmark(DirectoryPlaces.Entry.from(file,parent));launch();openBookmark(file.name,false);ready(true,parent.key());
        screen.onActivity(a->{assertEquals(file.name,entries(a,true).get(0).name);assertTrue(((Set<?>)field(pane(a,true),"resultHighlights")).contains(file.key()));});
    }
    @Test public void fileBookmarkBackupRetainsTypeParentAndCustomNameAlongsideLegacyDirectories() throws Exception {
        DirectoryPlaces places=new DirectoryPlaces(context);File file=new File(left,"2.txt");
        places.bookmark(new DirectoryPlaces.Entry(right.getPath(),"旧目录",right.getPath(),"",false));
        places.bookmark(DirectoryPlaces.Entry.from(FileStore.local(file),FileStore.local(left)));places.rename(file.getPath(),"常用配置");
        byte[] bytes=SettingsBackup.export(context);places.clear(true);SettingsBackup.apply(context,SettingsBackup.read(new ByteArrayInputStream(bytes)),false);
        List<DirectoryPlaces.Entry> values=places.entries(true);assertTrue(values.get(0).directory());assertFalse(values.get(1).directory());
        assertEquals("常用配置",values.get(1).label());assertEquals(left.getPath(),values.get(1).parentKey());
        DirectoryPlaces.Destination target=values.get(1).destination(store);assertEquals(file.getPath(),target.focusKey());assertEquals(left.getPath(),target.directory().path());
        JSONObject legacy=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        legacy.getJSONArray("bookmarks").getJSONObject(0).remove("directory");legacy.getJSONArray("bookmarks").getJSONObject(0).remove("parent");
        assertTrue(SettingsBackup.read(new ByteArrayInputStream(legacy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).bookmarks().get(0).directory());
    }
    @Test public void headerShowsUsableAndTotalAndSelectionCancelRestoresIt() {
        launch();screen.onActivity(a->{String text=((TextView)field(a,"subtitle")).getText().toString();assertTrue(text,text.contains(" · 可用 "));assertTrue(text,text.contains("/"));});
        object(By.text("1.txt")).longClick();device.pressBack();object(By.desc("同步另一窗口"));
        screen.onActivity(a->{String text=((TextView)field(a,"subtitle")).getText().toString();assertTrue(text.contains(" · 可用 "));assertFalse(text.contains("已选择"));});capture("main-light");
        context.getSharedPreferences("appearance",0).edit().putString("mode","dark").commit();screen.recreate();count(4);capture("main-dark");
    }
    @Test public void invalidSavedFilterCannotLeavePaneEmptyOrDisabled() {
        context.getSharedPreferences("MainActivity",0).edit().putString("filter-left","/[").commit();launch();
        screen.onActivity(a->{assertEquals("",field(pane(a,true),"filter"));assertTrue(((View)field(pane(a,true),"list")).isEnabled());});
    }
    @Test public void safArchiveSyncRemembersActualParentAcrossRecreation() throws Exception {
        var grant=(android.os.Bundle)invoke(new BatchCopyInstrumentedTest(),"grantFixture",context);
        Uri uri=Uri.parse(grant.getString("uri")); FileStore.Node documents=store.document(uri);
        try {
            File zip=zip(work,"saf.zip","folder/inside.txt",new byte[]{7});
            FileStore.Node archive=store.copy(FileStore.local(zip),documents,false);launch();navigate(true,documents);
            screen.onActivity(a->invoke(a,"browseArchive",archive));
            ready(true,FileStore.archiveNode(archive,"",true,0,0).key());screen.recreate();ready(true,FileStore.archiveNode(archive,"",true,0,0).key());
            screen.onActivity(a->invoke(a,"synchronizeOtherPane"));ready(false,documents.key());
            screen.onActivity(a->assertEquals("saf.zip",entries(a,false).get(0).name));
            DirectoryPlaces.Entry bookmark=DirectoryPlaces.Entry.from(archive,documents);
            DirectoryPlaces.Destination destination=bookmark.destination(store);
            assertEquals(documents.key(),destination.directory().key());assertEquals(archive.key(),destination.focusKey());
        } finally { if(screen!=null){screen.close();screen=null;}context.getContentResolver().call(Uri.parse("content://"+CountingDocumentsProvider.AUTHORITY),"reset",null,null); }
    }
    @Test public void archiveRootBookmarkAndRecentSurviveBackupImport() throws Exception {
        File zip=zip(work,"root.zip","readme.txt",new byte[]{1});DirectoryPlaces places=new DirectoryPlaces(context);
        FileStore.Node root=FileStore.archiveNode(FileStore.local(zip),"",true,0,0);
        places.bookmark(DirectoryPlaces.Entry.from(root));places.visit(root);
        FileStore.Node item=FileStore.archiveNode(FileStore.local(zip),"readme.txt",false,1,0);
        places.bookmark(DirectoryPlaces.Entry.from(item,root));
        byte[] bytes=SettingsBackup.export(context);
        SettingsBackup.Data data=SettingsBackup.read(new ByteArrayInputStream(bytes));
        assertEquals(root.key(),data.bookmarks().get(0).key());assertEquals(root.key(),data.recent().get(0).key());
        assertEquals(root.key(),data.bookmarks().get(1).parentKey());
        places.clear(true);SettingsBackup.apply(context,data,false);assertEquals(2,places.entries(true).size());
    }
}
