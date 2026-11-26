package org.example.filemanager;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.system.Os;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FileTransferSafetyInstrumentedTest {
    private static final Uri CONTROL = Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
    private Context app, isolated;
    private FileStore store;
    private Path sharedRoot, work;
    private Boolean previousAutomatic;

    @Before public void setUp() throws Exception {
        app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        sharedRoot = app.getCacheDir().toPath().resolve("shared");
        Files.createDirectories(sharedRoot);
        work = Files.createTempDirectory(sharedRoot, "move-audit-");
        File privateFiles = Files.createDirectory(work.resolve("files")).toFile();
        isolated = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return privateFiles; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                // The normal SAF recovery defaults apply, including its enabled-by-default backup.
                return app.getSharedPreferences(work.getFileName() + "-" + name, mode);
            }
        };
        Field automatic = RootAccess.class.getDeclaredField("automatic");
        automatic.setAccessible(true);
        previousAutomatic = automatic.getBoolean(null);
        RootAccess.setAutomatic(false);
        store = new FileStore(isolated);
    }

    @After public void tearDown() throws IOException {
        if (previousAutomatic != null) RootAccess.setAutomatic(previousAutomatic);
        if (work == null) return;
        app.deleteSharedPreferences(work.getFileName() + "-SafRecovery");
        app.deleteSharedPreferences(work.getFileName() + "-file-manager");
        assertEquals(sharedRoot, work.getParent());
        assertTrue(work.getFileName().toString().startsWith("move-audit-"));
        Files.walkFileTree(work, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }

    @Test public void movingSafDirectoryKeepsFileCreatedAfterEnumeration() throws Exception {
        Uri root = Uri.parse(BatchCopyInstrumentedTest.grantFixture(app).getString("uri"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            seed("root", "source", true, "");
            seed("root/source", "initial.txt", false, "initial data");
            Bundle config = new Bundle();
            config.putString("blockReadName", "initial.txt");
            call("configure", config);

            Path target = Files.createDirectory(work.resolve("target"));
            FileStore.Node source = store.document(DocumentsContract.buildDocumentUriUsingTree(root, "root/source"));
            Future<?> moving = executor.submit(() ->
                    store.copyAll(List.of(source), FileStore.local(target.toFile()), true));

            // The provider can only open the original member after the directory listing returned.
            long deadline = SystemClock.uptimeMillis() + 20000;
            boolean blocked = false;
            while (SystemClock.uptimeMillis() < deadline && !moving.isDone()) {
                Bundle state = call("readState", null);
                assertNull(state.getString("error"));
                if (state.getBoolean("prefixWritten")) { blocked = true; break; }
                Thread.sleep(10);
            }
            assertTrue("Expected the first member to pause after the listing", blocked);
            seed("root/source", "later.txt", false, "arrived during move");
            call("releaseRead", null);
            try { moving.get(25, TimeUnit.SECONDS); }
            catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof IOException)) throw failure;
                // Refusing to finish when the directory changes is also safe.
            }
            Path output = target.resolve("source/later.txt");
            boolean atSource = call("stats", null).getStringArrayList("paths").contains("source/later.txt");
            assertTrue("A child added during move vanished from both locations", atSource || Files.isRegularFile(output));
            byte[] expected = "arrived during move".getBytes(StandardCharsets.UTF_8);
            if (atSource) {
                Uri later = DocumentsContract.buildDocumentUriUsingTree(root, "root/source/later.txt");
                try (InputStream in = app.getContentResolver().openInputStream(later)) {
                    assertNotNull(in);
                    assertArrayEquals(expected, FileStore.readBytes(in, expected.length + 1));
                }
            }
            if (Files.isRegularFile(output)) assertArrayEquals(expected, Files.readAllBytes(output));
        } finally {
            call("releaseRead", null);
            executor.shutdownNow();
            assertTrue("Transfer worker did not stop", executor.awaitTermination(20, TimeUnit.SECONDS));
            call("reset", null);
        }
    }

    @Test public void overwriteByContentUriAliasPreservesOriginalBytes() throws Exception {
        Path file = work.resolve("same.bin");
        byte[] original = "the original nonempty contents".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        String authority = app.getPackageName() + ".files";
        Uri target = FileProvider.getUriForFile(app, authority, file.toFile());
        Uri parentUri = FileProvider.getUriForFile(app, authority, work.toFile());
        FileStore.Node source = FileStore.local(file.toFile());
        FileStore.Node existing = store.document(target);
        FileStore.Node parent = new FileStore.Node("destination", null, parentUri, true, false, 0, 0);
        // The app's share provider returns a real descriptor to this test's own file.
        // Use the conflict plan produced by the UI after it has already enumerated the destination.
        try (ParcelFileDescriptor fd = app.getContentResolver().openFileDescriptor(target, "r")) {
            assertNotNull(fd);
            assertEquals(FileStore.fileIdentity(Os.stat(file.toString())),
                    FileStore.fileIdentity(Os.fstat(fd.getFileDescriptor())));
        }
        assertNotEquals(source.key(), existing.key());
        TransferPlan plan = new TransferPlan();
        plan.directoryNames.put(parent.key(), Set.of(source.name));
        plan.items.add(new TransferPlan.Item(source, parent, FileStore.ConflictPolicy.OVERWRITE,
                existing, store.identity(existing)));
        try { store.executePlan(plan, false); }
        catch (IOException allowedIfOriginalSurvives) { /* Refusing an alias overwrite is safe. */ }
        assertArrayEquals("Overwriting the same file via another handle must preserve it",
                original, Files.readAllBytes(file));
    }

    @Test public void plannedMoveResumesAfterPartOfSourceCleanupFails() throws Exception {
        assertCleanupCanResume(true);
    }

    @Test public void ordinaryMoveOffersTheSameSafeCleanupRetry() throws Exception {
        assertCleanupCanResume(false);
    }

    @Test public void opaqueSameProviderMoveOverwritePreservesBothFiles() throws Exception {
        Uri root = Uri.parse(BatchCopyInstrumentedTest.grantFixture(app).getString("uri"));
        try {
            seed("root", "source", true, ""); seed("root", "destination", true, "");
            seed("root/source", "same.txt", false, "source bytes");
            seed("root/destination", "same.txt", false, "target bytes");
            FileStore.Node source = store.document(DocumentsContract.buildDocumentUriUsingTree(root, "root/source/same.txt"));
            FileStore.Node existing = store.document(DocumentsContract.buildDocumentUriUsingTree(root, "root/destination/same.txt"));
            FileStore.Node destination = store.document(DocumentsContract.buildDocumentUriUsingTree(root, "root/destination"));
            TransferPlan plan = new TransferPlan();
            plan.directoryNames.put(destination.key(), Set.of("same.txt"));
            plan.items.add(new TransferPlan.Item(source, destination, FileStore.ConflictPolicy.OVERWRITE, existing, store.identity(existing)));
            Bundle config = new Bundle(); config.putString("opaqueReadName", "same.txt"); call("configure", config);
            try { store.executePlan(plan, true); fail("Opaque identities from one provider must not be assumed distinct"); }
            catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("请先另存一份")); }
            assertEquals(0, call("stats", null).getInt("writeOpens"));
            assertEquals(0, call("stats", null).getInt("deleteCalls"));
            call("configure", new Bundle());
            try (InputStream input = store.read(source)) {
                assertEquals("source bytes", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (InputStream input = store.read(existing)) {
                assertEquals("target bytes", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally { call("reset", null); }
    }

    private void assertCleanupCanResume(boolean planned) throws Exception {
        Uri root = Uri.parse(BatchCopyInstrumentedTest.grantFixture(app).getString("uri"));
        try {
            seed("root", "source", true, "");
            seed("root/source", "a.txt", false, "first source file");
            seed("root/source", "b.txt", false, "second source file");
            Bundle config = new Bundle(); config.putString("failDeleteName", "a.txt");
            call("configure", config);
            Path target = Files.createDirectory(work.resolve("retry-target"));
            FileStore.Node source = store.document(DocumentsContract.buildDocumentUriUsingTree(root, "root/source"));
            FileStore.Node destination = FileStore.local(target.toFile());
            TransferPlan recovery;
            try {
                if (planned) {
                    TransferPlan plan = new TransferPlan();
                    plan.items.add(new TransferPlan.Item(source, destination, FileStore.ConflictPolicy.KEEP_BOTH, null, null));
                    store.executePlan(plan, true);
                } else store.copyAll(List.of(source), destination, true);
                fail("Injected source cleanup failure was ignored"); return;
            } catch (TransferPlan.Incomplete incomplete) { recovery = incomplete.plan; }
            assertEquals(1, recovery.published.size());
            assertEquals("first source file", new String(Files.readAllBytes(target.resolve("source/a.txt")), StandardCharsets.UTF_8));
            assertEquals("second source file", new String(Files.readAllBytes(target.resolve("source/b.txt")), StandardCharsets.UTF_8));
            assertEquals(List.of("source/", "source/a.txt"), call("stats", null).getStringArrayList("paths"));

            call("configure", new Bundle());
            List<FileStore.Node> outputs = store.executePlan(recovery, true);
            assertEquals(1, outputs.size());
            assertEquals(target.resolve("source").toFile(), outputs.get(0).file);
            try (var children = Files.list(target)) { assertEquals(1, children.count()); }
            // SAF cannot guarantee a nonrecursive rmdir. Retain its empty folder while moving every file.
            assertEquals(List.of("source/"), call("stats", null).getStringArrayList("paths"));
            assertEquals(1, recovery.retainedSourceDirectories);
        } finally { call("reset", null); }
    }

    private Bundle call(String method, Bundle extras) {
        Bundle response = app.getContentResolver().call(CONTROL, method, null, extras);
        assertNotNull(response); return response;
    }

    private void seed(String parent, String name, boolean directory, String contents) {
        Bundle request = new Bundle();
        request.putString("parent", parent); request.putString("name", name);
        request.putBoolean("directory", directory); request.putString("text", contents);
        call("seed", request);
    }
}
