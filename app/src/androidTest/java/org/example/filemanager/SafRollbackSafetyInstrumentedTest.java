package org.example.filemanager;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SafRollbackSafetyInstrumentedTest {
    private static final Uri CONTROL = Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
    private Context app;
    private FileStore store;
    private FileStore.Node documents;
    private Uri tree;
    private Path work;
    private Boolean previousAutomatic;

    @Before public void createFixture() throws Exception {
        app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Field automatic = RootAccess.class.getDeclaredField("automatic"); automatic.setAccessible(true);
        previousAutomatic = automatic.getBoolean(null); RootAccess.setAutomatic(false);
        store = new FileStore(app);
        tree = Uri.parse(BatchCopyInstrumentedTest.grantFixture(app).getString("uri"));
        documents = store.document(tree);
        work = Files.createTempDirectory(app.getCacheDir().toPath(), "saf-rollback-");
    }

    @After public void removeFixture() throws Exception {
        try {
            if (tree != null) call("reset", null);
            if (work == null) return;
            assertEquals(app.getCacheDir().toPath(), work.getParent());
            assertTrue(work.getFileName().toString().startsWith("saf-rollback-"));
            Files.walkFileTree(work, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    Files.delete(path); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(path); return FileVisitResult.CONTINUE;
                }
            });
        } finally { if (previousAutomatic != null) RootAccess.setAutomatic(previousAutomatic); }
    }

    @Test public void failedCopyKeepsFileArrivingAtTopLevelDirectoryDeletionBoundary() throws Exception {
        seed("root", "keep.txt", false, "neighbour");
        Path source = Files.createDirectory(work.resolve("source"));
        Files.write(source.resolve("a-good.txt"), "source-a".getBytes(StandardCharsets.UTF_8));
        Files.write(source.resolve("b-broken.txt"), "source-b".getBytes(StandardCharsets.UTF_8));
        Bundle config = new Bundle(); config.putString("failCreateName", "b-broken.txt");
        config.putString("blockDeleteName", "source"); call("configure", config);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<IOException> copying = worker.submit(() -> {
            try {
                store.copyAll(List.of(FileStore.local(source.toFile())), documents, false);
                throw new AssertionError("Injected create failure was ignored");
            } catch (IOException error) { return error; }
        });
        try {
            long deadline = SystemClock.uptimeMillis() + 20000;
            // An unsafe implementation pauses just before recursively deleting the empty folder.
            // The safe implementation finishes rollback with that folder retained.
            while (!copying.isDone() && !call("deleteState", null).getBoolean("opened")
                    && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
            assertTrue("Copy did not reach its rollback boundary", copying.isDone()
                    || call("deleteState", null).getBoolean("opened"));
            seed("root/source", "later.txt", false, "arrived during rollback");
            call("releaseDelete", null);
            assertTrue(causeContains(copying.get(20, TimeUnit.SECONDS), "Injected create failure"));
            assertEquals(List.of("keep.txt", "source/", "source/later.txt"), call("stats", null).getStringArrayList("paths"));
            assertEquals("arrived during rollback", text("root/source/later.txt"));
            assertEquals("Only the owned regular file may be removed", 1, call("stats", null).getInt("deleteCalls"));
            assertEquals("source-a", new String(Files.readAllBytes(source.resolve("a-good.txt")), StandardCharsets.UTF_8));
            assertEquals("source-b", new String(Files.readAllBytes(source.resolve("b-broken.txt")), StandardCharsets.UTF_8));
        } finally {
            call("releaseDelete", null); worker.shutdownNow();
            assertTrue("Rollback worker did not stop", worker.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test public void rollbackKeepsFileReplacedAtTheSameDocumentUri() throws Exception {
        FileStore.Node owned = store.create(documents, "owned.bin", false);
        FileStore.CopyLedger ledger = store.new CopyLedger(); ledger.record(owned);
        recordedWrite(ledger, owned, "our completed bytes");
        String original = identity(owned);
        store.rename(owned, "saved-original.bin");
        seed("root", "owned.bin", false, "foreign replacement");
        assertNotEquals(original, identity(owned));
        ledger.rollback(new IOException("Injected later failure"));
        assertEquals("foreign replacement", text("root/owned.bin"));
        assertEquals("our completed bytes", text("root/saved-original.bin"));
        assertEquals(0, call("stats", null).getInt("deleteCalls"));
    }

    @Test public void rollbackKeepsOwnedInodeEditedByAnotherWriter() throws Exception {
        FileStore.Node owned = store.create(documents, "edited.bin", false);
        FileStore.CopyLedger ledger = store.new CopyLedger(); ledger.record(owned);
        recordedWrite(ledger, owned, "our completed bytes");
        String original = identity(owned);
        try (OutputStream output = app.getContentResolver().openOutputStream(owned.uri, "wt")) {
            assertNotNull(output); output.write("foreign edit".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("This fixture must modify the original inode", original, identity(owned));
        ledger.rollback(new IOException("Injected later failure"));
        assertEquals("foreign edit", text("root/edited.bin"));
        assertEquals(0, call("stats", null).getInt("deleteCalls"));
    }

    @Test public void rollbackKeepsFileWhenProviderHidesItsCurrentIdentityBehindAPipe() throws Exception {
        FileStore.Node owned = store.create(documents, "opaque.bin", false);
        FileStore.CopyLedger ledger = store.new CopyLedger(); ledger.record(owned);
        recordedWrite(ledger, owned, "our completed bytes");
        Bundle config = new Bundle(); config.putString("alternateReadName", "opaque.bin");
        config.putInt("alternateReadAt", 1); call("configure", config);
        ledger.rollback(new IOException("Injected later failure"));
        call("configure", new Bundle());
        assertEquals("our completed bytes", text("root/opaque.bin"));
        assertEquals(0, call("stats", null).getInt("deleteCalls"));
    }

    @Test public void failedTopLevelFileCopyRemovesOnlyItsVerifiedPartialOutput() throws Exception {
        seed("root", "source", true, ""); seed("root", "destination", true, "");
        seed("root/source", "broken.bin", false, "x".repeat(2048));
        seed("root/destination", "keep.txt", false, "neighbour");
        Bundle config = new Bundle(); config.putString("failReadAfterPrefixName", "root/source/broken.bin");
        call("configure", config);
        try {
            store.copyAll(List.of(document("root/source/broken.bin")), document("root/destination"), false);
            fail("Injected mid-read failure was ignored");
        } catch (IOException expected) { assertTrue(causeContains(expected, "Injected mid-read failure")); }
        assertEquals(List.of("destination/", "destination/keep.txt", "source/", "source/broken.bin"),
                call("stats", null).getStringArrayList("paths"));
        assertEquals("neighbour", text("root/destination/keep.txt"));
        assertEquals(1, call("stats", null).getInt("deleteCalls"));
    }

    private void recordedWrite(FileStore.CopyLedger ledger, FileStore.Node node, String value) throws Exception {
        try (OutputStream output = app.getContentResolver().openOutputStream(node.uri, "wt")) {
            assertNotNull(output); ledger.recording(node, output).write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
    private String identity(FileStore.Node node) throws Exception {
        try (ParcelFileDescriptor descriptor = app.getContentResolver().openFileDescriptor(node.uri, "r")) {
            assertNotNull(descriptor); return FileStore.fileIdentity(Os.fstat(descriptor.getFileDescriptor()));
        }
    }
    private FileStore.Node document(String id) throws IOException {
        return store.document(DocumentsContract.buildDocumentUriUsingTree(tree, id));
    }
    private String text(String id) throws Exception {
        try (InputStream input = store.read(document(id))) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private Bundle call(String method, Bundle extras) {
        Bundle result = app.getContentResolver().call(CONTROL, method, null, extras); assertNotNull(result); return result;
    }
    private void seed(String parent, String name, boolean directory, String text) {
        Bundle extras = new Bundle(); extras.putString("parent", parent); extras.putString("name", name);
        extras.putBoolean("directory", directory); extras.putString("text", text); call("seed", extras);
    }
    private static boolean causeContains(Throwable error, String value) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (String.valueOf(current.getMessage()).contains(value)) return true;
        return false;
    }
}
