package org.example.filemanager;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReportedZipInstrumentedTest {
    private Context context;
    private File work, archive;
    private ActivityScenario<MainActivity> screen;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String path = InstrumentationRegistry.getArguments().getString("samplePath", "");
        Assume.assumeFalse("Requires a separately supplied archive fixture", path.isEmpty());
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(
                "cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "reported-zip-").toFile();
        archive = new File(work, "y700_drm_20261110.zip"); Files.copy(new File(path).toPath(), archive.toPath());
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        if (work != null) new FileStore(context).delete(FileStore.local(work));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }
    @Test public void suppliedArchiveListsAndEveryMemberMatchesItsCrc() throws Exception {
        FileStore store = new FileStore(context);
        for (boolean root : new boolean[]{false, true}) {
            FileStore.Node container = FileStore.local(archive, root);
            List<FileStore.Node> top = store.list(FileStore.archiveNode(container, "", true, 0, 0));
            assertEquals(6, top.size());
            assertEquals(3, store.list(FileStore.archiveNode(container, "tools/", true, 0, 0)).size());
        }
        try (ZipFile reference = new ZipFile(archive); ArchiveOps.ReadSession reader = new ArchiveOps.ReadSession(archive)) {
            Enumeration<? extends ZipEntry> entries = reference.entries(); int count = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement(); CRC32 crc = new CRC32(); long bytes = 0;
                try (InputStream input = reader.open(entry.getName())) {
                    byte[] buffer = new byte[131072]; int size;
                    while ((size = input.read(buffer)) != -1) { crc.update(buffer, 0, size); bytes += size; }
                }
                assertEquals(entry.getName(), entry.getSize(), bytes); assertEquals(entry.getName(), entry.getCrc(), crc.getValue()); count++;
            }
            assertEquals(8, count);
        }
    }
    @Test public void suppliedZipOpensThroughTheNormalFileList() throws Exception {
        context.getSharedPreferences("MainActivity", 0).edit().clear().putString("left", work.getPath()).putString("right", work.getPath()).commit();
        screen = ActivityScenario.launch(MainActivity.class);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 list = device.wait(Until.findObject(By.desc("左窗口文件列表")), 10000); assertNotNull(list);
        UiObject2 item = device.wait(Until.findObject(By.text(archive.getName())), 10000); assertNotNull(item); item.click();
        assertTrue("Archive root should be displayed", device.wait(Until.hasObject(By.text("MD5SUMS.txt")), 10000));
        UiObject2 tools = device.findObject(By.text("tools")); assertNotNull(tools); tools.click();
        assertTrue(device.wait(Until.hasObject(By.text("drmmat")), 10000));
        device.waitForIdle(); assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "reported-zip-open.png")));
    }
    @Test public void measureMovingOneSmallMemberOutOfTheSuppliedArchive() throws Exception {
        File destination = new File(work, "moved"); assertTrue(destination.mkdir());
        FileStore store = new FileStore(context); FileStore.Node container = FileStore.local(archive);
        FileStore.Node source = FileStore.archiveNode(container, "tools/drmstat", false, 7320, 0);
        byte[] expected;
        try (InputStream input = store.read(source)) { expected = input.readAllBytes(); }
        long start = SystemClock.elapsedRealtime();
        List<FileStore.Node> outputs = store.copyAll(List.of(source), FileStore.local(destination), true);
        long elapsed = SystemClock.elapsedRealtime() - start;
        assertArrayEquals(expected, Files.readAllBytes(outputs.get(0).file.toPath()));
        try (ZipFile zip = new ZipFile(archive)) { assertNull(zip.getEntry("tools/drmstat")); assertNotNull(zip.getEntry("DF_GPU_bp_drm")); }
        android.util.Log.i("ReportedZipTiming", "move7320BytesMs=" + elapsed + " archiveBytes=" + archive.length());
        String run = InstrumentationRegistry.getArguments().getString("benchmarkRun", "candidate");
        Files.write(new File(context.getExternalFilesDir(null), "reported-move-" + run + ".txt").toPath(), Long.toString(elapsed).getBytes());
    }
}
