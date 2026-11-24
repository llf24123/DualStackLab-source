package org.example.filemanager;

import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class Iteration38InstrumentedTest {
    Context context; File work; FileStore store;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); store = new FileStore(context);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "iteration38-").toFile();
    }
    @After public void cleanup() throws Exception { ArchiveOps.testRewriteBoundary = null; store.delete(FileStore.local(work)); }
    File dir(String name) throws Exception { File d = new File(work, name); assertTrue(d.mkdir()); return d; }
    File write(File parent, String name, String text) throws Exception { File f = new File(parent, name); Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); return f; }
    @Test public void naturalSortHandlesLargeNumbersAndLeadingZeros() {
        List<String> names = new ArrayList<>(List.of("file10", "file02", "file1", "file2", "file0002", "file99999999999999999999", "file100000000000000000000"));
        names.sort(NaturalOrder::compare);
        assertEquals(List.of("file1", "file2", "file02", "file0002", "file10", "file99999999999999999999", "file100000000000000000000"), names);
        for (String a : names) for (String b : names) assertEquals(Integer.signum(NaturalOrder.compare(a, b)), -Integer.signum(NaturalOrder.compare(b, a)));
    }
    private File archive(String name) throws Exception {
        File file = new File(work, name); byte[] bytes = new byte[2 * 1024 * 1024]; new Random(38).nextBytes(bytes);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            out.setLevel(1); out.setComment("keep comment"); out.putNextEntry(new ZipEntry("untouched.bin")); out.write(bytes); out.closeEntry();
            out.putNextEntry(new ZipEntry("replace.txt")); out.write("old".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        } return file;
    }
    private byte[] raw(File zip) throws Exception {
        try (var reader = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zip).get(); InputStream in = reader.getRawInputStream(reader.getEntry("untouched.bin"))) {
            return FileStore.readBytes(in, 4 * 1024 * 1024);
        }
    }
    @Test public void zipAddAndReplacePreserveOriginalCompressedPayload() throws Exception {
        File zip = archive("raw.zip"), replacement = write(work, "new.txt", "new content"); byte[] original = raw(zip);
        ArchiveOps.rewriteBatch(zip, Map.of("replace.txt", replacement, "added.txt", replacement), Set.of());
        assertArrayEquals(original, raw(zip)); assertEquals("new content", new String(ArchiveOps.bytes(zip, "replace.txt", 100), StandardCharsets.UTF_8));
        try (ZipFile reader = new ZipFile(zip)) { assertEquals("keep comment", reader.getComment()); assertEquals(3, reader.size()); }
    }
    @Test public void editingOneArchiveDoesNotBlockListingAnotherArchive() throws Exception {
        File a = archive("a.zip"), b = archive("b.zip");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); ExecutorService workers = Executors.newFixedThreadPool(2);
        ArchiveOps.testRewriteBoundary = file -> { if (file.equals(a)) { entered.countDown(); try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("gate timeout"); } catch (InterruptedException e) { throw new IOException(e); } } };
        try {
            Future<?> rewrite = workers.submit(() -> { try { ArchiveOps.removeAll(a, Set.of("replace.txt")); } catch (IOException e) { throw new RuntimeException(e); } });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, workers.submit(() -> ArchiveOps.list(b, "")).get(2, TimeUnit.SECONDS).size());
            release.countDown(); rewrite.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test public void journalRecoversRenameInterruptedBeforeCompletionRecord() throws Exception {
        File parent = dir("rename"); File a = write(parent, "a.txt", "alpha"), b = write(parent, "b.txt", "beta");
        BatchRename.Plan plan = BatchRename.preview(store, List.of(FileStore.local(a), FileStore.local(b)), new BatchRename.Rule("", "", true, 1, ""));
        Map<BatchRename.Item, String> temps = new LinkedHashMap<>();
        for (var item : plan.items()) temps.put(item, ".dualstack-rename-" + UUID.randomUUID());
        RenameJournal.begin(store, plan, plan.items(), temps);
        // Simulates process loss between filesystem rename and the subsequent journal update.
        store.rename(plan.items().get(0).source(), temps.get(plan.items().get(0)));
        List<RenameJournal> pending = RenameJournal.pending(context); assertEquals(1, pending.size()); pending.get(0).restore(new FileStore(context));
        assertEquals("alpha", new String(Files.readAllBytes(a.toPath()), StandardCharsets.UTF_8)); assertEquals("beta", new String(Files.readAllBytes(b.toPath()), StandardCharsets.UTF_8)); assertEquals(2, parent.list().length);
        assertTrue(RenameJournal.pending(context).isEmpty());
    }
    @Test public void journalRefusesChangedFileAndKeepsRecoveryRecord() throws Exception {
        File parent = dir("changed"); File source = write(parent, "a", "original");
        var plan = BatchRename.preview(store, List.of(FileStore.local(source)), new BatchRename.Rule("a", "b", false, 1, ""));
        var item = plan.items().get(0); String name = ".dualstack-rename-" + UUID.randomUUID();
        RenameJournal journal = RenameJournal.begin(store, plan, plan.items(), Map.of(item, name));
        FileStore.Node moved = store.rename(item.source(), name); Files.write(moved.file.toPath(), "modified-larger".getBytes(StandardCharsets.UTF_8));
        try { journal.restore(store); fail("Changed files must not be silently renamed"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("已被修改")); }
        assertTrue(moved.file.exists()); assertEquals(1, RenameJournal.pending(context).size()); journal.complete();
    }
    @Test public void diskReportPagesAndExportsAllDifferencesAfterReload() throws Exception {
        File a = dir("left"), b = dir("right"); for (int i = 0; i < 451; i++) write(a, "文件" + i + ".txt", "x");
        var stored = CompareReport.create(context, store, FileStore.local(a), FileStore.local(b), new DirectoryCompare.Options(true, true), new DirectoryCompare.Scan());
        var restored = CompareReport.open(context, stored.id); assertEquals(451, restored.count); assertEquals(200, restored.page(0).size()); assertEquals(51, restored.page(2).size());
        ByteArrayOutputStream out = new ByteArrayOutputStream(); restored.export(out); String report = out.toString("UTF-8");
        assertTrue(report.contains("文件450.txt")); assertTrue(report.contains("差异或错误：451"));
        for (String suffix : List.of(".rows", ".idx", ".json")) assertTrue(new File(restored.directory, restored.id + suffix).delete());
    }
    @Test public void analysisRanksDirectoriesAndHashesOnlyEqualSizedCandidates() throws Exception {
        File a = dir("a"), b = dir("b"); write(a, "small", "hello"); write(b, "copy", "hello"); write(b, "other", "world"); write(b, "unique", "a longer file");
        Files.createSymbolicLink(new File(a, "loop").toPath(), work.toPath());
        StorageAnalysis.Scan scan = new StorageAnalysis.Scan(); var result = scan.run(store, FileStore.local(work), true);
        assertEquals("b", result.entries().get(0).node().name); assertEquals(1, result.duplicates().size()); assertEquals(2, result.duplicates().get(0).files().size());
        assertEquals(3, scan.hashed); assertEquals(4, result.files()); assertEquals(1, result.ignored()); assertTrue(result.errors().isEmpty());
    }
    @Test public void analysisUsesInodeIdentityAndHonorsCancel() throws Exception {
        File a = write(work, "one", "abc"), copy = write(work, "copy", "abc");
        FileStore.Node original = FileStore.local(a), alias = FileStore.local(new File(work, "./one"));
        assertEquals(StorageAnalysis.objectKey(store, original), StorageAnalysis.objectKey(store, alias));
        assertNotEquals(StorageAnalysis.objectKey(store, original), StorageAnalysis.objectKey(store, FileStore.local(copy)));
        assertEquals(1, new StorageAnalysis.Scan().run(store, FileStore.local(work), true).duplicates().size());
        var cancelled = new StorageAnalysis.Scan(); cancelled.cancel();
        try { cancelled.run(store, FileStore.local(work), false); fail(); } catch (Tasks.CancelledException expected) { }
    }
    @Test public void firstImageDisplaysWhileSiblingListingIsBlocked() throws Exception {
        for (String name : List.of("1.png", "2.png", "10.png")) {
            Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.RED);
            try (OutputStream out = new FileOutputStream(new File(work, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        }
        CountDownLatch blocked = new CountDownLatch(2), release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) Tasks.BROWSE.execute(() -> { blocked.countDown(); try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } });
        assertTrue(blocked.await(5, TimeUnit.SECONDS)); Intent intent = new Intent(context, PreviewActivity.class).putExtra("kind", "image"); FileStore.local(new File(work, "10.png")).put(intent);
        try (ActivityScenario<PreviewActivity> screen = ActivityScenario.launch(intent)) {
            boolean[] loaded = {false}; long until = SystemClock.uptimeMillis() + 7000;
            while (!loaded[0] && SystemClock.uptimeMillis() < until) { screen.onActivity(a -> loaded[0] = field(a, "bitmap") != null); if (!loaded[0]) SystemClock.sleep(20); }
            assertTrue("Selected picture must not wait for BROWSE", loaded[0]); release.countDown(); until = SystemClock.uptimeMillis() + 7000;
            boolean[] ready = {false};
            while (!ready[0] && SystemClock.uptimeMillis() < until) { screen.onActivity(a -> ready[0] = ((List<?>)field(a, "pictures")).size() == 3); if (!ready[0]) SystemClock.sleep(20); }
            assertTrue(ready[0]); screen.onActivity(a -> assertEquals(2, field(a, "pictureIndex")));
        } finally { release.countDown(); }
    }
    static Object field(Object object, String name) { try { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); } catch (Exception e) { throw new AssertionError(e); } }
}
