package org.example.filemanager;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TransferConflictInstrumentedTest {
    @Test public void overwriteReplacesOnlyRegularTargetsAndMoveRemovesSourceAfterCommit() throws Exception {
        File root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(),
                "copy-overwrite-").toFile();
        File sourceDir = new File(root, "source"), targetDir = new File(root, "target");
        assertTrue(sourceDir.mkdir()); assertTrue(targetDir.mkdir());
        FileStore store = new FileStore(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try {
            File source = new File(sourceDir, "same.txt"), target = new File(targetDir, "same.txt");
            Files.write(source.toPath(), "new bytes".getBytes(StandardCharsets.UTF_8));
            Files.write(target.toPath(), "old bytes".getBytes(StandardCharsets.UTF_8));
            List<FileStore.Node> replaced = store.copyAll(List.of(FileStore.local(source)), FileStore.directory(targetDir, false), false,
                    FileStore.ConflictPolicy.OVERWRITE, Set.of("same.txt"));
            assertEquals(1, replaced.size()); assertEquals(target.getCanonicalPath(), replaced.get(0).file.getCanonicalPath());
            assertEquals("new bytes", read(target)); assertTrue(source.isFile());

            File moveSource = new File(sourceDir, "move.txt"), moveTarget = new File(targetDir, "move.txt");
            Files.write(moveSource.toPath(), "replacement".getBytes(StandardCharsets.UTF_8));
            Files.write(moveTarget.toPath(), "old".getBytes(StandardCharsets.UTF_8));
            store.copyAll(List.of(FileStore.local(moveSource)), FileStore.directory(targetDir, false), true,
                    FileStore.ConflictPolicy.OVERWRITE, Set.of("move.txt"));
            assertFalse(moveSource.exists()); assertEquals("replacement", read(moveTarget));

            File directorySource = new File(sourceDir, "blocked"); assertTrue(directorySource.mkdir());
            File directoryTarget = new File(targetDir, "blocked"); assertTrue(directoryTarget.mkdir());
            Files.write(new File(directoryTarget, "keep.txt").toPath(), new byte[]{1});
            try {
                store.copyAll(List.of(FileStore.local(directorySource)), FileStore.directory(targetDir, false), false,
                        FileStore.ConflictPolicy.OVERWRITE, Set.of("blocked"));
                fail("Directory conflict must not be replaced");
            } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("普通文件")); }
            assertTrue(new File(directoryTarget, "keep.txt").isFile());
        } finally { store.delete(FileStore.local(root)); }
    }

    @Test public void keepBothPreservesExistingAndSkipLeavesSourceUntouched() throws Exception {
        File root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(),
                "copy-conflict-").toFile();
        File sourceDir = new File(root, "source"), targetDir = new File(root, "target");
        assertTrue(sourceDir.mkdir()); assertTrue(targetDir.mkdir());
        FileStore store = new FileStore(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try {
            File source = new File(sourceDir, "same.txt"), existing = new File(targetDir, "same.txt");
            Files.write(source.toPath(), "new".getBytes(StandardCharsets.UTF_8));
            Files.write(existing.toPath(), "old".getBytes(StandardCharsets.UTF_8));
            List<FileStore.Node> kept = store.copyAll(List.of(FileStore.local(source)), FileStore.directory(targetDir, false), false,
                    FileStore.ConflictPolicy.KEEP_BOTH, Set.of("same.txt"));
            assertEquals(1, kept.size()); assertNotEquals("same.txt", kept.get(0).name);
            assertEquals("old", new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));
            assertEquals("new", new String(Files.readAllBytes(kept.get(0).file.toPath()), StandardCharsets.UTF_8));

            File moveSource = new File(sourceDir, "same.txt");
            List<FileStore.Node> skipped = store.copyAll(List.of(FileStore.local(moveSource)), FileStore.directory(targetDir, false), true,
                    FileStore.ConflictPolicy.SKIP, Set.of("same.txt"));
            assertTrue(skipped.isEmpty()); assertTrue(moveSource.isFile());
            assertEquals("old", new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));
        } finally { store.delete(FileStore.local(root)); }
    }

    @Test public void transferDialogOffersKeepBothAndSkipForExistingNames() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        var preferences = context.getSharedPreferences("MainActivity", 0); Map<String, ?> previous = new HashMap<>(preferences.getAll());
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "copy-conflict-ui-").toFile();
        File left = new File(root, "left"), right = new File(root, "right"); assertTrue(left.mkdir()); assertTrue(right.mkdir());
        File source = new File(left, "same.txt"), existing = new File(right, "same.txt");
        Files.write(source.toPath(), "new".getBytes(StandardCharsets.UTF_8)); Files.write(existing.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        ActivityScenario<MainActivity> scenario = null;
        try {
            device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
            preferences.edit().putString("left", left.getPath()).putString("right", right.getPath()).commit();
            scenario = ActivityScenario.launch(MainActivity.class);
            UiObject2 pane = device.wait(Until.findObject(By.desc("左窗口文件列表")), 10000); assertNotNull(pane);
            UiObject2 row = pane.findObject(By.text("same.txt")); assertNotNull(row); row.longClick();
            object(device, By.desc("文件操作 复制")).click();
            object(device, By.text("处理同名项目")); object(device, By.desc("同名处理 另存")).click();
            File renamed = new File(right, "same (1).txt"); awaitContent(renamed, "new");
            assertEquals("old", read(existing)); assertEquals("new", read(renamed));
            waitNotBusy(scenario);

            scenario.onActivity(TransferConflictInstrumentedTest::reloadLeft);
            leftRow(device, "same.txt").longClick(); object(device, By.desc("文件操作 复制")).click();
            object(device, By.text("处理同名项目")); object(device, By.text("另存一份"));
            object(device, By.desc("同名处理 覆盖")).click();
            awaitContent(existing, "new"); assertEquals("new", read(existing));
            waitNotBusy(scenario);

            File skippedSource = new File(left, "skip.txt"), skippedTarget = new File(right, "skip.txt");
            Files.write(skippedSource.toPath(), "source".getBytes(StandardCharsets.UTF_8));
            Files.write(skippedTarget.toPath(), "target".getBytes(StandardCharsets.UTF_8));
            scenario.onActivity(TransferConflictInstrumentedTest::reloadLeft);
            leftRow(device, "skip.txt").longClick();
            object(device, By.desc("文件操作 复制")).click();
            object(device, By.text("处理同名项目")); object(device, By.text("跳过同名项")).click();
            assertEquals("source", read(skippedSource)); assertEquals("target", read(skippedTarget));
        } finally {
            if (scenario != null) scenario.close();
            var editor = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : previous.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String text) editor.putString(entry.getKey(), text);
                else if (value instanceof Boolean flag) editor.putBoolean(entry.getKey(), flag);
                else if (value instanceof Integer number) editor.putInt(entry.getKey(), number);
                else if (value instanceof Long number) editor.putLong(entry.getKey(), number);
            }
            editor.commit(); new FileStore(context).delete(FileStore.local(root));
        }
    }
    @Test public void partialBatchFailureReportsOnlyUnfinishedSourcesForRetry() throws Exception {
        File root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(),
                "copy-partial-").toFile();
        File sourceDir = new File(root, "source"), targetDir = new File(root, "target");
        assertTrue(sourceDir.mkdir()); assertTrue(targetDir.mkdir());
        FileStore store = new FileStore(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try {
            File first = new File(sourceDir, "first.txt"), missing = new File(sourceDir, "missing.txt");
            Files.write(first.toPath(), "done".getBytes(StandardCharsets.UTF_8));
            try {
                store.copyAll(List.of(FileStore.local(first), FileStore.local(missing)), FileStore.directory(targetDir, false), false,
                        FileStore.ConflictPolicy.KEEP_BOTH, Set.of());
                fail("Missing second source should report a partial transfer");
            } catch (Tasks.PartialTransferException partial) {
                assertEquals(1, partial.completed.size()); assertEquals("first.txt", partial.completed.get(0).name);
                assertEquals(List.of(FileStore.local(missing).key()), partial.remaining.stream().map(FileStore.Node::key).toList());
                assertEquals("done", read(new File(targetDir, "first.txt")));
            }
        } finally { store.delete(FileStore.local(root)); }
    }
    @Test public void overwriteWorksForArchiveDestinationsAndArchiveMovesOut() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); FileStore store = new FileStore(context);
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "archive-overwrite-").toFile();
        try {
            File source = new File(root, "same.txt"), archive = new File(root, "target.zip"), target = new File(root, "output");
            Files.write(source.toPath(), "new".getBytes(StandardCharsets.UTF_8)); assertTrue(target.mkdir());
            try (var zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(archive))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("same.txt")); zip.write("old".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            FileStore.Node destination = FileStore.archiveNode(FileStore.local(archive), "", true, 0, 0);
            store.copyAll(List.of(FileStore.local(source)), destination, false, FileStore.ConflictPolicy.OVERWRITE, Set.of("same.txt"));
            assertEquals("new", new String(ArchiveOps.bytes(archive, "same.txt", 100), StandardCharsets.UTF_8));
            Files.write(source.toPath(), "move into archive".getBytes(StandardCharsets.UTF_8));
            store.copyAll(List.of(FileStore.local(source)), destination, true, FileStore.ConflictPolicy.OVERWRITE, Set.of("same.txt"));
            assertFalse(source.exists());
            File output = new File(target, "same.txt"); Files.write(output.toPath(), "old target".getBytes(StandardCharsets.UTF_8));
            store.copyAll(List.of(FileStore.archiveNode(FileStore.local(archive), "same.txt", false, 17, 0)),
                    FileStore.directory(target, false), true, FileStore.ConflictPolicy.OVERWRITE, Set.of("same.txt"));
            assertEquals("move into archive", read(output)); assertTrue(ArchiveOps.entryNames(archive).isEmpty());
        } finally { store.delete(FileStore.local(root)); }
    }

    private static UiObject2 object(UiDevice device, androidx.test.uiautomator.BySelector selector) {
        UiObject2 found = device.wait(Until.findObject(selector), 8000); assertNotNull(selector.toString(), found); return found;
    }
    private static UiObject2 leftRow(UiDevice device, String name) {
        UiObject2 pane = object(device, By.desc("左窗口文件列表").hasDescendant(By.text(name)));
        UiObject2 row = pane.findObject(By.text(name)); assertNotNull(name, row); return row;
    }
    private static void reloadLeft(MainActivity activity) {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("left"); field.setAccessible(true);
            Object pane = field.get(activity); java.lang.reflect.Method reload = pane.getClass().getDeclaredMethod("reload");
            reload.setAccessible(true); reload.invoke(pane);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void waitNotBusy(ActivityScenario<MainActivity> scenario) {
        boolean[] busy = {true}; long until = SystemClock.uptimeMillis() + 10000;
        while (busy[0] && SystemClock.uptimeMillis() < until) {
            scenario.onActivity(activity -> {
                try { var field = MainActivity.class.getDeclaredField("busy"); field.setAccessible(true); busy[0] = field.getBoolean(activity); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            if (busy[0]) SystemClock.sleep(25);
        }
        assertFalse("Transfer should finish before the next UI action", busy[0]);
    }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void await(File file) {
        long until = SystemClock.uptimeMillis() + 8000;
        while (!file.isFile() && SystemClock.uptimeMillis() < until) SystemClock.sleep(40);
        assertTrue(file.toString(), file.isFile());
    }
    private static void awaitContent(File file, String expected) throws Exception {
        long until = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < until && (!file.isFile() || !expected.equals(read(file)))) SystemClock.sleep(40);
        assertTrue(file.toString(), file.isFile()); assertEquals(expected, read(file));
    }
}
