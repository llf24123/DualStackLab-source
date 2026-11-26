package org.example.filemanager;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ZipCompatibilityInstrumentedTest {
    private Context context;
    private File work;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "zip-compat-").toFile();
    }
    @After public void cleanup() throws Exception { ArchiveOps.testRewriteBoundary = null; new FileStore(context).delete(FileStore.local(work)); }
    @Test public void mixedSeparatorsWorkForListingReadingExtractingEditingAndRemoving() throws Exception {
        File zip = archive("windows.zip", new String[]{"tools\\nested/file.txt", "empty\\", "keep.txt"});
        assertEquals(3, ArchiveOps.list(zip, "").size());
        assertEquals("file.txt", ArchiveOps.list(zip, "tools/nested/").get(0).name());
        try (InputStream input = ArchiveOps.openEntry(zip, "tools/nested/file.txt")) { assertEquals("tools\\nested/file.txt", new String(input.readAllBytes())); }
        assertEquals(-1, ArchiveOps.entrySize(zip, "missing"));
        File extracted = new File(work, "extracted"); ArchiveOps.extract(zip, "", extracted);
        assertEquals("tools\\nested/file.txt", new String(Files.readAllBytes(new File(extracted, "tools/nested/file.txt").toPath())));
        assertTrue(new File(extracted, "empty").isDirectory());
        File single = new File(work, "single"); ArchiveOps.extractOne(zip, "tools/nested/file.txt", single);
        assertEquals("tools\\nested/file.txt", new String(Files.readAllBytes(single.toPath())));
        File replacement = new File(work, "replacement"); Files.write(replacement.toPath(), "changed".getBytes());
        ArchiveOps.rewrite(zip, null, null, Map.of("tools/nested/file.txt", replacement));
        try (InputStream input = ArchiveOps.openEntry(zip, "tools/nested/file.txt")) { assertEquals("changed", new String(input.readAllBytes())); }
        ArchiveOps.removeAll(zip, Set.of("tools/"));
        assertEquals(Set.of("empty/", "keep.txt"), new HashSet<>(ArchiveOps.entryNames(zip)));
    }
    @Test public void normalizedCollisionsAndUnsafeWindowsPathsAreRejected() throws Exception {
        File collision = archive("collision.zip", new String[]{"a\\file.txt", "a/file.txt"});
        failure(() -> ArchiveOps.list(collision, ""));
        failure(() -> { try (ArchiveOps.ReadSession reader = new ArchiveOps.ReadSession(collision)) { reader.open("a/file.txt"); } });
        for (String name : new String[]{"..\\outside", "C:\\outside", "\\\\server\\share", "a\\..\\outside", "a//file"}) {
            File zip = archive("unsafe-" + UUID.randomUUID() + ".zip", new String[]{name});
            failure(() -> ArchiveOps.list(zip, ""));
            File output = new File(work, "unsafe-output-" + UUID.randomUUID());
            failure(() -> ArchiveOps.extract(zip, "", output));
        }
        assertFalse(new File(work, "outside").exists());
    }
    @Test public void corruptStoredPayloadIsRejectedAndNeverPublished() throws Exception {
        File zip = new File(work, "corrupt.zip");
        byte[] payload = {11, 22, 33, 44};
        CRC32 crc = new CRC32(); crc.update(payload);
        ZipEntry entry = new ZipEntry("data.bin");
        entry.setMethod(ZipEntry.STORED);
        entry.setTime(1700000000000L);
        entry.setSize(payload.length);
        entry.setCrc(crc.getValue());
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(entry); out.write(payload); out.closeEntry();
        }
        try (RandomAccessFile file = new RandomAccessFile(zip, "rw")) {
            file.seek(30 + "data.bin".length()); // The fixture's local header has no extra fields.
            assertEquals(11, file.read());
            file.seek(file.getFilePointer() - 1);
            file.write(99);
        }
        assertEquals("data.bin", ArchiveOps.list(zip, "").get(0).name());
        failure(() -> ArchiveOps.bytes(zip, "data.bin", 16));
        File output = new File(work, "output");
        failure(() -> ArchiveOps.extract(zip, "", output));
        assertArrayEquals(new String[0], output.list());
        FileStore store = new FileStore(context);
        FileStore.Node member = FileStore.archiveNode(FileStore.local(zip), "data.bin", false, payload.length, 0);
        failure(() -> store.copy(member, FileStore.local(output), false));
        assertArrayEquals(new String[0], output.list());
        File existing = new File(output, "existing.bin");
        Files.write(existing.toPath(), payload);
        failure(() -> ArchiveOps.extractOne(zip, "data.bin", existing));
        assertArrayEquals(payload, Files.readAllBytes(existing.toPath()));
        assertArrayEquals(new String[]{"existing.bin"}, output.list());
    }
    @Test public void removalPreservesCompressedBytesMetadataAndComment() throws Exception {
        File zip = new File(work, "raw.zip"); byte[] payload = new byte[512 * 1024]; new Random(23).nextBytes(payload);
        try (org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream out = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(zip)) {
            out.setLevel(1); out.setComment("archive-comment");
            org.apache.commons.compress.archivers.zip.ZipArchiveEntry kept = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("keep.bin");
            kept.setUnixMode(0755); kept.setComment("member-comment"); out.putArchiveEntry(kept); out.write(payload); out.closeArchiveEntry();
            org.apache.commons.compress.archivers.zip.ZipArchiveEntry zero = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("zero");
            zero.setMethod(ZipEntry.STORED); zero.setSize(0); zero.setCrc(0); out.putArchiveEntry(zero); out.closeArchiveEntry();
            out.putArchiveEntry(new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("remove.txt")); out.write(1); out.closeArchiveEntry();
        }
        byte[] compressed = raw(zip, "keep.bin"); ArchiveOps.removeAll(zip, Set.of("remove.txt"));
        assertArrayEquals(compressed, raw(zip, "keep.bin"));
        try (org.apache.commons.compress.archivers.zip.ZipFile input = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zip).get(); ZipFile metadata = new ZipFile(zip)) {
            assertEquals(0755, input.getEntry("keep.bin").getUnixMode() & 07777);
            assertEquals("member-comment", input.getEntry("keep.bin").getComment()); assertEquals("archive-comment", metadata.getComment());
            assertEquals(ZipEntry.STORED, metadata.getEntry("zero").getMethod()); assertNull(metadata.getEntry("remove.txt"));
            try (InputStream contents = input.getInputStream(input.getEntry("keep.bin"))) { assertArrayEquals(payload, contents.readAllBytes()); }
        }
    }
    @Test public void rawRemovalStripsApkSignaturesAndCanCreateAnEmptyArchive() throws Exception {
        File apk = archive("signed.apk", new String[]{"META-INF/CERT.SF", "META-INF/CERT.RSA", "META-INF/MANIFEST.MF", "remove", "keep"});
        ArchiveOps.removeAll(apk, Set.of("remove")); assertEquals(List.of("keep"), ArchiveOps.entryNames(apk));
        ArchiveOps.removeAll(apk, Set.of("keep")); assertTrue(ArchiveOps.entryNames(apk).isEmpty());
    }
    @Test public void cancellationDuringRawCopyPreservesOriginalAndCleansTemporaryOutput() throws Exception {
        File zip = new File(work, "cancel.zip"); byte[] chunk = new byte[1024 * 1024]; new Random(3).nextBytes(chunk);
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            out.setLevel(0); out.putNextEntry(new ZipEntry("keep"));
            for (int i = 0; i < 16; i++) out.write(chunk); out.closeEntry();
            out.putNextEntry(new ZipEntry("remove")); out.closeEntry();
        }
        String before = FileStore.FileState.read(zip).fingerprint(); Tasks.Progress progress = new Tasks.Progress();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { progress.run(() -> { ArchiveOps.removeAll(zip, Set.of("remove")); return true; }); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.start(); long until = android.os.SystemClock.uptimeMillis() + 10000;
        while (worker.isAlive() && progress.transferred.get() == 0 && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(1);
        progress.cancelled.set(true); worker.join(10000);
        assertFalse(worker.isAlive()); assertTrue(String.valueOf(failure.get()), failure.get() instanceof Tasks.CancelledException);
        assertEquals(before, FileStore.FileState.read(zip).fingerprint()); assertArrayEquals(new String[]{"cancel.zip"}, work.list());
    }
    private File archive(String name, String[] entries) throws IOException {
        File file = new File(work, name);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (String entry : entries) { out.putNextEntry(new ZipEntry(entry)); if (!entry.endsWith("\\")) out.write(entry.getBytes()); out.closeEntry(); }
        }
        return file;
    }
    private byte[] raw(File file, String entry) throws Exception {
        try (org.apache.commons.compress.archivers.zip.ZipFile zip = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(file).get();
                InputStream input = zip.getRawInputStream(zip.getEntry(entry))) { return input.readAllBytes(); }
    }
    private interface Failing { void run() throws Exception; }
    private void failure(Failing operation) throws Exception { try { operation.run(); fail("Expected invalid path failure"); } catch (IOException expected) { } }
}
