package org.example.filemanager;

import android.content.Context;
import android.content.Intent;
import android.view.inputmethod.EditorInfo;
import androidx.test.core.app.ActivityScenario;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import static org.junit.Assert.*;
import static org.example.filemanager.ApksTargetingInstrumentedTest.*;

@RunWith(AndroidJUnit4.class)
public class Iteration50InstrumentedTest {
    private File directory;
    private FileStore store;
    private Context context;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = Files.createTempDirectory(context.getCacheDir().toPath(), "iteration50-").toFile();
        store = new FileStore(context);
    }

    @After public void cleanup() throws Exception { store.delete(FileStore.local(directory)); }

    @Test public void encryptedSevenZCreatesAndReadsEveryMemberWithPassword() throws Exception {
        File first = text(directory, "first.txt", "第一份内容");
        File second = text(directory, "second.txt", "第二份内容");
        File archive = new File(directory, "test.7z");
        char[] password = "test-fragment".toCharArray();
        ArchiveOps.pack(store, List.of(FileStore.local(first), FileStore.local(second)), archive, "7z", password, 6);
        ArchiveOps.clearPassword(archive);
        assertTrue(ArchiveOps.isEncrypted(archive));
        try (var entry = ArchiveOps.openEntry(archive, first.getName())) { entry.read(); fail("Read without password"); }
        catch (ArchiveOps.PasswordRequired expected) { }
        ArchiveOps.setPassword(archive, password);
        try {
            assertArrayEquals(Files.readAllBytes(first.toPath()), bytes(archive, first.getName()));
            assertArrayEquals(Files.readAllBytes(second.toPath()), bytes(archive, second.getName()));
            var parts = ArchiveCreation.splitSevenZ(archive, Math.max(1, archive.length() / 2));
            ArchiveOps.setPassword(parts.get(0), password);
            try { assertArrayEquals(Files.readAllBytes(second.toPath()), bytes(parts.get(0), second.getName())); }
            finally { ArchiveOps.clearPassword(parts.get(0)); }
        } finally { ArchiveOps.clearPassword(archive); }
    }

    @Test public void normalSplitZipCanRenameAddDeleteAndMoveMembersInPlace() throws Exception {
        File first = text(directory, "keep.txt", "保留的文件");
        File second = new File(directory, "large.bin");
        byte[] payload = new byte[300 * 1024]; new Random(50).nextBytes(payload); Files.write(second.toPath(), payload);
        File inputDir = Files.createDirectory(new File(directory, "input").toPath()).toFile();
        File archive = new File(inputDir, "split.zip");
        var parts = ArchiveOps.packSplit(store, List.of(FileStore.local(first), FileStore.local(second)), archive, new char[0], 6, 64 * 1024);
        assertTrue(parts.size() >= 3);
        FileStore.Node root = FileStore.archiveNode(FileStore.local(archive), "", true, 0, 0);
        store.rename(find(root, "keep.txt"), "renamed.txt");
        assertArrayEquals(Files.readAllBytes(first.toPath()), bytes(archive, "renamed.txt"));
        store.create(root, "empty", true);
        assertTrue(find(root, "empty").directory);
        store.delete(find(root, "empty"));
        assertFalse(ArchiveOps.entryNames(archive).contains("empty/"));
        assertArrayEquals(payload, bytes(archive, "large.bin"));
        File out = Files.createDirectory(new File(directory, "target").toPath()).toFile();
        store.moveArchiveBatch(List.of(find(root, "large.bin")), FileStore.local(out), node -> { });
        assertArrayEquals(payload, Files.readAllBytes(new File(out, "large.bin").toPath()));
        assertEquals(List.of("renamed.txt"), ArchiveOps.entryNames(archive));
        for (File part : parts) if (!part.equals(archive)) assertFalse(part.exists());
    }

    @Test public void interruptedSplitZipPublicationRestoresOriginalVolumes() throws Exception {
        File parent = Files.createDirectory(new File(directory, "recovery").toPath()).toFile();
        byte[] before = new byte[180_000], after = new byte[180_000];
        new Random(51).nextBytes(before); new Random(52).nextBytes(after);
        File old = new File(directory, "old.bin"), updated = new File(directory, "updated.bin");
        Files.write(old.toPath(), before); Files.write(updated.toPath(), after);
        File archive = new File(parent, "set.zip");
        List<File> originals = ArchiveOps.packSplit(store, List.of(FileStore.local(old)), archive, new char[0], 6, 64 * 1024);
        Path transaction = Files.createDirectory(new File(parent, ".dualstack-split-interrupted").toPath());
        Path backup = Files.createDirectory(transaction.resolve("original"));
        Path staging = Files.createDirectory(transaction.resolve("staged"));
        File stagedMain = staging.resolve("set.zip").toFile();
        List<File> outputs = ArchiveOps.packSplit(store, List.of(FileStore.local(updated)), stagedMain, new char[0], 6, 64 * 1024);
        SplitZipEdits.writeJournal(transaction, "prepared", archive, originals, stagedMain, outputs);
        for (int i = originals.size() - 1; i >= 0; i--) {
            File part = originals.get(i);
            Files.move(part.toPath(), backup.resolve(part.getName()));
        }
        File published = outputs.stream().filter(part -> !part.equals(stagedMain)).findFirst().orElseThrow();
        Files.move(published.toPath(), parent.toPath().resolve(published.getName()));

        SplitZipEdits.recoverDirectory(parent);
        assertFalse(transaction.toFile().exists());
        assertArrayEquals(before, ArchiveOps.bytes(archive, "old.bin", before.length));
        assertFalse(ArchiveOps.entryNames(archive).contains("updated.bin"));
        SplitZipEdits.recoverDirectory(parent);
        assertArrayEquals(before, ArchiveOps.bytes(archive, "old.bin", before.length));
    }

    @Test public void syncOnlySelectedFilesCanCreateParentsAndExplicitEmptyFolders() throws Exception {
        File left = Files.createDirectory(new File(directory, "left").toPath()).toFile();
        File right = Files.createDirectory(new File(directory, "right").toPath()).toFile();
        File child = Files.createDirectory(new File(left, "nested").toPath()).toFile();
        File wanted = text(child, "wanted.txt", "sync"); text(child, "unselected.txt", "leave");
        File empty = Files.createDirectory(new File(left, "empty").toPath()).toFile();
        var rows = List.of(missing("nested/wanted.txt", wanted), missing("empty", empty));
        var plan = DirectorySync.preview(store, FileStore.local(left), FileStore.local(right), rows, true);
        assertEquals(2, plan.operations().size());
        assertEquals(2, DirectorySync.apply(store, plan, FileStore.ConflictPolicy.OVERWRITE));
        assertEquals("sync", new String(Files.readAllBytes(new File(right, "nested/wanted.txt").toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(right, "nested/unselected.txt").exists());
        assertTrue(new File(right, "empty").isDirectory());
    }

    @Test public void deviceRulesSelectOnlyMatchingGroupsTiersAndRegions() throws Exception {
        byte[] apkSet = cat(apk("base.apk", new byte[0]), apk("fast.apk", msg(11, str(1, "fast"))),
                apk("slow.apk", msg(11, str(1, "slow"))), apk("tier0.apk", msg(9, msg(3, number(1, 0)))),
                apk("tier2.apk", msg(9, msg(3, number(1, 2)))), apk("region.apk", msg(10, str(1, "asia"))),
                apk("default.apk", msg(10, str(2, "asia"))));
        byte[] condition = msg(3, str(1, "TW"));
        byte[] feature = msg(2, cat(msg(1, cat(str(1, "feature"), number(6, 1), msg(5, condition))), apk("feature.apk", new byte[0])));
        byte[] toc = variant(0, new byte[0], cat(module("base", apkSet), feature));
        var profile = new ApksTargeting.Profile(Set.of("fast"), 2, "asia", "TW");
        assertEquals(Set.of("base.apk", "tier2.apk", "fast.apk", "region.apk", "feature.apk"),
                ApksTargeting.select(toc, device(), null, profile).paths());
        var other = new ApksTargeting.Profile(Set.of(), 0, "", "US");
        assertEquals(Set.of("base.apk", "tier0.apk", "default.apk"), ApksTargeting.select(toc, device(), null, other).paths());
    }

    @Test public void syncRejectsAReplacedTargetParentEvenWhenTheNameStillMatches() throws Exception {
        File left = Files.createDirectory(new File(directory, "left").toPath()).toFile();
        File right = Files.createDirectory(new File(directory, "right").toPath()).toFile();
        File source = text(left, "data.txt", "preserved");
        var plan = DirectorySync.preview(store, FileStore.local(left), FileStore.local(right),
                List.of(missing("data.txt", source)), true);
        assertEquals(1, plan.operations().size());
        Files.move(right.toPath(), new File(directory, "old-right").toPath());
        Files.createDirectory(right.toPath());
        try { DirectorySync.apply(store, plan, FileStore.ConflictPolicy.OVERWRITE); fail("Replaced parent accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("父目录")); }
        assertFalse(new File(right, "data.txt").exists());
    }

    @Test public void multiCursorAcceptsComposingTextAndUndoAsOneEdit() throws Exception {
        var source = FileStore.local(text(directory, "editor.txt", "name\nname\nother"));
        Intent intent = new Intent(context, EditorActivity.class);
        source.put(intent);
        var field = EditorActivity.class.getDeclaredField("editor"); field.setAccessible(true);
        try (var scenario = ActivityScenario.<EditorActivity>launch(intent)) {
            MultiCursorEditor[] view = new MultiCursorEditor[1];
            scenario.onActivity(activity -> {
                try { view[0] = (MultiCursorEditor) field.get(activity); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            for (int attempt = 0; attempt < 100; attempt++) {
                boolean[] ready = {false};
                scenario.onActivity(activity -> ready[0] = view[0].isEditable());
                if (ready[0]) break;
                android.os.SystemClock.sleep(50);
            }
            scenario.onActivity(activity -> {
            var editor = view[0];
            assertTrue(editor.isEditable());
            editor.setCursors(List.of(new MultiCursorEditor.Range(0, 4), new MultiCursorEditor.Range(5, 9)), 0);
            assertTrue(editor.hasCursors());
            var connection = editor.onCreateInputConnection(new EditorInfo());
            assertNotNull(connection);
            assertTrue(connection.setComposingText("ni", 1));
            assertEquals("ni\nni\nother", editor.getText().toString());
            assertTrue(connection.commitText("你", 1));
            assertEquals("你\n你\nother", editor.getText().toString());
            editor.commitText("好");
            assertEquals("你好\n你好\nother", editor.getText().toString());
            editor.undo();
            assertEquals("你\n你\nother", editor.getText().toString());
            });
        }
    }

    private FileStore.Node find(FileStore.Node parent, String name) throws IOException {
        return store.list(parent).stream().filter(n -> n.name.equals(name)).findFirst().orElseThrow();
    }

    private static CompareReport.Row missing(String path, File file) {
        return new CompareReport.Row(path, "仅当前窗口", DirectoryCompare.metadata(FileStore.local(file)), "无", "");
    }

    private static File text(File parent, String name, String value) throws IOException {
        File file = new File(parent, name); Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); return file;
    }

    private static byte[] bytes(File archive, String name) throws IOException {
        try (var input = ArchiveOps.openEntry(archive, name)) { return FileStore.readBytes(input, 512 * 1024); }
    }
}
