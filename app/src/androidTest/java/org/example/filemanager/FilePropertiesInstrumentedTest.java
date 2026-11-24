package org.example.filemanager;

import android.content.Context;
import android.os.SystemClock;
import android.system.Os;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FilePropertiesInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work, left, right;
    private ActivityScenario<MainActivity> screen;
    private final Map<String, Map<String, ?>> previous = new LinkedHashMap<>();
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.setOrientationNatural(); device.waitForIdle();
        for (String name : List.of("MainActivity", "file-manager", "directory-places", "appearance"))
            previous.put(name, new HashMap<>(context.getSharedPreferences(name, 0).getAll()));
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "properties-ui-").toFile();
        left = directory(work, "left"); right = directory(work, "right");
        context.getSharedPreferences("MainActivity", 0).edit().clear().putString("left", left.getPath())
                .putString("right", right.getPath()).putString("home-left", left.getPath()).putString("home-right", right.getPath()).commit();
        FileManagerSettings.prefs(context).edit().putBoolean("root", false)
                .putString("startup-left", "last").putString("startup-right", "last").commit();
        context.getSharedPreferences("directory-places", 0).edit().clear().putString("bookmarks", "[]").commit();
        context.getSharedPreferences("appearance", 0).edit().putString("mode", "light").commit();
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        device.setOrientationNatural(); device.unfreezeRotation();
        if (work != null) try (var paths = Files.walk(work.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
        for (var saved : previous.entrySet()) {
            var edit = context.getSharedPreferences(saved.getKey(), 0).edit().clear();
            for (var item : saved.getValue().entrySet()) {
                Object value = item.getValue(); String key = item.getKey();
                if (value instanceof String text) edit.putString(key, text);
                else if (value instanceof Boolean flag) edit.putBoolean(key, flag);
                else if (value instanceof Integer number) edit.putInt(key, number);
                else if (value instanceof Long number) edit.putLong(key, number);
                else if (value instanceof Float number) edit.putFloat(key, number);
                else if (value instanceof Set<?> set) edit.putStringSet(key, (Set<String>) set);
            }
            edit.commit();
        }
        RootAccess.setAutomatic(FileManagerSettings.prefs(context).getBoolean("root", true));
    }
    private File directory(File parent, String name) throws IOException { return Files.createDirectory(new File(parent, name).toPath()).toFile(); }
    private File file(File parent, String name, int bytes) throws IOException { File file = new File(parent, name); Files.write(file.toPath(), new byte[bytes]); return file; }
    private UiObject2 object(BySelector selector) {
        UiObject2 result = device.wait(Until.findObject(selector), 1200);
        if (result == null) {
            for (Direction direction : List.of(Direction.DOWN, Direction.UP)) {
                for (int i = 0; i < 6; i++) {
                    UiObject2 scroll = device.findObject(By.clazz("android.widget.ScrollView"));
                    if (scroll == null) break;
                    boolean moved = scroll.scroll(direction, 0.7f); result = device.findObject(selector);
                    if (result != null) return result;
                    if (!moved) break;
                }
            }
        }
        if (result == null) result = device.wait(Until.findObject(selector), 8000);
        assertNotNull(selector.toString(), result); return result;
    }
    private void launch() { launch(false); }
    private void launch(boolean landscape) {
        screen = ActivityScenario.launch(MainActivity.class);
        screen.onActivity(activity -> activity.setRequestedOrientation(landscape
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        device.waitForIdle();
    }
    private void properties(File file) {
        object(By.text(file.getName())).longClick(); object(By.desc("文件操作 属性")).click();
    }
    private void exact(long bytes) {
        object(By.desc("属性大小"));
        await(() -> { UiObject2 value = device.findObject(By.desc("属性大小")); return value != null
                && value.getText().endsWith("(" + bytes + " 字节)") && !value.getText().startsWith("计算中"); });
    }
    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) { if (condition.getAsBoolean()) return; SystemClock.sleep(40); }
        assertTrue("UI condition did not complete", condition.getAsBoolean());
    }
    private Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private Object invoke(Object target, String name) {
        try { var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void choose(List<File> files) {
        for (File file : files) object(By.text(file.getName()));
        screen.onActivity(activity -> {
            Object pane = field(activity, "left"); Set<String> selected = (Set<String>) field(pane, "selected"); selected.clear();
            for (FileStore.Node node : (List<FileStore.Node>) field(pane, "entries"))
                if (files.stream().anyMatch(file -> file.getPath().equals(node.path()))) selected.add(node.key());
            assertEquals(files.size(), selected.size()); invoke(pane, "selectionChanged"); invoke(activity, "selectionMenu");
        });
        UiObject2 properties = object(By.desc("文件操作 属性")); assertTrue("Multi-select properties must be enabled", properties.isEnabled()); properties.click();
    }
    private void click(BySelector selector) {
        for (int i=0;i<3;i++) {
            try { object(selector).click(); device.waitForIdle(); return; }
            catch (StaleObjectException stale) { if(i==2) throw stale; }
        }
    }
    private void more(String action) { click(By.text("更多")); click(By.text(action)); }
    private void input(BySelector selector, String text) {
        object(selector).setText(text); device.waitForIdle();
    }
    private void screenshot(String name) {
        assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "0646-properties-" + name + ".png")));
    }

    @Test public void singleFileShowsExactBytesAndDefersChecksumsUntilRequested() throws Exception {
        File selected = file(left, "single.bin", 4097); launch(); properties(selected); exact(4097);
        assertNotNull(device.findObject(By.desc("属性修改时间")));
        assertNull(device.findObject(By.desc("输入校验值"))); assertNull(device.findObject(By.desc("文件摘要值，可长按复制")));
        assertNotNull(device.findObject(By.text("校验"))); assertNotNull(device.findObject(By.text("更多")));
        screenshot("light-single");
    }
    @Test public void propertySizeRefreshesOutdatedDirectoryListingMetadata() throws Exception {
        File selected = file(left, "stale-size.bin", 4); FileStore.Node stale = FileStore.local(selected);
        Files.write(selected.toPath(), new byte[8193]); launch();
        screen.onActivity(activity -> FileProperties.show(activity, List.of(stale), () -> { }));
        exact(8193);
    }
    @Test public void folderManifestComputesRecursivelyAndReportsConcreteMismatch() throws Exception {
        File folder = directory(left, "manifest"), nested = directory(folder, "nested");
        Files.write(new File(nested, "known.txt").toPath(), "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        launch(); screen.onActivity(activity -> ChecksumManifest.show(activity, List.of(FileStore.local(folder))));
        await(() -> { UiObject2 progress = device.findObject(By.desc("清单计算进度"));
            return progress != null && progress.getText().contains("成功 1 项"); });
        assertTrue(object(By.desc("SHA256 清单预览")).getText().contains("manifest/nested/known.txt"));
        assertTrue(object(By.text("分享清单文件")).isEnabled());
        input(By.desc("SHA256 清单输入"), "b".repeat(64) + "  manifest/nested/known.txt");
        object(By.text("比对清单")).click();
        await(() -> { UiObject2 value = device.findObject(By.desc("清单比对结果"));
            return value != null && value.getText().contains("不匹配：manifest/nested/known.txt"); });
        assertTrue(object(By.desc("清单比对结果")).getText().contains("实际 15e2b0d3"));
        screenshot("manifest-light");
        object(By.text("关闭")).click();
    }
    @Test public void propertyLongPressRowsHaveFortyEightDpTouchHeight() throws Exception {
        File selected = file(left, "touch-targets.bin", 4); launch();
        android.app.AlertDialog[] shown = {null};
        screen.onActivity(activity -> shown[0] = FileProperties.show(activity, List.of(FileStore.local(selected)), () -> { }));
        exact(4);
        screen.onActivity(activity -> {
            android.view.ViewGroup decor = (android.view.ViewGroup) shown[0].getWindow().getDecorView();
            Deque<android.view.View> pending = new ArrayDeque<>(); pending.push(decor); int checked=0;
            while (!pending.isEmpty()) {
                android.view.View view=pending.pop();
                if (view.isLongClickable() && view.getContentDescription()!=null && view.getContentDescription().toString().startsWith("属性")) {
                    assertTrue(view.getContentDescription().toString(),view.getHeight()>=Ui.dp(activity,48)); checked++;
                }
                if (view instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) pending.push(group.getChildAt(i));
            }
            assertTrue(checked>=4);
        });
    }
    @Test public void directoryShowsRecursiveBytesAndExcludesSelectedRootFromFolderCount() throws Exception {
        File selected = directory(left, "folder"), child = directory(selected, "child");
        file(selected, "one", 47); file(child, "two", 53); file(child, "empty", 0);
        launch(); properties(selected); exact(100);
        assertEquals("3", object(By.desc("属性文件数")).getText()); assertEquals("1", object(By.desc("属性文件夹数")).getText());
        assertNull(device.findObject(By.text("校验")));
        screenshot("light-multiple");
    }
    @Test public void multipleSelectionsEnablePropertiesAndShowCombinedTotal() throws Exception {
        File first = file(left, "first.bin", 31), folder = directory(left, "folder");
        file(folder, "inside.bin", 59); launch(); choose(List.of(first, folder)); exact(90);
        assertEquals("2", object(By.desc("属性文件数")).getText()); assertEquals("1", object(By.desc("属性文件夹数")).getText());
        assertTrue(object(By.desc("属性统计状态")).getText().contains("文件夹数包含所选目录"));
        assertNull(device.findObject(By.text("校验")));
    }
    @Test public void batchPermissionEditorWritesEverySelectedFileAndLeavesNeighbourUntouched() throws Exception {
        File a = file(left, "first.bin", 1), b = file(left, "second.bin", 2), neighbour = file(left, "neighbour.bin", 3);
        for (File file : List.of(a, b, neighbour)) Os.chmod(file.getPath(), 0644);
        launch(); choose(List.of(a, b)); more("文件权限"); input(By.desc("八进制权限"), "600");
        object(By.text("保存权限")).click();
        await(() -> { try { return (Os.stat(a.getPath()).st_mode & 07777) == 0600 && (Os.stat(b.getPath()).st_mode & 07777) == 0600; }
            catch (Exception e) { return false; } });
        assertEquals(0644, Os.stat(neighbour.getPath()).st_mode & 07777);
    }
    @Test public void dateEditorWritesChosenTimestampAndRejectsInvalidCalendarDate() throws Exception {
        File selected = file(left, "dated.bin", 7); launch(); properties(selected); exact(7); more("修改时间");
        input(By.desc("修改时间输入"), "2026-02-30 10:11:12"); object(By.text("确定")).click();
        assertNotNull(device.findObject(By.desc("修改时间输入")));
        String requested = "2026-01-02 03:04:05";
        input(By.desc("修改时间输入"), requested); object(By.text("确定")).click();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT); format.setLenient(false);
        long expected = format.parse(requested).getTime() / 1000;
        await(() -> { try { return Os.stat(selected.getPath()).st_mtime == expected; } catch (Exception e) { return false; } });
        await(() -> { UiObject2 text = device.findObject(By.desc("属性修改时间")); return text != null && requested.equals(text.getText()); });
    }
    @Test public void directoryPermissionScopeChangesOnlyRequestedDescendantTypes() throws Exception {
        File selected = directory(left, "scope"), nested = directory(selected, "nested"), payload = file(nested, "payload", 3);
        Os.chmod(selected.getPath(), 0755); Os.chmod(nested.getPath(), 0755); Os.chmod(payload.getPath(), 0644);
        launch(); properties(selected); exact(3); more("文件权限"); input(By.desc("八进制权限"), "700");
        object(By.text("同时应用到所有子文件")).click(); object(By.text("保存权限")).click();
        await(() -> { try { return (Os.stat(selected.getPath()).st_mode & 07777) == 0700 && (Os.stat(payload.getPath()).st_mode & 07777) == 0700; }
            catch (Exception e) { return false; } });
        assertEquals(0755, Os.stat(nested.getPath()).st_mode & 07777);
    }
    @Test public void defaultPermissionScopeChangesSelectedDirectoryOnly() throws Exception {
        File selected = directory(left, "nonrecursive"), nested = directory(selected, "nested"), payload = file(nested, "payload", 3);
        Os.chmod(selected.getPath(), 0755); Os.chmod(nested.getPath(), 0755); Os.chmod(payload.getPath(), 0644);
        launch(); properties(selected); exact(3); more("文件权限"); input(By.desc("八进制权限"), "700");
        assertFalse(object(By.text("同时应用到所有子文件")).isChecked()); assertFalse(object(By.text("同时应用到所有子文件夹")).isChecked());
        object(By.text("保存权限")).click();
        await(() -> { try { return (Os.stat(selected.getPath()).st_mode & 07777) == 0700; } catch (Exception e) { return false; } });
        assertEquals(0755, Os.stat(nested.getPath()).st_mode & 07777); assertEquals(0644, Os.stat(payload.getPath()).st_mode & 07777);
    }
    @Test public void batchDateEditorChangesBothSelectedFilesOnly() throws Exception {
        File a = file(left, "time-a", 1), b = file(left, "time-b", 2), untouched = file(left, "time-neighbour", 3);
        long before = Os.stat(untouched.getPath()).st_mtime; launch(); choose(List.of(a, b)); more("修改时间");
        String requested = "2026-03-04 05:06:07";
        input(By.desc("修改时间输入"), requested); object(By.text("确定")).click();
        long expected = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse(requested).getTime() / 1000;
        await(() -> { try { return Os.stat(a.getPath()).st_mtime == expected && Os.stat(b.getPath()).st_mtime == expected; } catch (Exception e) { return false; } });
        assertEquals(before, Os.stat(untouched.getPath()).st_mtime);
    }
    @Test public void darkLandscapePropertiesRemainReadableAndCaptureEvidence() throws Exception {
        File selected = file(left, "landscape.bin", 2345); context.getSharedPreferences("appearance", 0).edit().putString("mode", "dark").commit();
        device.setOrientationLeft(); launch(true); properties(selected); exact(2345);
        assertTrue(object(By.text("更多")).isClickable()); assertTrue(object(By.text("校验")).isClickable());
        screenshot("dark-landscape");
    }
}
