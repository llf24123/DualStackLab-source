package org.example.filemanager;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PropertyChecksumsInstrumentedTest {
    @Test public void knownHashesUseOnePassAndAcceptAlgorithmLabels() throws Exception {
        int[] read = {0}, closed = {0};
        InputStream input = new ByteArrayInputStream("123456789".getBytes(StandardCharsets.US_ASCII)) {
            @Override public synchronized int read(byte[] data, int start, int length) { int count = super.read(data, start, length); if (count > 0) read[0] += count; return count; }
            @Override public void close() { closed[0]++; }
        };
        List<Long> progress = new ArrayList<>();
        var values = new PropertyChecksums.Scan().calculate(input, progress::add);
        assertEquals("25f9e794323b453885f5181f1b624d0b", values.md5());
        assertEquals("f7c3bc1d808e04732adf679965ccc34ca7ae3441", values.sha1());
        assertEquals("15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225", values.sha256());
        assertEquals("cbf43926", values.crc32()); assertEquals(9, values.bytes()); assertEquals(9, read[0]); assertEquals(1, closed[0]);
        assertEquals(Long.valueOf(9), progress.get(progress.size() - 1));
        assertEquals("MD5 匹配", PropertyChecksums.compare("MD5: " + values.md5().toUpperCase(), values));
        assertEquals("SHA-1 匹配", PropertyChecksums.compare("SHA-1: " + values.sha1(), values));
        assertEquals("SHA-256 匹配", PropertyChecksums.compare(values.sha256(), values));
        assertEquals("CRC32 匹配", PropertyChecksums.compare("CRC32: cbf43926", values));
        assertEquals("CRC32 不匹配", PropertyChecksums.compare("00000000", values));
        assertTrue(PropertyChecksums.compare("MD5: " + values.sha256(), values).startsWith("输入"));
    }
    @Test public void cancellationClosesBlockedStreamAndReturnsNoDigest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean(); PropertyChecksums.Scan scan = new PropertyChecksums.Scan();
        InputStream input = new InputStream() {
            @Override public int read() throws IOException { return read(new byte[1], 0, 1); }
            @Override public int read(byte[] data, int start, int length) throws IOException {
                entered.countDown();
                try { if (!released.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                throw new IOException("closed");
            }
            @Override public void close() { closed.set(true); released.countDown(); }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = executor.submit(() -> scan.calculate(input, null));
            assertTrue(entered.await(5, TimeUnit.SECONDS)); scan.cancel();
            try { task.get(5, TimeUnit.SECONDS); fail("Cancelled checksum returned a value"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof Tasks.CancelledException); }
            assertTrue(closed.get());
        } finally { released.countDown(); executor.shutdownNow(); }
    }
    @Test public void preCancelledInputIsClosedWithoutReading() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(); PropertyChecksums.Scan scan = new PropertyChecksums.Scan(); scan.cancel();
        InputStream input = new InputStream() {
            @Override public int read() { fail("Read after cancellation"); return -1; }
            @Override public void close() { closed.set(true); }
        };
        try { scan.calculate(input, null); fail("Cancellation ignored"); } catch (Tasks.CancelledException expected) { }
        assertTrue(closed.get());
    }
    @Test public void emptyFileHasRealHashesAndZeroByteProgress() throws Exception {
        var values = new PropertyChecksums.Scan().calculate(new ByteArrayInputStream(new byte[0]), null);
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", values.md5());
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", values.sha1());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", values.sha256());
        assertEquals("00000000", values.crc32()); assertEquals(0, values.bytes());
    }
    @Test public void safFileOpensPayloadOnlyOnceForAllAlgorithms() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri control = Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
        Uri root = Uri.parse(BatchCopyInstrumentedTest.grantFixture(context).getString("uri"));
        try {
            Bundle payload = new Bundle(); payload.putString("name", "payload"); payload.putString("text", "123456789");
            String id = context.getContentResolver().call(control, "seed", null, payload).getString("id");
            FileStore store = new FileStore(context); FileStore.Node file = store.document(DocumentsContract.buildDocumentUriUsingTree(root, id));
            context.getContentResolver().call(control, "clearCounters", null, null);
            assertEquals("cbf43926", new PropertyChecksums.Scan().calculate(store, file).crc32());
            Bundle stats = context.getContentResolver().call(control, "stats", null, null); assertEquals(1, stats.getInt("readOpens"));
        } finally { context.getContentResolver().call(control, "reset", null, null); }
    }
    @Test public void symbolicLinksFifosAndDevicesAreRejectedBeforeOpening() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "checksum-types-").toFile();
        File regular = new File(root, "regular"), link = new File(root, "link"), fifo = new File(root, "pipe");
        Files.write(regular.toPath(), new byte[]{1}); Os.symlink(regular.getPath(), link.getPath()); Os.mkfifo(fifo.getPath(), 0600);
        try {
            for (File file : List.of(link, fifo, new File("/dev/null"))) {
                try { new PropertyChecksums.Scan().calculate(new FileStore(context), FileStore.local(file)); fail("Opened special file " + file); }
                catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("普通文件")); }
            }
        } finally { Files.deleteIfExists(link.toPath()); Files.deleteIfExists(fifo.toPath()); Files.deleteIfExists(regular.toPath()); Files.deleteIfExists(root.toPath()); }
    }
    @Test public void sameLengthModificationWithRestoredMtimeStillInvalidatesChecksum() throws Exception {
        Assume.assumeTrue("Nanosecond stat timestamps require API 27", android.os.Build.VERSION.SDK_INT >= 27);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("checksum-revision-", ".bin", context.getCacheDir()); Files.write(file.toPath(), new byte[10000]);
        long modified = file.lastModified(); AtomicBoolean changed = new AtomicBoolean();
        try {
            try {
                new PropertyChecksums.Scan().calculate(new FileStore(context), FileStore.local(file), bytes -> {
                    if (bytes == 0 && changed.compareAndSet(false, true)) try {
                        byte[] replacement = new byte[10000]; Arrays.fill(replacement, (byte) 42); Files.write(file.toPath(), replacement);
                        assertTrue(file.setLastModified(modified));
                    } catch (IOException error) { throw new UncheckedIOException(error); }
                }); fail("Concurrent same-length rewrite accepted");
            } catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("变化")); }
            assertTrue(changed.get());
        } finally { Files.deleteIfExists(file.toPath()); }
    }
}
