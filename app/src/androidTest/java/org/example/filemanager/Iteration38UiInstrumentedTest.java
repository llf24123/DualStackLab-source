package org.example.filemanager;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class Iteration38UiInstrumentedTest {
    Context context; UiDevice device; File work, left, right; ActivityScenario<MainActivity> screen;
    Map<String, ?> old; String theme;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        old = new HashMap<>(context.getSharedPreferences("MainActivity", 0).getAll()); theme = Ui.themeMode(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "ui38-").toFile(); left = new File(work, "left"); right = new File(work, "right"); assertTrue(left.mkdir()); assertTrue(right.mkdir());
        for (String name : List.of("1.txt", "2.txt", "10.txt", "image.png")) Files.write(new File(left, name).toPath(), name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        context.getSharedPreferences("MainActivity", 0).edit().clear().putString("left", left.getPath()).putString("right", right.getPath()).commit();
        context.getSharedPreferences("appearance", 0).edit().putString("mode", "light").commit();
        screen = ActivityScenario.launch(MainActivity.class); object(By.text("10.txt"));
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close(); new FileStore(context).delete(FileStore.local(work));
        var editor = context.getSharedPreferences("MainActivity", 0).edit().clear();
        for (var e : old.entrySet()) { if (e.getValue() instanceof String s) editor.putString(e.getKey(), s); else if (e.getValue() instanceof Boolean b) editor.putBoolean(e.getKey(), b); else if (e.getValue() instanceof Integer n) editor.putInt(e.getKey(), n); }
        editor.commit(); context.getSharedPreferences("appearance", 0).edit().putString("mode", theme).commit();
    }
    UiObject2 object(BySelector selector) { var found = device.wait(Until.findObject(selector), 10000); if (found == null) { screenshot("missing-control"); try { device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "0638-missing-control.xml")); } catch (IOException ignored) { } } assertNotNull(selector.toString(), found); return found; }
    static Object field(Object o, String name) { return Iteration38InstrumentedTest.field(o, name); }
    Object pane(MainActivity a) { return field(a, "left"); }
    void invoke(Object o, String name) { try { var m = o.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(o); } catch (Exception e) { throw new AssertionError(e); } }
    void awaitEntries(int count) {
        boolean[] ready = {false}; long until = SystemClock.uptimeMillis() + 7000;
        while (!ready[0] && SystemClock.uptimeMillis() < until) { screen.onActivity(a -> ready[0] = ((List<?>)field(pane(a), "entries")).size() == count); if (!ready[0]) SystemClock.sleep(20); } assertTrue(ready[0]);
    }
    void screenshot(String name) { assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "0638-" + name + ".png"))); }
    @Test public void cancellingSelectionRestoresDirectoryHeaderAndRemovesHint() {
        object(By.text("1.txt")).longClick(); object(By.desc("文件操作 复制"));
        screen.onActivity(a -> assertEquals("已选择 1 项", ((TextView)field(a, "subtitle")).getText().toString()));
        assertFalse(device.hasObject(By.textContains("长按全选"))); device.pressBack();
        object(By.desc("同步另一窗口"));
        screen.onActivity(a -> assertFalse(((TextView)field(a, "subtitle")).getText().toString().contains("已选择")));
        sidewaysThenVerticalGestureSelectsOnlyStartingFile();
        object(By.desc("取消")).click(); object(By.desc("同步另一窗口"));
        screen.onActivity(a -> assertFalse(((TextView)field(a, "subtitle")).getText().toString().contains("已选择")));
    }
    @Test public void iconLongPressOpensSameSelectionMenuAsText() {
        object(By.desc("左窗口文件列表")).findObject(By.desc("1.txt")).longClick(); object(By.desc("文件操作 属性"));
        screen.onActivity(a -> assertEquals(1, ((Set<?>)field(pane(a), "selected")).size()));
        device.pressBack(); object(By.desc("同步另一窗口"));
        object(By.text("1.txt")).longClick(); object(By.desc("文件操作 属性"));
        screen.onActivity(a -> assertEquals(1, ((Set<?>)field(pane(a), "selected")).size()));
    }
    @Test public void sidewaysThenVerticalGestureSelectsOnlyStartingFile() {
        screen.onActivity(a -> {
            ListView list = (ListView)field(pane(a), "list"); View row = list.getChildAt(1);
            float x = list.getWidth() * .25f, to = list.getWidth() * .85f, y = row.getTop() + row.getHeight() / 2f;
            long t = SystemClock.uptimeMillis(); send(list,t,t,MotionEvent.ACTION_DOWN,x,y);
            send(list,t,t+80,MotionEvent.ACTION_MOVE,to,y);
            send(list,t,t+160,MotionEvent.ACTION_MOVE,to,list.getHeight()-5);
            send(list,t,t+240,MotionEvent.ACTION_UP,to,list.getHeight()-5);
            Set<?> selected = (Set<?>)field(pane(a), "selected"); assertEquals(1, selected.size()); assertTrue(selected.iterator().next().toString().endsWith("/1.txt"));
        });
    }
    @Test public void swipeDoesNotExtendAnArmedExplicitRange() {
        sidewaysThenVerticalGestureSelectsOnlyStartingFile(); object(By.desc("连选")).click();
        screen.onActivity(a -> {
            ListView list = (ListView)field(pane(a), "list"); View row = list.getChildAt(4);
            float x = list.getWidth() * .25f, to = list.getWidth() * .85f, y = row.getTop() + row.getHeight() / 2f;
            long t = SystemClock.uptimeMillis(); send(list,t,t,MotionEvent.ACTION_DOWN,x,y);
            send(list,t,t+80,MotionEvent.ACTION_MOVE,to,y); send(list,t,t+120,MotionEvent.ACTION_UP,to,y);
            Set<?> selected = (Set<?>)field(pane(a), "selected"); assertEquals(2, selected.size());
            assertFalse((boolean)field(pane(a), "rangeSelecting"));
        });
    }
    private void send(View view,long down,long time,int action,float x,float y) { MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0); view.dispatchTouchEvent(e); e.recycle(); }
    @Test public void longPressSyncFiltersImmediatelyAndClearRestoresAllFiles() {
        object(By.desc("同步另一窗口")).longClick(); object(By.desc("快速过滤关键字")).setText("2"); awaitEntries(1);
        screen.onActivity(a -> { assertEquals("2.txt", ((List<FileStore.Node>)field(pane(a), "entries")).get(0).name); assertTrue(((List<?>)field(field(a,"right"),"entries")).isEmpty()); });
        screenshot("quick-filter"); object(By.text("完成")).click();
        object(By.desc("同步另一窗口")).longClick(); object(By.desc("清除过滤")).click(); awaitEntries(4);
        object(By.desc("同步另一窗口")).click(); object(By.desc("右窗口文件列表"));
        long until=SystemClock.uptimeMillis()+5000; boolean[] same={false};
        while(!same[0]&&SystemClock.uptimeMillis()<until){screen.onActivity(a->same[0]=((FileStore.Node)field(field(a,"right"),"location")).key().equals(((FileStore.Node)field(pane(a),"location")).key()));if(!same[0])SystemClock.sleep(20);}assertTrue(same[0]);
    }
    @Test public void cancelledRenamePreviewDoesNotReopenDialog() throws Exception {
        CountDownLatch blocked=new CountDownLatch(2),release=new CountDownLatch(1);
        for(int i=0;i<2;i++)Tasks.BROWSE.execute(()->{blocked.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException ignored){}});
        assertTrue(blocked.await(5,TimeUnit.SECONDS));
        try {
            screen.onActivity(a -> BatchRename.show(a,new FileStore(a),FileStore.local(left),List.of(FileStore.local(new File(left,"1.txt"))),plan->fail("Should not execute")));
            object(By.text("预览")).click(); object(By.text("取消")).click(); release.countDown();
            Tasks.BROWSE.submit(()->{}).get(5,TimeUnit.SECONDS); device.waitForIdle();
            assertFalse(device.hasObject(By.desc("批量重命名预览")));
        } finally { release.countDown(); }
    }
    @Test public void cancelQuickFilterRestoresScrollAfterAsynchronousFiltering() throws Exception {
        for (int i = 0; i < 1300; i++) Files.write(new File(left, String.format(java.util.Locale.ROOT, "entry-%04d.txt", i)).toPath(), new byte[]{1});
        screen.onActivity(a -> invoke(pane(a), "reload")); awaitEntries(1304);
        screen.onActivity(a -> ((ListView)field(pane(a), "list")).setSelectionFromTop(900, -7));
        device.waitForIdle(); int[] position = new int[2];
        screen.onActivity(a -> { ListView list = (ListView)field(pane(a), "list"); position[0] = list.getFirstVisiblePosition(); position[1] = list.getChildAt(0).getTop(); });
        object(By.desc("同步另一窗口")).longClick(); object(By.desc("快速过滤关键字")).setText("entry-1299"); awaitEntries(1);
        object(By.text("取消")).click(); awaitEntries(1304); device.waitForIdle();
        screen.onActivity(a -> { ListView list = (ListView)field(pane(a), "list"); assertEquals(position[0], list.getFirstVisiblePosition()); assertEquals(position[1], list.getChildAt(0).getTop()); });
    }
    @Test public void analysisFeaturesAreReachableAndRenderResults() {
        object(By.desc("更多操作")).click(); UiObject2 menu=object(By.desc("更多操作列表"));
        menu.scroll(Direction.DOWN,.3f); SystemClock.sleep(400); device.waitForIdle(); object(By.desc("目录占用排行")).click();
        object(By.text("开始扫描")).click(); object(By.text("重新扫描")); screenshot("storage-usage"); device.pressBack();
        object(By.desc("更多操作")).click(); menu=object(By.desc("更多操作列表")); menu.scroll(Direction.DOWN,.3f); SystemClock.sleep(400); device.waitForIdle(); object(By.desc("重复文件查找")).click();
        object(By.text("开始扫描")).click(); object(By.text("重新扫描")); screenshot("duplicate-files"); device.pressBack();
    }
    @Test public void directoryReportSurvivesActivityRecreation() throws Exception {
        Intent intent=new Intent(context,DirectoryCompareActivity.class).putExtra("left",left.getPath()).putExtra("right",right.getPath());
        try(ActivityScenario<DirectoryCompareActivity> compare=ActivityScenario.launch(intent)){
            object(By.text("开始校验")).click(); object(By.textContains("已比较 4 项")); compare.recreate(); object(By.textContains("已比较 4 项"));
            assertTrue(object(By.text("导出结果")).isEnabled()); screenshot("directory-report");
        }
    }
    @Test public void referenceLayoutHasFiveActionsAndSupportsBothThemes() throws Exception {
        for(String name:List.of("Alarms","Android","backups","DCIM","Download","Movies","Music"))assertTrue(new File(left,name).mkdir());
        screen.onActivity(a->invoke(pane(a),"reload")); awaitEntries(11); screenshot("main-light");
        screen.onActivity(a->{LinearLayout actions=(LinearLayout)field(a,"actions");assertEquals(5,actions.getChildCount());assertEquals("同步另一窗口",actions.getChildAt(3).getContentDescription());assertEquals("上一级",actions.getChildAt(4).getContentDescription());});
        context.getSharedPreferences("appearance",0).edit().putString("mode","dark").commit();screen.recreate();object(By.text("Download")); screenshot("main-dark");
    }
}
