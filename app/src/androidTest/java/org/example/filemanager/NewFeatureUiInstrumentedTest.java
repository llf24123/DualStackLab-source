package org.example.filemanager;

import android.content.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NewFeatureUiInstrumentedTest {
    Context context; UiDevice device; File work, left, right; byte[] backup; Map<String, ?> oldPrefs;
    ActivityScenario<?> scenario;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        backup = SettingsBackup.export(context); oldPrefs = new HashMap<>(context.getSharedPreferences("MainActivity", 0).getAll());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "feature-ui-").toFile();
        left = new File(work, "left"); right = new File(work, "right"); assertTrue(left.mkdir()); assertTrue(right.mkdir());
        context.getSharedPreferences("MainActivity", 0).edit().putString("left", left.getPath()).putString("right", right.getPath())
                .putString("filter-left", "").putString("filter-right", "").commit();
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        SettingsBackup.apply(context, SettingsBackup.read(new ByteArrayInputStream(backup)), false);
        var edit = context.getSharedPreferences("MainActivity", 0).edit().clear();
        for (var e : oldPrefs.entrySet()) { if (e.getValue() instanceof String s) edit.putString(e.getKey(), s); else if (e.getValue() instanceof Boolean b) edit.putBoolean(e.getKey(), b); else if (e.getValue() instanceof Integer n) edit.putInt(e.getKey(), n); }
        edit.commit(); new FileStore(context).delete(FileStore.local(work));
    }
    UiObject2 object(BySelector selector) { UiObject2 found = device.wait(Until.findObject(selector), 10000); assertNotNull(selector.toString(), found); return found; }
    void write(File parent, String name, String text) throws Exception { Files.write(new File(parent, name).toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    void screenshot(String name) { assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "0637-" + name + ".png"))); }
    void launch() { scenario = ActivityScenario.launch(MainActivity.class); object(By.desc("左窗口文件列表")); }
    void call(String name) { ((ActivityScenario<MainActivity>) scenario).onActivity(a -> { try { var method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(a); } catch (Exception e) { throw new AssertionError(e); } }); }
    @Test public void multipleSelectionShowsBatchRenamePreviewBeforeChangingFiles() throws Exception {
        write(left, "one.txt", "1"); write(left, "two.txt", "2"); launch();
        object(By.desc("左窗口文件列表")).findObject(By.text("one.txt"));
        object(By.desc("更多操作")).click(); object(By.text("全选")).click(); object(By.text("操作")).click();
        object(By.desc("文件操作 批量重命名")).click();
        object(By.text("在名称末尾追加序号")).click(); object(By.text("预览")).click();
        object(By.desc("批量重命名预览")); assertTrue(new File(left, "one.txt").exists()); screenshot("rename-preview");
        object(By.text("执行")).click();
        long until = SystemClock.uptimeMillis() + 10000;
        while (!new File(left, "two_2.txt").exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        assertTrue(new File(left, "one_1.txt").exists()); assertTrue(new File(left, "two_2.txt").exists());
    }
    @Test public void directoryMergePanelShowsMetadataAndPreservesOtherFiles() throws Exception {
        File a = new File(left, "folder"), b = new File(right, "folder"); assertTrue(a.mkdir()); assertTrue(b.mkdir());
        write(a, "same.txt", "new-content"); write(b, "same.txt", "old"); write(b, "untouched.txt", "keep"); launch();
        object(By.desc("左窗口文件列表")).findObject(By.text("folder")).longClick(); object(By.desc("文件操作 复制")).click();
        object(By.desc("同名处理 合并")).click(); object(By.textContains("来源：")); object(By.textContains("11 字节")); screenshot("conflict-details");
        object(By.desc("同名处理 覆盖")).click();
        long until = SystemClock.uptimeMillis() + 10000;
        while (new File(b, "same.txt").length() != 11 && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        assertEquals(11, new File(b, "same.txt").length()); assertTrue(new File(b, "untouched.txt").exists());
    }
    @Test public void comparisonContentActionRunsHashAndShowsExportAction() throws Exception {
        write(left, "same.txt", "left"); write(right, "same.txt", "rght"); launch(); call("comparePanes");
        object(By.text("内容校验…")).click(); object(By.text("计算 SHA-256 校验文件内容")).click(); object(By.text("开始校验")).click();
        object(By.text("SHA-256 不同 · same.txt")); assertTrue(object(By.text("导出结果")).isEnabled()); screenshot("directory-hash");
        File report = new File(work, "report.txt");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                if (activity instanceof DirectoryCompareActivity compare) compare.onActivityResult(1, android.app.Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(report)));
        });
        long until = SystemClock.uptimeMillis() + 5000;
        while (report.length() == 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        assertTrue(new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8).contains("当前 SHA-256=")); device.pressBack();
    }
    @Test public void settingsBackupEntryOffersImportAndExport() throws Exception {
        launch(); call("settings");
        object(By.desc("设置内容"));UiScrollable list=new UiScrollable(new UiSelector().description("设置内容"));
        assertTrue("备份与迁移分类应可滚动到",list.scrollIntoView(new UiSelector().description("设置分类：备份与迁移")));
        object(By.desc("设置分类：备份与迁移")).click();object(By.descStartsWith("书签与设置备份，")).click();
        object(By.text("导出备份")); object(By.text("导入备份")); screenshot("settings-backup"); device.pressBack();
    }
    @Test public void backupActivityExportsAndImportsThroughDocumentResult() throws Exception {
        DirectoryPlaces places = new DirectoryPlaces(context); places.clear(true);
        places.bookmark(new DirectoryPlaces.Entry("/roundtrip", "往返验证", "/roundtrip", "", false));
        scenario = ActivityScenario.launch(BackupActivity.class); File output = new File(work, "backup.json");
        scenario.onActivity(activity -> ((BackupActivity) activity).onActivityResult(1, android.app.Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(output))));
        long until = SystemClock.uptimeMillis() + 5000;
        while (output.length() == 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        var exported = SettingsBackup.read(new FileInputStream(output)); assertEquals("往返验证", exported.bookmarks().get(0).label());
        places.clear(true);
        scenario.onActivity(activity -> ((BackupActivity) activity).onActivityResult(2, android.app.Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(output))));
        object(By.text("合并导入")).click(); until = SystemClock.uptimeMillis() + 5000;
        while (places.entries(true).isEmpty() && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        assertEquals("往返验证", places.entries(true).get(0).label());
    }
    @Test public void apksPreviewDoesNotCreateSessionUntilConfirmedAndBackCleansStaging() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("apksFixture"); Assume.assumeNotNull(path);
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " REQUEST_INSTALL_PACKAGES allow");
        var installer = context.getPackageManager().getPackageInstaller(); int sessions = installer.getMySessions().size();
        Set<String> before = new HashSet<>(Arrays.asList(context.getCacheDir().list((d, n) -> n.startsWith("split-install-"))));
        Intent intent = new Intent(context, SplitInstallActivity.class); FileStore.local(new File(path)).put(intent); scenario = ActivityScenario.launch(intent);
        object(By.text("确认安装所选 APK")); object(By.desc("APKS 设备选择结果")); assertEquals(sessions, installer.getMySessions().size()); screenshot("apks-selection");
        int[] bars = new int[2]; scenario.onActivity(activity -> {
            var insets = activity.getWindow().getDecorView().getRootWindowInsets().getInsets(android.view.WindowInsets.Type.systemBars());
            bars[0] = insets.top; bars[1] = insets.bottom;
        });
        assertTrue(object(By.text("安装 APKS")).getVisibleBounds().top >= bars[0]);
        assertTrue(object(By.text("确认安装所选 APK")).getVisibleBounds().bottom <= device.getDisplayHeight() - bars[1]);
        device.pressBack(); long until = SystemClock.uptimeMillis() + 5000;
        Set<String> after;
        do { after = new HashSet<>(Arrays.asList(context.getCacheDir().list((d, n) -> n.startsWith("split-install-")))); if (after.equals(before)) break; SystemClock.sleep(50); } while (SystemClock.uptimeMillis() < until);
        assertEquals(before, after); assertEquals(sessions, installer.getMySessions().size());
    }
}
