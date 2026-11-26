package org.example.filemanager;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OperationProgressInstrumentedTest {
    private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private File work;
    private ActivityScenario<MainActivity> screen;
    private CountDownLatch workerDone;
    private Long originalIdleTimeout;
    @Before public void fixture() throws Exception { work = Files.createTempDirectory(app.getCacheDir().toPath(), "operation-progress-").toFile(); }
    @After public void cleanup() throws Exception {
        try {
            Tasks.Mutation active = Tasks.activeMutation();
            if (workerDone != null && workerDone.getCount() != 0) {
                if (active != null) active.progress.cancelled.set(true);
                assertTrue(workerDone.await(15, TimeUnit.SECONDS));
            }
            if (screen != null) screen.close();
            if (active != null && active.finished) Tasks.clearMutation(active);
            new FileStore(app).delete(FileStore.local(work));
        } finally {
            if (originalIdleTimeout != null) Configurator.getInstance().setWaitForIdleTimeout(originalIdleTimeout);
        }
    }
    @Test public void knownBytesGiveStableEtaAndStallInvalidatesIt() throws Exception {
        Tasks.Progress progress = new Tasks.Progress();
        progress.run(() -> {
            Tasks.Progress.budget("复制文件", 10000);
            long clock = SystemClock.elapsedRealtime();
            Tasks.Progress.bytes(2500);
            var first = progress.snapshot(clock + 1000);
            assertEquals(25, first.percent()); assertTrue(first.bytesPerSecond() > 0); assertTrue(first.remainingSeconds() > 0);
            var stalled = progress.snapshot(clock + 3500);
            assertEquals(0d, stalled.bytesPerSecond(), 0d); assertEquals(-1, stalled.remainingSeconds());
            Tasks.Progress.bytes(7500);
            assertEquals("An unfinished worker cannot claim completion", 99, progress.snapshot(clock + 4000).percent());
            Tasks.Progress.phase("重建归档");
            var rewriting = progress.snapshot(clock + 4250);
            assertEquals(-1, rewriting.percent()); assertEquals(-1, rewriting.remainingSeconds());
            assertEquals(0d, rewriting.bytesPerSecond(), 0d);
            return null;
        });
        assertTrue(progress.snapshot().finished());
    }
    @Test public void directoriesAreDiscoveredLazilyAndRootUpgradeKeepsTheirBudget() throws Exception {
        Tasks.Progress progress = new Tasks.Progress();
        FileStore.Node directory = new FileStore.Node("folder", new File(work, "folder"), null, true, false, 0, 0);
        FileStore.Node rootDirectory = new FileStore.Node("folder", directory.file, null, true, true, 0, 0);
        FileStore.Node child = new FileStore.Node("child.bin", new File(directory.file, "child.bin"), null, false, true, 1000, 0);
        progress.run(() -> {
            Tasks.Progress.plan(List.of(directory), "复制文件"); assertEquals(-1, progress.snapshot().total());
            Tasks.Progress.discovered(rootDirectory, List.of(child));
            Tasks.Progress.bytes(300); var snapshot = progress.snapshot();
            assertEquals(1000, snapshot.total()); assertEquals(30, snapshot.percent()); return null;
        });
    }
    @Test public void unknownOrChangingSizesNeverFabricatePercent() throws Exception {
        Tasks.Progress progress = new Tasks.Progress();
        progress.run(() -> {
            Tasks.Progress.budget("解压文件", -1); Tasks.Progress.bytes(42); assertEquals(-1, progress.snapshot().percent());
            Tasks.Progress.budget("复制文件", 10); Tasks.Progress.bytes(20); assertEquals(-1, progress.snapshot().percent());
            progress.cancelled.set(true); assertEquals(-1, progress.snapshot().remainingSeconds());
            assertTrue(OperationProgressView.format(progress.snapshot()).contains("正在停止")); return null;
        });
    }
    @Test public void nestedPreparationBytesNeverPolluteMemberCopyBudget() throws Exception {
        Tasks.Progress progress = new Tasks.Progress();
        progress.run(() -> {
            Tasks.Progress.budget("复制文件", 32768); Tasks.Progress.bytes(1024);
            try (Tasks.Progress.Phase preparation = Tasks.Progress.stage("准备归档")) {
                Tasks.Progress.bytes(40L * 1024 * 1024);
                try (Tasks.Progress.Phase nested = Tasks.Progress.stage("校验来源")) {
                    Tasks.Progress.bytes(128L * 1024 * 1024);
                    assertEquals(-1, progress.snapshot().total());
                }
                assertEquals(40L * 1024 * 1024, progress.snapshot().bytes());
            }
            var restored = progress.snapshot();
            assertEquals("复制文件", restored.phase()); assertEquals(1024, restored.bytes());
            assertEquals(32768, restored.total()); assertEquals(3, restored.percent());
            var idleAfterPreparation = progress.snapshot(SystemClock.elapsedRealtime() + 1000);
            assertEquals("Returning from preparation must not recount previous payload bytes as new speed",
                    0d, idleAfterPreparation.bytesPerSecond(), 0d);
            assertEquals(-1, idleAfterPreparation.remainingSeconds());
            Tasks.Progress.bytes(31744);
            assertEquals(32768, progress.snapshot().bytes());
            assertEquals(99, progress.snapshot().percent()); return null;
        });
    }
    @Test public void realZipExtractionCountsUncompressedBytesAndHeaderTotal() throws Exception {
        File zipFile = zipFixture(3 * 1024 * 1024);
        File target = new File(work, "extracted"); Tasks.Progress progress = new Tasks.Progress();
        progress.run(() -> {
            ArchiveOps.extract(zipFile, "", target);
            var snapshot = progress.snapshot(); assertEquals(3 * 1024 * 1024, snapshot.bytes());
            assertEquals(3 * 1024 * 1024, snapshot.total()); assertEquals(99, snapshot.percent()); return null;
        });
        assertEquals(3 * 1024 * 1024, new File(target, "payload.bin").length());
    }
    @Test public void realDirectoryCopyDiscoversTotalWithoutAnotherWalk() throws Exception {
        File source = new File(work, "source"), target = new File(work, "target"); assertTrue(source.mkdir()); assertTrue(target.mkdir());
        Files.write(new File(source, "one.bin").toPath(), new byte[32768]);
        Files.write(new File(source, "two.bin").toPath(), new byte[65536]);
        Tasks.Progress progress = new Tasks.Progress();
        progress.run(() -> {
            new FileStore(app).copyAll(List.of(FileStore.local(source)), FileStore.local(target), false);
            var snapshot = progress.snapshot(); assertEquals(98304, snapshot.bytes()); assertEquals(98304, snapshot.total()); return null;
        });
    }
    @Test public void failedExtractionKeepsItsUserVisibleDirectory() throws Exception {
        File invalid = new File(work, "invalid.zip"), parent = new File(work, "output"); assertTrue(parent.mkdir());
        Files.write(invalid.toPath(), new byte[]{1, 2, 3, 4});
        try { new FileStore(app).extract(List.of(FileStore.local(invalid)), FileStore.local(parent), "kept"); fail("Invalid archive was accepted"); }
        catch (IOException expected) { assertTrue("Visible output must not be recursively rolled back", new File(parent, "kept").isDirectory()); }
    }
    @Test public void slowCopyShowsInlineEtaSurvivesRecreationAndHides() throws Exception { exerciseInlineCopy(false, true); }
    @Test public void slowZipPayloadShowsInlineEtaWithoutModalWindow() throws Exception { exerciseInlineCopy(true, false); }
    @Test public void subsecondWorkNeverFlashesProgress() throws Exception {
        screen = ActivityScenario.launch(MainActivity.class); Tasks.Progress progress = new Tasks.Progress();
        screen.onActivity(activity -> {
            OperationProgressView view = (OperationProgressView) field(activity, "operationProgress");
            view.bind(progress); progress.finished = true;
        });
        SystemClock.sleep(850);
        screen.onActivity(activity -> assertEquals(View.GONE, ((View)field(activity, "operationProgress")).getVisibility()));
    }
    private void exerciseInlineCopy(boolean zip, boolean recreate) throws Exception {
        // Progress text changes every 250 ms. Waiting for global UI idleness can consume
        // the entire transfer before a selector is evaluated, so observe this live UI directly.
        Configurator configurator = Configurator.getInstance();
        originalIdleTimeout = configurator.getWaitForIdleTimeout();
        configurator.setWaitForIdleTimeout(0);
        int length = 8 * 1024 * 1024; File source;
        if (zip) source = zipFixture(length);
        else { source = new File(work, "large.bin"); try (RandomAccessFile data = new RandomAccessFile(source, "rw")) { data.setLength(length); } }
        File target = new File(work, "copied.bin"); workerDone = new CountDownLatch(1);
        screen = ActivityScenario.launch(MainActivity.class);
        Tasks.Job<FileStore.Node> job = () -> {
            try {
                Tasks.Progress.budget(zip ? "解压文件" : "复制文件", length);
                try (ZipFile archive = zip ? new ZipFile(source) : null;
                     InputStream input = zip ? archive.getInputStream(archive.getEntry("payload.bin")) : new FileInputStream(source);
                     InputStream slow = new FilterInputStream(input) {
                         @Override public int read(byte[] buffer, int offset, int count) throws IOException {
                             try { Thread.sleep(85); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                             return in.read(buffer, offset, count);
                         }
                     }; OutputStream output = new FileOutputStream(target)) { FileStore.pump(slow, output); }
                return FileStore.local(target);
            } finally { workerDone.countDown(); }
        };
        screen.onActivity(activity -> {
            try {
                Method mutate = MainActivity.class.getDeclaredMethod("mutate", Tasks.Job.class, Tasks.Done.class);
                mutate.setAccessible(true); mutate.invoke(activity, job, (Tasks.Done<FileStore.Node>) ignored -> {});
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        boolean stopVisible = device.wait(Until.hasObject(By.desc("停止文件操作")), 5000);
        if (!stopVisible) captureProgressState(device, zip, "missing-stop");
        assertTrue("The live inline progress strip must expose its stop action", stopVisible);
        long until = SystemClock.uptimeMillis() + 3000;
        while (!device.hasObject(By.textContains("预计剩余")) && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        boolean etaVisible = device.hasObject(By.textContains("预计剩余"));
        if (!etaVisible) captureProgressState(device, zip, "missing-eta");
        assertTrue("A measured payload should show a remaining-time estimate", etaVisible);
        assertFalse(device.hasObject(By.text("正在处理")));
        assertTrue(device.takeScreenshot(new File(app.getExternalFilesDir(null), zip ? "operation-extract-progress.png" : "operation-copy-progress.png")));
        if (recreate) {
            screen.recreate();
            assertNotNull(device.wait(Until.findObject(By.desc("停止文件操作")), 3000));
        }
        assertTrue(workerDone.await(15, TimeUnit.SECONDS));
        long complete = SystemClock.uptimeMillis() + 5000; boolean[] hidden = {false};
        while (!hidden[0] && SystemClock.uptimeMillis() < complete) {
            screen.onActivity(activity -> hidden[0] = ((View)field(activity, "operationProgress")).getVisibility() == View.GONE);
            if (!hidden[0]) SystemClock.sleep(25);
        }
        assertTrue(hidden[0]); assertEquals(length, target.length());
    }
    private void captureProgressState(UiDevice device, boolean zip, String reason) {
        File directory = app.getExternalFilesDir(null);
        String name = "operation-" + (zip ? "extract-" : "copy-") + reason;
        device.takeScreenshot(new File(directory, name + ".png"));
        try { device.dumpWindowHierarchy(new File(directory, name + ".xml")); }
        catch (IOException ignored) { /* Keep the original visibility assertion as the failure. */ }
    }
    private File zipFixture(int length) throws Exception {
        File file = new File(work, "fixture.zip"); byte[] data = new byte[32768];
        try (ZipOutputStream archive = new ZipOutputStream(new FileOutputStream(file))) {
            archive.putNextEntry(new ZipEntry("payload.bin"));
            for (int left = length; left > 0; left -= Math.min(left, data.length)) archive.write(data, 0, Math.min(left, data.length));
            archive.closeEntry();
        }
        return file;
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
