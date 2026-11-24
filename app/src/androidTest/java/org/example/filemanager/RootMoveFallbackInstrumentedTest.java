package org.example.filemanager;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RootMoveFallbackInstrumentedTest {
    private Context context;
    private File work, source, target;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "move-fallback-").toFile();
        source = new File(work, "source"); assertTrue(source.mkdir());
        target = new File(work, "target");
        Files.write(new File(source, "payload").toPath(), "keep".getBytes(StandardCharsets.UTF_8));
    }

    @After public void cleanup() throws Exception {
        new FileStore(context).delete(FileStore.local(work));
    }

    @Test public void sameDeviceExdevCleansReservationBeforeAllowingCopyFallback() throws Exception {
        String identity = identity(Os.lstat(source.getPath()));
        RootFileOps.Result result = RootFileOps.moveShared(request(), (from, to) -> {
            assertTrue(new File(from).isDirectory());
            assertTrue(new File(to).isDirectory());
            assertEquals(0, new File(to).list().length);
            throw new ErrnoException("rename", OsConstants.EXDEV);
        });
        assertNull(result);
        assertEquals(identity, identity(Os.lstat(source.getPath())));
        assertEquals("keep", text(new File(source, "payload")));
        assertFalse(target.exists());
        assertArrayEquals(new String[]{"source"}, work.list());
    }

    @Test public void otherRenameErrorsStillFailAfterCleaningOwnedReservation() throws Exception {
        try {
            RootFileOps.moveShared(request(), (from, to) -> { throw new ErrnoException("rename", OsConstants.EIO); });
            fail("EIO must be reported");
        } catch (IOException expected) {
            assertTrue(expected.getCause() instanceof ErrnoException);
            assertEquals(OsConstants.EIO, ((ErrnoException) expected.getCause()).errno);
        }
        assertTrue(source.isDirectory()); assertFalse(target.exists());
        assertEquals("keep", text(new File(source, "payload")));
    }
    @Test public void erangeOnPrivateFilesystemIsNotTreatedAsCrossUserFallback() throws Exception {
        try {
            RootFileOps.moveShared(request(), (from, to) -> { throw new ErrnoException("rename", OsConstants.ERANGE); });
            fail("Private filesystem ERANGE must remain visible");
        } catch (IOException expected) {
            assertTrue(expected.getCause() instanceof ErrnoException);
            assertEquals(OsConstants.ERANGE, ((ErrnoException) expected.getCause()).errno);
        }
        assertTrue(source.isDirectory());assertFalse(target.exists());
        assertEquals("keep", text(new File(source, "payload")));
    }

    @Test public void replacedSourceRefusesFallbackAndRetainsBothVersions() throws Exception {
        File held = new File(work, "held");
        try {
            RootFileOps.moveShared(request(), (from, to) -> {
                Os.rename(from, held.getPath());
                Os.mkdir(from, 0700);
                throw new ErrnoException("rename", OsConstants.EXDEV);
            });
            fail("Changed source must refuse fallback");
        } catch (IOException expected) { }
        assertTrue(source.isDirectory()); assertFalse(target.exists());
        assertEquals("keep", text(new File(held, "payload")));
    }

    @Test public void replacedReservationRefusesFallbackAndRetainsForeignTarget() throws Exception {
        try {
            RootFileOps.moveShared(request(), (from, to) -> {
                Os.remove(to);
                try { Files.write(new File(to).toPath(), "foreign".getBytes(StandardCharsets.UTF_8)); }
                catch (IOException error) { throw new AssertionError(error); }
                throw new ErrnoException("rename", OsConstants.EXDEV);
            });
            fail("Foreign target must refuse fallback");
        } catch (IOException expected) { }
        assertEquals("foreign", text(target));
        assertEquals("keep", text(new File(source, "payload")));
    }

    @Test public void nonemptyReservationRefusesFallbackAndRetainsForeignChild() throws Exception {
        try {
            RootFileOps.moveShared(request(), (from, to) -> {
                try { Files.write(new File(to, "foreign").toPath(), new byte[]{9}); }
                catch (IOException error) { throw new AssertionError(error); }
                throw new ErrnoException("rename", OsConstants.EXDEV);
            });
            fail("Failed reservation cleanup must refuse fallback");
        } catch (IOException expected) { }
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(new File(target, "foreign").toPath()));
        assertEquals("keep", text(new File(source, "payload")));
    }

    private RootFileOps.Request request() throws Exception {
        StructStat from = Os.lstat(source.getPath()), parent = Os.stat(work.getPath());
        assertEquals(from.st_dev, parent.st_dev);
        return RootFileOps.Request.rename(
                new RootFileOps.Spec(source.getPath(), identity(from), identity(parent), identity(from), false),
                new RootFileOps.Spec(work.getPath(), identity(parent), identity(Os.stat(work.getParent())), identity(parent), false),
                target.getName(), true);
    }
    private static String identity(StructStat stat) { return stat.st_dev + ":" + stat.st_ino; }
    private static String text(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
}
