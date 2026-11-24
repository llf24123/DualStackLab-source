package org.example.filemanager;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SplitZipRecoveryInstrumentedTest {
    private File work;
    private FileStore store;
    private record Fixture(File parent, File archive, Path transaction, Path backup,
                           List<File> originals, List<File> outputs, byte[] before, byte[] after) { }

    @Before public void setup() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(app.getCacheDir().toPath(), "split-recovery-").toFile();
        store = new FileStore(app);
    }
    @After public void cleanup() throws Exception { store.delete(FileStore.local(work)); }

    @Test public void partiallyWithdrawnSetRecoversWhenOpenedByKey() throws Exception {
        Fixture f = prepare("prepared");
        Files.move(f.archive.toPath(), f.backup.resolve(f.archive.getName()));
        assertTrue(store.fromKey(f.archive.getAbsolutePath()).file.isFile());
        assertOriginal(f);
    }
    @Test public void fullyPublishedButUncommittedSetRollsBackIncludingExtraVolumes() throws Exception {
        Fixture f = prepare("prepared"); withdraw(f); publish(f);
        SplitZipEdits.recoverDirectory(f.parent); assertOriginal(f);
        long volumeCount;
        try (var files = Files.list(f.parent.toPath())) { volumeCount = files.count(); }
        assertEquals(f.originals.size(), volumeCount);
    }
    @Test public void recoveryCanContinueAfterSomeOriginalsWereAlreadyRestored() throws Exception {
        Fixture f = prepare("recovering"); withdraw(f);
        File first = f.originals.get(0);
        Files.move(f.backup.resolve(first.getName()), first.toPath());
        SplitZipEdits.recoverDirectory(f.parent); assertOriginal(f);
        SplitZipEdits.recoverDirectory(f.parent); assertOriginal(f);
    }
    @Test public void committedSetKeepsNewVolumesAndRemovesBackup() throws Exception {
        Fixture f = prepare("committed"); withdraw(f); publish(f);
        SplitZipEdits.recoverDirectory(f.parent);
        assertFalse(Files.exists(f.transaction));
        assertArrayEquals(f.after, ArchiveOps.bytes(f.archive, "after.bin", f.after.length));
        assertFalse(ArchiveOps.entryNames(f.archive).contains("before.bin"));
    }
    @Test public void sameContentExternalReplacementIsPreservedWithOriginalBackup() throws Exception {
        Fixture f = prepare("prepared"); withdraw(f); publish(f);
        Path target = f.parent.toPath().resolve(f.outputs.get(0).getName());
        byte[] bytes = Files.readAllBytes(target);
        Path foreign = Files.write(new File(work, "external.bin").toPath(), bytes);
        Files.move(foreign, target, StandardCopyOption.REPLACE_EXISTING);
        expectRecoveryFailure(f);
        assertArrayEquals(bytes, Files.readAllBytes(target));
        for (File original : f.originals) assertTrue(Files.exists(f.backup.resolve(original.getName())));
        for (File output : f.outputs) assertTrue(new File(f.parent, output.getName()).isFile());
    }
    @Test public void corruptedBackupDoesNotDeletePublishedFiles() throws Exception {
        Fixture f = prepare("prepared"); withdraw(f); publish(f);
        Files.write(f.backup.resolve(f.originals.get(0).getName()), new byte[]{1, 2, 3});
        expectRecoveryFailure(f);
        for (File output : f.outputs) assertTrue(new File(f.parent, output.getName()).isFile());
        assertArrayEquals(f.after, ArchiveOps.bytes(f.archive, "after.bin", f.after.length));
    }
    @Test public void changedCommittedVolumeKeepsBackupAndDirectoryCanStillBeListed() throws Exception {
        Fixture f = prepare("committed"); withdraw(f); publish(f);
        Files.write(f.archive.toPath(), new byte[]{0, 1});
        expectRecoveryFailure(f);
        assertTrue(store.list(FileStore.local(f.parent)).stream().anyMatch(n -> n.name.equals(f.archive.getName())));
        for (File original : f.originals) assertTrue(Files.exists(f.backup.resolve(original.getName())));
    }
    @Test public void unexpectedFileInTransactionSurvivesCleanup() throws Exception {
        Fixture f = prepare("committed"); withdraw(f); publish(f);
        Path extra = Files.write(f.backup.resolve("my-notes.txt"), new byte[]{42});
        expectRecoveryFailure(f);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(extra));
        assertArrayEquals(f.after, ArchiveOps.bytes(f.archive, "after.bin", f.after.length));
    }
    @Test public void unjournaledLookalikeDirectoryIsNeverDeleted() throws Exception {
        Path folder = Files.createDirectory(new File(work, ".dualstack-split-user").toPath());
        Path note = Files.write(folder.resolve("note.txt"), new byte[]{4, 5, 6});
        SplitZipEdits.recoverDirectory(work);
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(note));
    }
    @Test public void damagedJournalKeepsOriginalsAndOutputs() throws Exception {
        Fixture f = prepare("prepared"); withdraw(f); publish(f);
        Path journal = f.transaction.resolve("journal");
        List<String> lines = Files.readAllLines(journal); lines.add(lines.get(2)); Files.write(journal, lines);
        expectRecoveryFailure(f);
        for (File original : f.originals) assertTrue(Files.exists(f.backup.resolve(original.getName())));
        for (File output : f.outputs) assertTrue(new File(f.parent, output.getName()).isFile());
    }
    @Test public void splitZipEditAlsoWorksOnEmulatedExternalStorage() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File external = Files.createTempDirectory(app.getExternalFilesDir(null).toPath(), "split-external-").toFile();
        try {
            byte[] payload = new byte[180_000]; new Random(503).nextBytes(payload);
            File input = new File(work, "input.bin"); Files.write(input.toPath(), payload);
            File archive = new File(external, "set.zip");
            ArchiveOps.packSplit(store, List.of(FileStore.local(input)), archive, new char[0], 6, 65536);
            var root = FileStore.archiveNode(FileStore.local(archive), "", true, 0, 0);
            store.rename(store.list(root).get(0), "renamed.bin");
            assertArrayEquals(payload, ArchiveOps.bytes(archive, "renamed.bin", payload.length));
            assertFalse(ArchiveOps.entryNames(archive).contains("input.bin"));
        } finally { store.delete(FileStore.local(external)); }
    }
    private Fixture prepare(String phase) throws Exception {
        File parent = Files.createDirectory(new File(work, "volumes").toPath()).toFile();
        byte[] before = new byte[90_000], after = new byte[180_000];
        new Random(501).nextBytes(before); new Random(502).nextBytes(after);
        File old = new File(work, "before.bin"), next = new File(work, "after.bin");
        Files.write(old.toPath(), before); Files.write(next.toPath(), after);
        File archive = new File(parent, "set.zip");
        List<File> originals = ArchiveOps.packSplit(store, List.of(FileStore.local(old)), archive, new char[0], 6, 65536);
        Path transaction = Files.createDirectory(new File(parent, ".dualstack-split-test").toPath());
        Path backup = Files.createDirectory(transaction.resolve("original"));
        Path staging = Files.createDirectory(transaction.resolve("staged"));
        File main = staging.resolve(archive.getName()).toFile();
        List<File> outputs = ArchiveOps.packSplit(store, List.of(FileStore.local(next)), main, new char[0], 6, 65536);
        SplitZipEdits.writeJournal(transaction, phase, archive, originals, main, outputs);
        return new Fixture(parent, archive, transaction, backup, originals, outputs, before, after);
    }
    private void withdraw(Fixture f) throws Exception {
        for (int i = f.originals.size() - 1; i >= 0; i--) {
            File file = f.originals.get(i); Files.move(file.toPath(), f.backup.resolve(file.getName()));
        }
    }
    private void publish(Fixture f) throws Exception {
        for (File file : f.outputs) Files.move(file.toPath(), f.parent.toPath().resolve(file.getName()));
    }
    private void assertOriginal(Fixture f) throws Exception {
        assertFalse(Files.exists(f.transaction));
        assertArrayEquals(f.before, ArchiveOps.bytes(f.archive, "before.bin", f.before.length));
        assertFalse(ArchiveOps.entryNames(f.archive).contains("after.bin"));
    }
    private void expectRecoveryFailure(Fixture f) throws Exception {
        try { SplitZipEdits.recoverDirectory(f.parent); fail("Damaged transaction was accepted"); }
        catch (IOException expected) { assertTrue(Files.exists(f.transaction)); }
    }
}
