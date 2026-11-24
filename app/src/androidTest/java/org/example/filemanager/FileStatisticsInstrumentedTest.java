package org.example.filemanager;

import android.content.Context;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FileStatisticsInstrumentedTest {
    private Context context;
    private File work;
    private FileStore store;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "file-statistics-").toFile();
        store = new FileStore(context);
    }
    @After public void cleanup() throws Exception {
        if (work != null) try (var paths = Files.walk(work.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private File file(File folder, String name, int size) throws IOException {
        File file = new File(folder, name); Files.write(file.toPath(), new byte[size]); return file;
    }
    private File directory(File folder, String name) throws IOException {
        File file = new File(folder, name); Files.createDirectory(file.toPath()); return file;
    }
    @Test public void singleFileRefreshesSizeAndFinishesWithExactBytes() throws Exception {
        File file = file(work, "changing.bin", 4); FileStore.Node stale = FileStore.local(file);
        Files.write(file.toPath(), new byte[4097]);
        List<FileStatistics.Snapshot> updates = new ArrayList<>();
        var result = new FileStatistics.Scan().run(store, List.of(stale), updates::add);
        assertEquals(4097, result.snapshot().bytes()); assertEquals(1, result.snapshot().files());
        assertEquals(0, result.snapshot().directories()); assertTrue(result.snapshot().complete());
        assertFalse(updates.get(0).finished()); assertTrue(updates.get(updates.size() - 1).finished());
    }
    @Test public void verifiedSharedStorageDirectoriesSwitchRecursiveReadsToLocalAccess() {
        assertTrue(FileStatistics.isDirectSharedStoragePath("/storage/emulated/0/Download"));
        assertTrue(FileStatistics.isDirectSharedStoragePath("/storage/emulated/0/AndroidMedia"));
        assertFalse(FileStatistics.isDirectSharedStoragePath("/storage/emulated/0/Android/data/app"));
        FileStore.Node root = new FileStore.Node("Download", new File("/storage/emulated/0/Download"), null,
                true, true, 0, 0);
        FileStore.Node local = new FileStore.Node("Download", root.file, null, true, false, 0, 0);
        var privileged = new FileStatistics.Entry(root, "inode:1:2", FileStatistics.Kind.DIRECTORY, 0);
        var direct = new FileStatistics.Entry(local, "inode:1:2", FileStatistics.Kind.DIRECTORY, 0);
        assertSame(local, FileStatistics.preferVerifiedLocalEntry(privileged, direct).node());
        var replaced = new FileStatistics.Entry(local, "inode:1:3", FileStatistics.Kind.DIRECTORY, 0);
        assertSame(root, FileStatistics.preferVerifiedLocalEntry(privileged, replaced).node());
    }
    @Test public void recursiveSelectionsDeduplicateParentsChildrenAndDirectoryMetadata() throws Exception {
        File a = directory(work, "tree"), b = directory(a, "nested");
        File first = file(a, "first", 11), second = file(b, "second", 23); file(b, "empty", 0);
        for (List<FileStore.Node> selections : List.of(
                List.of(FileStore.local(first), FileStore.local(b), FileStore.local(a), FileStore.local(second)),
                List.of(FileStore.local(a), FileStore.local(second), FileStore.local(b), FileStore.local(first)))) {
            var state = new FileStatistics.Scan().run(store, selections).snapshot();
            assertEquals(34, state.bytes()); assertEquals(3, state.files()); assertEquals(2, state.directories());
            assertEquals(3, state.duplicateNodes()); assertTrue(state.complete());
        }
    }
    @Test public void linksAreNotFollowedIncludingBrokenAndRecursiveTargets() throws Exception {
        File a = directory(work, "tree"); File original = file(a, "payload", 37);
        Os.symlink(a.getAbsolutePath(), new File(a, "loop").getAbsolutePath());
        Os.symlink(new File(work, "missing").getAbsolutePath(), new File(a, "broken").getAbsolutePath());
        Os.symlink(original.getAbsolutePath(), new File(a, "shortcut").getAbsolutePath());
        var state = new FileStatistics.Scan().run(store, List.of(FileStore.local(a))).snapshot();
        assertEquals(37, state.bytes()); assertEquals(1, state.files()); assertEquals(1, state.directories());
        assertEquals(3, state.skippedLinks()); assertEquals(0, state.duplicateNodes()); assertTrue(state.complete());
        var link = new FileStatistics.Scan().run(store, List.of(FileStore.local(new File(a, "loop")))).snapshot();
        assertEquals(0, link.bytes()); assertEquals(0, link.directories()); assertEquals(1, link.skippedLinks());
    }
    @Test public void sameInodeAliasesAreCountedOnceInTraversal() throws Exception {
        Fixture fixture = new Fixture(); var first = fixture.file("first-alias", 37); var second = fixture.file("second-alias", 37);
        fixture.entries.put(second, new FileStatistics.Entry(second, "first-alias", FileStatistics.Kind.FILE, 37));
        var result = new FileStatistics.Scan().run(fixture, List.of(first, second), null);
        assertEquals(37, result.snapshot().bytes()); assertEquals(1, result.snapshot().files()); assertEquals(1, result.snapshot().duplicateNodes());
    }
    @Test public void actualHardLinkAliasesAreCountedOnceWhenPlatformAllowsLink() throws Exception {
        File original = file(work, "original", 37), alias = new File(work, "alias");
        try { Os.link(original.getPath(), alias.getPath()); }
        catch (android.system.ErrnoException denied) { Assume.assumeNoException("Platform disallows hard links", denied); }
        var result = new FileStatistics.Scan().run(store, List.of(FileStore.local(original), FileStore.local(alias)));
        assertEquals(37, result.snapshot().bytes()); assertEquals(1, result.snapshot().files()); assertEquals(1, result.snapshot().duplicateNodes());
    }
    @Test public void unreadableOrMissingItemDoesNotDiscardOtherSelections() throws Exception {
        File good = file(work, "good", 19), missing = new File(work, "missing");
        var result = new FileStatistics.Scan().run(store, List.of(FileStore.local(missing), FileStore.local(good)));
        assertEquals(19, result.snapshot().bytes()); assertEquals(1, result.snapshot().errorCount());
        assertEquals(1, result.errors().size()); assertTrue(result.errors().get(0).path().endsWith("missing"));
        assertFalse(result.snapshot().complete());
    }
    @Test public void archiveSelectionUsesUncompressedMetadataAndFreshRestoredEntrySizes() throws Exception {
        File archive = new File(work, "nested.zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            for (var item : Map.of("folder/one", 91, "folder/nested/two", 16384, "outside", 8).entrySet()) {
                output.putNextEntry(new ZipEntry(item.getKey())); output.write(new byte[item.getValue()]); output.closeEntry();
            }
        }
        var source = FileStore.local(archive);
        var one = store.fromKey(FileStore.archiveNode(source, "folder/one", false, 0, 0).key());
        var folder = FileStore.archiveNode(source, "folder/", true, 99999, 0);
        var result = new FileStatistics.Scan().run(store, List.of(one, folder, one));
        assertEquals(16475, result.snapshot().bytes()); assertEquals(2, result.snapshot().files());
        assertEquals(2, result.snapshot().directories()); assertEquals(2, result.snapshot().duplicateNodes());
        assertTrue(result.snapshot().complete());
    }
    @Test public void compressedStreamUnknownLengthIsNotReportedAsCompleteZero() throws Exception {
        File gzip = new File(work, "unknown.txt.gz");
        try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(gzip))) { out.write(new byte[20000]); }
        FileStore.Node root = FileStore.archiveNode(FileStore.local(gzip), "", true, 0, 0);
        var result = new FileStatistics.Scan().run(store, List.of(root));
        assertEquals(1, result.snapshot().files()); assertEquals(1, result.snapshot().unknownFiles());
        assertEquals(0, result.snapshot().bytes()); assertFalse(result.snapshot().complete());
        assertTrue(result.errors().isEmpty());
    }
    @Test public void safDirectoryCountsWithoutOpeningPayloadAndDeduplicatesUriAliases() throws Exception {
        Uri control = Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY);
        Uri root = Uri.parse(BatchCopyInstrumentedTest.grantFixture(context).getString("uri"));
        try {
            Bundle folder = new Bundle(); folder.putString("name", "folder"); folder.putBoolean("directory", true);
            String folderId = context.getContentResolver().call(control, "seed", null, folder).getString("id");
            Bundle payload = new Bundle(); payload.putString("parent", folderId); payload.putString("name", "payload"); payload.putByteArray("bytes", new byte[173]);
            String fileId = context.getContentResolver().call(control, "seed", null, payload).getString("id");
            Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(root, fileId);
            context.getContentResolver().call(control, "clearCounters", null, null);
            var result = new FileStatistics.Scan().run(store, List.of(store.document(fileUri), store.document(root)));
            assertEquals(173, result.snapshot().bytes()); assertEquals(1, result.snapshot().files());
            assertEquals(2, result.snapshot().directories()); assertEquals(1, result.snapshot().duplicateNodes());
            assertTrue(result.snapshot().complete());
            Bundle counts = context.getContentResolver().call(control, "stats", null, null);
            assertEquals(0, counts.getInt("readOpens")); assertEquals(2, counts.getInt("childQueries"));
        } finally { context.getContentResolver().call(control, "reset", null, null); }
    }
    @Test public void safNullAndNegativeSizesRemainUnknownAndTreeAliasesShareIdentity() throws Exception {
        Uri plain = DocumentsContract.buildDocumentUri("statistics.fixture", "same-file");
        Uri tree = DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri("statistics.fixture", "root"), "same-file");
        FileStore.Node original = new FileStore.Node("original", null, plain, false, false, 0, 0);
        for (Object size : Arrays.asList(null, -1L)) {
            try (MatrixCursor rows = new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE})) {
                rows.addRow(new Object[]{"payload", "application/octet-stream", size}); assertTrue(rows.moveToFirst());
                var a = FileStatistics.documentEntry(rows, original, plain);
                var b = FileStatistics.documentEntry(rows, original, tree);
                assertEquals(-1, a.bytes()); assertEquals(a.identity(), b.identity());
                Fixture fixture = new Fixture(); fixture.entries.put(original, a);
                var result = new FileStatistics.Scan().run(fixture, List.of(original), null);
                assertEquals(1, result.snapshot().unknownFiles()); assertFalse(result.snapshot().complete());
            }
        }
    }
    @Test public void cyclesFailureIsolationUnknownSizesAndOverflowProducePartialResults() throws Exception {
        Fixture fixture = new Fixture();
        FileStore.Node root = fixture.directory("root"), cycle = fixture.directory("cycle"), failed = fixture.directory("failed");
        fixture.entries.put(cycle, new FileStatistics.Entry(cycle, "root", FileStatistics.Kind.DIRECTORY, 0));
        FileStore.Node unknown = fixture.file("unknown", -1), huge = fixture.file("huge", Long.MAX_VALUE), over = fixture.file("over", 1);
        fixture.children.put(root, List.of(cycle, failed, unknown, huge, over)); fixture.failures.add(failed);
        var result = new FileStatistics.Scan().run(fixture, List.of(root), null);
        assertEquals(Long.MAX_VALUE, result.snapshot().bytes()); assertEquals(3, result.snapshot().files());
        assertEquals(2, result.snapshot().unknownFiles()); assertEquals(1, result.snapshot().duplicateNodes());
        assertEquals(2, result.snapshot().errorCount()); assertFalse(result.snapshot().complete());
    }
    @Test public void cancellationReturnsPartialResultAndClosesAccess() throws Exception {
        FileStatistics.Scan scan = new FileStatistics.Scan(); CountDownLatch inspected = new CountDownLatch(1), release = new CountDownLatch(1);
        Fixture fixture = new Fixture() {
            @Override public FileStatistics.Entry inspect(FileStore.Node node) throws IOException {
                if (node.name.equals("second")) {
                    inspected.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timed out"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                }
                return super.inspect(node);
            }
        };
        var first = fixture.file("first", 31); var second = fixture.file("second", 52);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<FileStatistics.Result> running = executor.submit(() -> scan.run(fixture, List.of(first, second), null));
            assertTrue(inspected.await(5, TimeUnit.SECONDS)); scan.cancel(); release.countDown();
            var result = running.get(5, TimeUnit.SECONDS);
            assertTrue(result.snapshot().cancelled()); assertTrue(result.snapshot().finished()); assertFalse(result.snapshot().complete());
            assertEquals(31, result.snapshot().bytes()); assertEquals(0, result.snapshot().errorCount()); assertTrue(fixture.closed);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test public void cancelledBeforeStartDoesNotInspectAndErrorDetailsAreBounded() throws Exception {
        Fixture fixture = new Fixture(); FileStore.Node node = fixture.file("not-read", 7);
        FileStatistics.Scan cancelled = new FileStatistics.Scan(); cancelled.cancel();
        assertTrue(cancelled.run(fixture, List.of(node), null).snapshot().cancelled()); assertEquals(0, fixture.inspections.get());
        Fixture broken = new Fixture(); List<FileStore.Node> failures = new ArrayList<>();
        for (int i = 0; i < 120; i++) { FileStore.Node directory = broken.directory("broken-" + i); broken.failures.add(directory); failures.add(directory); }
        var result = new FileStatistics.Scan().run(broken, failures, null);
        assertEquals(120, result.snapshot().errorCount()); assertEquals(100, result.errors().size());
    }
    @Test public void deepProviderTreeStopsAtBoundedDepthAndMarksResultIncomplete() throws Exception {
        Fixture fixture = new Fixture(); FileStore.Node root = fixture.directory("level-0"), parent = root;
        for (int i = 1; i <= 300; i++) {
            FileStore.Node child = fixture.directory("level-" + i); fixture.children.put(parent, List.of(child)); parent = child;
        }
        fixture.children.put(parent, List.of(fixture.file("too-deep", 99)));
        var result = new FileStatistics.Scan().run(fixture, List.of(root), null);
        assertEquals(257, result.snapshot().directories()); assertEquals(0, result.snapshot().bytes());
        assertEquals(1, result.snapshot().errorCount()); assertFalse(result.snapshot().complete());
        assertTrue(result.errors().get(0).message().contains("256"));
    }
    @Test public void rootMetadataUsesSameSizeAndDoesNotFollowDirectoryLinks() throws Exception {
        Assume.assumeTrue("Requires real ROOT", RootAccess.available());
        File folder = directory(work, "root-tree"); file(folder, "payload", 79);
        Os.symlink(folder.getAbsolutePath(), new File(folder, "loop").getAbsolutePath());
        var state = new FileStatistics.Scan().run(store, List.of(FileStore.local(folder, true))).snapshot();
        assertEquals(79, state.bytes()); assertEquals(1, state.files()); assertEquals(1, state.directories());
        assertEquals(1, state.skippedLinks()); assertTrue(state.complete());
    }
    @Test public void rootMalformedOrNewlineNamesCannotSilentlyDisappearFromTotal() throws Exception {
        assertEquals(1, FileStatistics.parseRootListing("81a4|3|123|1|2|/fixture/valid\n").size());
        for (String listing : List.of("81a4|3|123|1|2|/fixture/new\nline\n", "unparsed failure\n", "81a4|3|123|1|2|relative\n")) {
            try { FileStatistics.parseRootListing(listing); fail("Incomplete ROOT metadata accepted"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("统计不完整")); }
        }
    }

    private static class Fixture implements FileStatistics.Access {
        final Map<FileStore.Node, FileStatistics.Entry> entries = new IdentityHashMap<>();
        final Map<FileStore.Node, List<FileStore.Node>> children = new IdentityHashMap<>();
        final Set<FileStore.Node> failures = Collections.newSetFromMap(new IdentityHashMap<>());
        final AtomicInteger inspections = new AtomicInteger();
        boolean closed;
        FileStore.Node file(String name, long size) { return add(name, size, FileStatistics.Kind.FILE); }
        FileStore.Node directory(String name) { return add(name, 999999, FileStatistics.Kind.DIRECTORY); }
        private FileStore.Node add(String name, long size, FileStatistics.Kind kind) {
            FileStore.Node node = new FileStore.Node(name, new File("/fixture", name), null, kind == FileStatistics.Kind.DIRECTORY, false, size, 0);
            entries.put(node, new FileStatistics.Entry(node, name, kind, size)); return node;
        }
        @Override public FileStatistics.Entry inspect(FileStore.Node node) throws IOException { inspections.incrementAndGet(); return entries.get(node); }
        @Override public List<FileStore.Node> children(FileStatistics.Entry directory) throws IOException {
            if (failures.contains(directory.node())) throw new IOException("fixture directory denied");
            return children.getOrDefault(directory.node(), List.of());
        }
        @Override public void close() { closed = true; }
    }
}
