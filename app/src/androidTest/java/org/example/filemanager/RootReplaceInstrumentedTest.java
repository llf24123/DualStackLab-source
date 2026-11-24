package org.example.filemanager;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RootReplaceInstrumentedTest {
    private Context context;
    private File work, target;
    private RootMutations.Session session;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "root-replace-").toFile();
        target = new File(work, "target.bin"); Files.write(target.toPath(), new byte[]{1, 2, 3, 4});
        session = new RootMutations.Session(context, false);
    }
    @After public void cleanup() throws Exception {
        RootAccess.reset(); RootDirectoryReader.close(); RootMutations.close(); session.close();
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void replacementPreservesMetadataAndSessionAcrossMultipleRequests() throws Exception {
        FileStore.FileState before = FileStore.FileState.read(target);
        for (int i = 0; i < 3; i++) {
            byte[] payload = new byte[32768 + i]; new Random(i).nextBytes(payload);
            RootFileOps.Result result = session.execute(request(payload.length), null, new ByteArrayInputStream(payload));
            assertArrayEquals(payload, Files.readAllBytes(target.toPath())); assertEquals(target.getPath(), result.path);
            assertEquals(before.mode(), result.mode & 07777); assertEquals(before.uid(), result.uid); assertEquals(before.gid(), result.gid);
            assertEquals(FileStore.local(target).identity, result.identity); assertTrue(session.isAlive());
        }
        assertArrayEquals(new String[]{"target.bin"}, work.list());
    }
    @Test public void sameSizeSameTimeConflictAndBadLengthsKeepTheOriginal() throws Exception {
        RootFileOps.Request stale = request(1); FileTime modified = Files.getLastModifiedTime(target.toPath());
        byte[] foreign = new byte[]{9, 9, 9, 9}; Files.write(target.toPath(), foreign); Files.setLastModifiedTime(target.toPath(), modified);
        failure(() -> session.execute(stale, null, new ByteArrayInputStream(new byte[]{8})));
        assertArrayEquals(foreign, Files.readAllBytes(target.toPath()));
        for (long size : new long[]{0, 2}) {
            failure(() -> session.execute(request(size), null, new ByteArrayInputStream(new byte[]{8})));
            assertArrayEquals(foreign, Files.readAllBytes(target.toPath())); assertTrue(session.isAlive());
        }
        assertArrayEquals(new String[]{"target.bin"}, work.list());
    }
    @Test public void cancellationAfterPayloadCleansStagingWithoutPublishing() throws Exception {
        byte[] before = Files.readAllBytes(target.toPath()); AtomicBoolean ended = new AtomicBoolean();
        InputStream payload = new ByteArrayInputStream(new byte[]{5, 6, 7}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length); if (count == -1) ended.set(true); return count;
            }
        };
        try {
            session.execute(request(3), (code, text) -> code != RootFileOps.VERIFY || !ended.get(), payload);
            fail("Must cancel before publication");
        } catch (Tasks.CancelledException expected) { }
        assertArrayEquals(before, Files.readAllBytes(target.toPath())); assertArrayEquals(new String[]{"target.bin"}, work.list());
        assertTrue(session.isAlive());
    }
    @Test public void oversizedSourceStopsAtTheFirstExcessBlock() throws Exception {
        byte[] before = Files.readAllBytes(target.toPath()); java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        InputStream endless = new InputStream() {
            public int read() { reads.incrementAndGet(); return 9; }
            public int read(byte[] buffer, int offset, int count) { reads.incrementAndGet(); Arrays.fill(buffer, offset, offset + count, (byte) 9); return count; }
        };
        failure(() -> session.execute(request(1), null, endless));
        assertEquals(1, reads.get()); assertArrayEquals(before, Files.readAllBytes(target.toPath()));
        assertArrayEquals(new String[]{"target.bin"}, work.list()); assertTrue(session.isAlive());
    }
    @Test public void sourceFailureAndConcurrentTargetChangeDoNotPublish() throws Exception {
        byte[] before = Files.readAllBytes(target.toPath());
        failure(() -> session.execute(request(3), null, new InputStream() {
            public int read() throws IOException { throw new IOException("fixture read failure"); }
        }));
        assertArrayEquals(before, Files.readAllBytes(target.toPath()));
        FileTime modified = Files.getLastModifiedTime(target.toPath());
        InputStream payload = new ByteArrayInputStream(new byte[]{5, 6, 7}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                if (count == -1) try { Files.write(target.toPath(), new byte[]{9, 9, 9, 9}); Files.setLastModifiedTime(target.toPath(), modified); }
                catch (IOException error) { throw new RuntimeException(error); }
                return count;
            }
        };
        failure(() -> session.execute(request(3), null, payload));
        assertArrayEquals(new byte[]{9, 9, 9, 9}, Files.readAllBytes(target.toPath()));
        assertArrayEquals(new String[]{"target.bin"}, work.list()); assertTrue(session.isAlive());
    }
    @Test public void fileStoreWritesTwiceThroughTheExistingHelper() throws Exception {
        installFixtureChannels(); FileStore store = new FileStore(context); FileStore.Node node = FileStore.local(target, true);
        String revision = store.fingerprint(node);
        for (int i = 0; i < 2; i++) {
            File payload = new File(work, "payload-" + i); byte[] bytes = new byte[]{(byte) i, 6, 7}; Files.write(payload.toPath(), bytes);
            revision = store.write(node, payload, revision);
            assertArrayEquals(bytes, Files.readAllBytes(target.toPath())); assertSame(session, get(RootMutations.class, "cached"));
        }
    }
    @Test public void suppliedArchiveMovesWithRootTaggedSource() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("samplePath", ""); Assume.assumeFalse(path.isEmpty());
        installFixtureChannels();
        set(RootAccess.class, "available", false);
        File archive = new File(work, "source.zip"), destination = new File(work, "destination"); assertTrue(destination.mkdir());
        Files.copy(new File(path).toPath(), archive.toPath());
        FileStore store = new FileStore(context); FileStore.Node container = FileStore.local(archive, true);
        long start = SystemClock.elapsedRealtime();
        List<FileStore.Node> moved = store.copyAll(List.of(FileStore.archiveNode(container, "tools/drmstat", false, 7320, 0)), FileStore.directory(destination, false), true);
        long elapsed = SystemClock.elapsedRealtime() - start;
        assertEquals(7320, moved.get(0).file.length()); assertFalse(ArchiveOps.entryNames(archive).contains("tools/drmstat"));
        assertSame(session, get(RootMutations.class, "cached"));
        android.util.Log.i("ReportedZipTiming", "rootTaggedMoveMs=" + elapsed);
        Files.write(new File(context.getExternalFilesDir(null), "reported-root-move.txt").toPath(), Long.toString(elapsed).getBytes());
    }
    @Test public void readableRootDataAllowsMappedOwnerButRefusesDifferentInode() throws Exception {
        FileStore store = new FileStore(context); FileStore.Node node = FileStore.local(target, true);
        android.system.StructStat stat = android.system.Os.stat(target.getPath());
        String data = stat.st_dev + ":" + stat.st_ino + ":" + stat.st_size + ":" + stat.st_mtime;
        Method read = FileStore.class.getDeclaredMethod("read", FileStore.Node.class, String.class); read.setAccessible(true);
        try (InputStream input = (InputStream) read.invoke(store, node, data + ":600:0:0")) {
            assertArrayEquals(new byte[]{1, 2, 3, 4}, input.readAllBytes());
        }
        try { read.invoke(store, node, "999:999:" + stat.st_size + ":" + stat.st_mtime + ":600:0:0"); fail("Different ROOT inode must not use app bytes"); }
        catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IOException); }
    }
    private void installFixtureChannels() throws Exception {
        RootDirectoryReader.close(); RootMutations.close();
        Class<?> type = Class.forName("org.example.filemanager.RootDirectoryReader$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(ProcessBuilder.class); constructor.setAccessible(true);
        set(RootDirectoryReader.class, "session", constructor.newInstance(new ProcessBuilder("/system/bin/sh")));
        set(RootMutations.class, "cached", session); set(RootMutations.class, "cachedRoot", true);
        long now = SystemClock.elapsedRealtime(); set(RootAccess.class, "available", true); set(RootAccess.class, "checkedAt", now); set(RootAccess.class, "grantedUntil", now + 30000);
    }
    private RootFileOps.Request request(long size) throws Exception {
        FileStore.Node selected = FileStore.local(target, true);
        return RootFileOps.Request.replace(new RootFileOps.Spec(target.getPath(), selected.identity, selected.parentIdentity, null, false), size, FileStore.FileState.read(target).fingerprint());
    }
    private interface Failing { void run() throws Exception; }
    private void failure(Failing operation) throws Exception { try { operation.run(); fail("Expected guarded failure"); } catch (IOException expected) { } }
    private static void set(Class<?> type, String name, Object value) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value); }
    private static Object get(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
}
