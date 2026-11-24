package org.example.filemanager;

import android.content.Context;
import android.system.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RenameLinkFailureInstrumentedTest {
    private Context context;
    private File work, source, target;
    private byte[] payload;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "rename-link-").toFile();
        source = new File(work, "Tenc.apks"); target = new File(work, "Tenc.zip");
        payload = new byte[32 * 1024]; new Random(29).nextBytes(payload);
        Files.write(source.toPath(), payload);
    }
    @After public void cleanup() throws Exception { new FileStore(context).delete(FileStore.local(work)); }

    @Test public void einvalLinkFailureRenamesExtensionWithoutCopying() throws Exception {
        String before = id(Os.lstat(source.getPath()));
        RootFileOps.Result result = RootFileOps.renameLocal(request(), Os::rename, (from, to) -> {
            throw new ErrnoException("link", OsConstants.EINVAL);
        });
        assertEquals(before, result.identity); assertFalse(source.exists());
        assertArrayEquals(payload, Files.readAllBytes(target.toPath()));
        assertEquals(before, id(Os.lstat(target.getPath())));
        assertArrayEquals(new String[]{"Tenc.zip"}, work.list());
    }
    @Test public void einvalDoesNotOverwriteALateConflictingDestination() throws Exception {
        try {
            RootFileOps.renameLocal(request(), Os::rename, (from, to) -> {
                try { Files.write(new File(to).toPath(), "foreign".getBytes(StandardCharsets.UTF_8)); }
                catch (IOException e) { throw new AssertionError(e); }
                throw new ErrnoException("link", OsConstants.EINVAL);
            }); fail("Must reject occupied destination");
        } catch (IOException expected) { }
        assertArrayEquals(payload, Files.readAllBytes(source.toPath()));
        assertEquals("foreign", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void einvalRechecksSourceBeforeRenaming() throws Exception {
        File held = new File(work, "held");
        try {
            RootFileOps.renameLocal(request(), Os::rename, (from, to) -> {
                Os.rename(from, held.getPath());
                try { Files.write(new File(from).toPath(), new byte[]{9}); }
                catch (IOException e) { throw new AssertionError(e); }
                throw new ErrnoException("link", OsConstants.EINVAL);
            }); fail("Must reject replaced source");
        } catch (IOException expected) { }
        assertArrayEquals(payload, Files.readAllBytes(held.toPath()));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(source.toPath())); assertFalse(target.exists());
    }
    @Test public void renameErrorsAfterEinvalCleanOnlyTheOwnedReservation() throws Exception {
        try {
            RootFileOps.renameLocal(request(), (from, to) -> { throw new ErrnoException("rename", OsConstants.EIO); },
                    (from, to) -> { throw new ErrnoException("link", OsConstants.EINVAL); });
            fail("Must report rename error");
        } catch (IOException expected) {
            assertTrue(expected.getCause() instanceof ErrnoException);
            assertEquals(OsConstants.EIO, ((ErrnoException) expected.getCause()).errno);
        }
        assertArrayEquals(payload, Files.readAllBytes(source.toPath())); assertFalse(target.exists());
    }
    @Test public void unrelatedLinkErrorsRemainVisibleAndPreserveTheSource() throws Exception {
        try {
            RootFileOps.renameLocal(request(), (from, to) -> { throw new AssertionError("Do not rename after EIO"); },
                    (from, to) -> { throw new ErrnoException("link", OsConstants.EIO); });
            fail("Must report link error");
        } catch (IOException expected) {
            assertEquals(OsConstants.EIO, ((ErrnoException) expected.getCause()).errno);
        }
        assertArrayEquals(payload, Files.readAllBytes(source.toPath())); assertFalse(target.exists());
    }
    @Test public void sharedStorageHelperRenamesExtensionsAndRejectsExistingFiles() throws Exception {
        File external = Files.createTempDirectory(context.getExternalCacheDir().toPath(), "rename-extension-").toFile();
        try (RootMutations.Session session = new RootMutations.Session(context, false)) {
            File from = new File(external, "Tenc.apks"); Files.write(from.toPath(), payload);
            String original = id(Os.lstat(from.getPath()));
            for (String name : new String[]{"Tenc.zip", "中文 空格.apk", "Tenc.apks"}) {
                RootFileOps.Result result = session.execute(RootFileOps.Request.rename(spec(from), spec(external), name, false), null);
                assertEquals(original, result.identity); assertFalse(from.exists());
                from = new File(external, name); assertArrayEquals(payload, Files.readAllBytes(from.toPath()));
            }
            File occupied = new File(external, "occupied.zip"); Files.write(occupied.toPath(), new byte[]{7});
            try { session.execute(RootFileOps.Request.rename(spec(from), spec(external), occupied.getName(), false), null); fail(); }
            catch (IOException expected) { }
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(occupied.toPath()));
            assertArrayEquals(payload, Files.readAllBytes(from.toPath()));
        } finally { new FileStore(context).delete(FileStore.local(external)); }
    }
    private RootFileOps.Request request() throws Exception { return RootFileOps.Request.rename(spec(source), spec(work), target.getName(), false); }
    private static RootFileOps.Spec spec(File file) throws Exception {
        StructStat stat = Os.lstat(file.getPath());
        return new RootFileOps.Spec(file.getPath(), id(stat), id(Os.stat(file.getParent())), OsConstants.S_ISDIR(stat.st_mode) ? id(stat) : null, false);
    }
    private static String id(StructStat stat) { return stat.st_dev + ":" + stat.st_ino; }
}
