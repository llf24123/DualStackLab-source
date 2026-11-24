package org.example.filemanager;

import android.content.Context;
import android.net.Uri;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Own-UID tests exercise real filesystem effects and helper framing, without changing global files. */
@RunWith(AndroidJUnit4.class)
public class BatchFileAttributesInstrumentedTest {
    private Context context;
    private File fixture;
    private static final long MTIME = 1700000000000L, ATIME = 1600000000000L;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RootAccess.setAutomatic(false);
        fixture = new File(context.getCacheDir(), "batch-attributes-" + UUID.randomUUID());
        assertTrue(fixture.mkdir());
    }
    @After public void cleanup() throws Exception {
        try { if (fixture != null) erase(fixture); }
        finally { RootMutations.close(); RootAccess.setAutomatic(FileManagerSettings.prefs(context).getBoolean("root", true)); }
    }

    @Test public void ownFileOwnerModeAndTwoTimesAreVerifiedWithoutChangingBytes() throws Exception {
        File file = text(fixture, "file", "payload"); StructStat before = Os.lstat(file.getPath());
        BatchFileAttributes.Change change = new BatchFileAttributes.Change(0640, (long) before.st_uid, (long) before.st_gid, MTIME, ATIME);
        BatchFileAttributes.Result result = BatchFileAttributes.apply(context, List.of(FileStore.local(file)), change);
        assertEquals(result.summary(), 1, result.succeeded()); assertEquals(0, result.failed());
        StructStat actual = Os.lstat(file.getPath());
        assertEquals(before.st_ino, actual.st_ino); assertEquals(0640, actual.st_mode & 07777);
        assertEquals(before.st_uid, actual.st_uid); assertEquals(before.st_gid, actual.st_gid);
        assertEquals(MTIME / 1000, actual.st_mtime); assertEquals(ATIME / 1000, actual.st_atime);
        assertEquals("payload", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void failedEntryDoesNotBlockLaterEntryAndDefaultDoesNotRecurse() throws Exception {
        File dir = new File(fixture, "directory"); assertTrue(dir.mkdir());
        File child = text(dir, "child", "keep"); Os.chmod(child.getPath(), 0644);
        File second = text(fixture, "second", "second");
        FileStore.Node missing = FileStore.local(new File(fixture, "missing"));
        BatchFileAttributes.Result result = BatchFileAttributes.apply(context,
                List.of(FileStore.local(dir), missing, FileStore.local(second)), new BatchFileAttributes.Change(0700, null, null, MTIME, null));
        assertEquals(2, result.succeeded()); assertEquals(1, result.failed()); assertEquals(3, result.items().size());
        assertEquals(0644, Os.lstat(child.getPath()).st_mode & 07777);
        assertEquals(0700, Os.lstat(dir.getPath()).st_mode & 07777);
        assertEquals(MTIME / 1000, Os.lstat(second.getPath()).st_mtime);
    }

    @Test public void recursiveFilesAndDirectoriesAreIndependentAndDirectoriesApplyLast() throws Exception {
        File dir = new File(fixture, "directory"), nested = new File(dir, "nested");
        assertTrue(dir.mkdir()); assertTrue(nested.mkdir());
        File first = text(dir, "first", "first"), second = text(nested, "second", "second");
        File outside = text(fixture, "outside", "outside"); Os.chmod(outside.getPath(), 0644);
        File link = new File(dir, "link"); Os.symlink(outside.getPath(), link.getPath());
        BatchFileAttributes.Result files = BatchFileAttributes.apply(context, List.of(FileStore.local(dir)),
                new BatchFileAttributes.Change(null, null, null, MTIME, null), true, false);
        assertEquals(3, files.succeeded()); assertEquals(1, files.failed()); // link is explicitly reported
        assertEquals(MTIME / 1000, Os.lstat(first.getPath()).st_mtime);
        assertEquals(MTIME / 1000, Os.lstat(second.getPath()).st_mtime);
        assertNotEquals(MTIME / 1000, Os.lstat(nested.getPath()).st_mtime);
        assertNotEquals(MTIME / 1000, Os.lstat(outside.getPath()).st_mtime);
        BatchFileAttributes.Result directories = BatchFileAttributes.apply(context, List.of(FileStore.local(dir)),
                new BatchFileAttributes.Change(0500, null, null, null, null), false, true);
        assertEquals(2, directories.succeeded()); assertEquals(0500, Os.lstat(nested.getPath()).st_mode & 07777);
        assertEquals(dir.getPath(), directories.items().get(directories.items().size() - 1).node().path());
        assertEquals(0644, Os.lstat(outside.getPath()).st_mode & 07777);
    }

    @Test public void cancellationKeepsCompletedResultAndLeavesRemainingNodesUntouched() throws Exception {
        File first = text(fixture, "first", "first"), second = text(fixture, "second", "second");
        Tasks.Progress progress = new Tasks.Progress();
        BatchFileAttributes.Result result = progress.run(() -> BatchFileAttributes.apply(context,
                List.of(FileStore.local(first), FileStore.local(second)), new BatchFileAttributes.Change(null, null, null, MTIME, null),
                done -> progress.cancelled.set(true)));
        assertTrue(result.cancelled()); assertEquals(1, result.succeeded()); assertEquals(1, result.remaining().size());
        assertEquals(MTIME / 1000, Os.lstat(first.getPath()).st_mtime);
        assertNotEquals(MTIME / 1000, Os.lstat(second.getPath()).st_mtime);
        try { result.items().clear(); fail("mutable results"); } catch (UnsupportedOperationException expected) { }
        try { result.remaining().clear(); fail("mutable remaining"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void archiveSafStaleAndSymbolicLinkAreReportedWithoutTouchingTarget() throws Exception {
        File file = text(fixture, "file", "unchanged"), link = new File(fixture, "link");
        Os.symlink(file.getPath(), link.getPath());
        FileStore.Node stale = FileStore.local(file); stale.identity = "0:0";
        FileStore.Node saf = new FileStore.Node("document", null, Uri.parse("content://example/document/test"), false, false, 0, 0);
        FileStore.Node archive = FileStore.archiveNode(FileStore.local(file), "member", false, 0, 0);
        BatchFileAttributes.Result result = BatchFileAttributes.apply(context, List.of(archive, saf, stale, FileStore.local(link)),
                new BatchFileAttributes.Change(null, null, null, MTIME, null));
        assertEquals(0, result.succeeded()); assertEquals(4, result.failed());
        assertNotEquals(MTIME / 1000, Os.lstat(file.getPath()).st_mtime);
        assertEquals("unchanged", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void helperOwnerTimesRoundTripAndRejectLateReplacement() throws Exception {
        File file = text(fixture, "file", "source"), moved = new File(fixture, "moved");
        StructStat original = Os.lstat(file.getPath());
        try (RootMutations.Session session = new RootMutations.Session(context, false)) {
            RootFileOps.Result owner = session.execute(RootFileOps.Request.owner(spec(file), (long) original.st_uid, null), null);
            assertEquals(original.st_uid, owner.uid);
            RootFileOps.Result times = session.execute(RootFileOps.Request.times(spec(file), MTIME, ATIME), null);
            assertEquals(MTIME, times.modified); assertEquals(ATIME / 1000, Os.lstat(file.getPath()).st_atime);
            try {
                session.execute(RootFileOps.Request.times(spec(file), MTIME + 10000, null), (event, name) -> {
                    if (event == RootFileOps.STEP) {
                        try { Os.rename(file.getPath(), moved.getPath()); }
                        catch (android.system.ErrnoException error) { throw new IOException(error); }
                        text(fixture, "file", "replacement");
                    }
                    return true;
                });
                fail("late replacement accepted");
            } catch (IOException expected) { }
            assertEquals(MTIME / 1000, Os.lstat(moved.getPath()).st_mtime);
            assertNotEquals((MTIME + 10000) / 1000, Os.lstat(file.getPath()).st_mtime);
        }
    }

    @Test public void invalidBatchParametersCannotPartiallyModifyEarlierFiles() throws Exception {
        File file = text(fixture, "file", "payload");
        for (BatchFileAttributes.Change change : List.of(new BatchFileAttributes.Change(null, null, null, null, null),
                new BatchFileAttributes.Change(0600, -1L, null, null, null),
                new BatchFileAttributes.Change(0600, null, null, MTIME + 1, null))) {
            try { BatchFileAttributes.apply(context, List.of(FileStore.local(file)), change); fail("invalid change accepted"); }
            catch (IOException expected) { }
        }
        assertNotEquals(MTIME / 1000, Os.lstat(file.getPath()).st_mtime);
    }

    @Test public void failedOwnerRetainsLaterSuccessAndCombinedZeroModeDoesNotBlockTime() throws Exception {
        File file = text(fixture, "partial", "payload");
        BatchFileAttributes.Result partial = BatchFileAttributes.apply(context, List.of(FileStore.local(file)),
                new BatchFileAttributes.Change(0640, 0L, null, MTIME, null));
        assertEquals(1, partial.failed()); assertEquals(1, partial.partial());
        assertTrue(partial.items().get(0).applied().contains("权限"));
        assertTrue(partial.items().get(0).applied().contains("时间"));
        assertEquals(0640, Os.lstat(file.getPath()).st_mode & 07777);
        assertEquals(MTIME / 1000, Os.lstat(file.getPath()).st_mtime);
        BatchFileAttributes.Result zero = BatchFileAttributes.apply(context, List.of(FileStore.local(file)),
                new BatchFileAttributes.Change(0, null, null, MTIME + 1000, null));
        assertEquals(zero.summary(), 1, zero.succeeded());
        assertEquals(0, Os.lstat(file.getPath()).st_mode & 07777);
        assertEquals(MTIME / 1000 + 1, Os.lstat(file.getPath()).st_mtime);
    }

    @Test public void unreadableRecursiveDirectoryIsPartialAndOverlappingSelectionsAreNotRepeated() throws Exception {
        File dir = new File(fixture, "unreadable"); assertTrue(dir.mkdir());
        File child = text(dir, "child", "preserved");
        Os.chmod(dir.getPath(), 0000);
        BatchFileAttributes.Result blocked = BatchFileAttributes.apply(context, List.of(FileStore.local(dir)),
                new BatchFileAttributes.Change(0700, null, null, null, null), true, true);
        assertEquals(1, blocked.failed()); assertEquals(1, blocked.partial());
        assertTrue(blocked.items().get(0).errors().get(0).contains("子项读取失败"));
        assertEquals(0700, Os.lstat(dir.getPath()).st_mode & 07777);
        BatchFileAttributes.Result overlapping = BatchFileAttributes.apply(context,
                List.of(FileStore.local(child), FileStore.local(dir)), new BatchFileAttributes.Change(null, null, null, MTIME, null), true, true);
        assertEquals(2, overlapping.succeeded()); assertEquals(2, overlapping.items().size());
        assertEquals(dir.getPath(), overlapping.items().get(1).node().path());
    }

    private static RootFileOps.Spec spec(File file) throws Exception {
        StructStat stat = Os.lstat(file.getPath()), parent = Os.stat(file.getParent());
        return new RootFileOps.Spec(file.getPath(), FileStore.fileIdentity(stat), FileStore.fileIdentity(parent),
                OsConstants.S_ISDIR(stat.st_mode) ? FileStore.fileIdentity(stat) : null, false);
    }
    private static File text(File parent, String name, String text) throws IOException {
        File file = new File(parent, name); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private static void erase(File file) throws Exception {
        StructStat stat = Os.lstat(file.getPath());
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            Os.chmod(file.getPath(), 0700);
            File[] children = file.listFiles(); if (children != null) for (File child : children) erase(child);
        }
        Os.remove(file.getPath());
    }
}
