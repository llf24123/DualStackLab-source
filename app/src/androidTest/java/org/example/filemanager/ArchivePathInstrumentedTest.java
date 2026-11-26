package org.example.filemanager;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchivePathInstrumentedTest {
    private File work;
    private FileStore store;
    @Before public void setup() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(app.getCacheDir().toPath(), "archive-path-test-").toFile();
        store = new FileStore(app);
    }
    @After public void cleanup() throws Exception {
        if (work != null && work.exists()) store.delete(FileStore.local(work));
    }
    private File zip(String name, String... entries) throws Exception {
        File file = new File(work, name);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            for (String entry : entries) {
                output.putNextEntry(new ZipEntry(entry));
                if (!entry.endsWith("/")) output.write(entry.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return file;
    }
    @Test public void dotAndLegacySeparatorMembersBrowseReadAndExtractConsistently() throws Exception {
        File archive = zip("paths.zip", "./", "./notes.txt", "./nested/./first.txt", "nested\\second.txt");
        assertEquals(List.of("nested/", "notes.txt"), ArchiveOps.list(archive, "").stream().map(ArchiveOps.Item::path).toList());
        assertEquals(List.of("nested/first.txt", "nested/second.txt"), ArchiveOps.list(archive, "./nested/./").stream().map(ArchiveOps.Item::path).toList());
        assertArrayEquals("./notes.txt".getBytes(StandardCharsets.UTF_8), ArchiveOps.bytes(archive, "notes.txt", 1024));
        assertArrayEquals("./nested/./first.txt".getBytes(StandardCharsets.UTF_8), ArchiveOps.bytes(archive, "./nested/first.txt", 1024));
        assertArrayEquals("nested\\second.txt".getBytes(StandardCharsets.UTF_8), ArchiveOps.bytes(archive, "nested/second.txt", 1024));
        assertEquals(11, ArchiveOps.entrySize(archive, "notes.txt"));
        assertFalse(ArchiveOps.entryNames(archive).contains(""));
        File output = new File(work, "extracted");
        ArchiveOps.extract(archive, "", output);
        assertArrayEquals(ArchiveOps.bytes(archive, "notes.txt", 1024), Files.readAllBytes(new File(output, "notes.txt").toPath()));
        assertTrue(new File(output, "nested/first.txt").isFile());
        assertTrue(new File(output, "nested/second.txt").isFile());
    }
    @Test public void replacementRenameAndDeleteUseTheSameCanonicalMember() throws Exception {
        File archive = zip("changes.zip", "./", "./notes.txt", "./nested/./first.txt");
        File replacement = new File(work, "replacement.txt");
        Files.write(replacement.toPath(), "replacement".getBytes(StandardCharsets.UTF_8));
        ArchiveOps.rewrite(archive, null, null, Map.of("./notes.txt", replacement));
        assertArrayEquals(Files.readAllBytes(replacement.toPath()), ArchiveOps.bytes(archive, "notes.txt", 1024));
        assertEquals(Set.of("notes.txt", "nested/first.txt"), new HashSet<>(ArchiveOps.entryNames(archive)));
        ArchiveOps.renameAll(archive, Map.of("./nested/", "./renamed/"));
        assertTrue(ArchiveOps.containsEntry(archive, "renamed/first.txt"));
        ArchiveOps.removeAll(archive, Set.of("./renamed/"));
        assertEquals(List.of("notes.txt"), ArchiveOps.entryNames(archive));
        ArchiveOps.rewrite(archive, "./notes.txt", "./renamed.txt", Map.of());
        assertEquals(List.of("renamed.txt"), ArchiveOps.entryNames(archive));
        byte[] beforeRejected = Files.readAllBytes(archive.toPath());
        for (String invalid : List.of("./", "nested/../renamed.txt", "/renamed.txt", "./C:/renamed.txt")) {
            assertRejected(() -> ArchiveOps.removeAll(archive, Set.of(invalid)));
            assertArrayEquals(beforeRejected, Files.readAllBytes(archive.toPath()));
        }
    }
    @Test public void encryptedZipUsesTheSameDotPathMapping() throws Exception {
        File archive = new File(work, "encrypted.zip");
        char[] password = "fixture-password".toCharArray();
        try (var output = new net.lingala.zip4j.io.outputstream.ZipOutputStream(Files.newOutputStream(archive.toPath()), password)) {
            var entry = new net.lingala.zip4j.model.ZipParameters();
            entry.setFileNameInZip("./secret.txt");
            entry.setEncryptFiles(true);
            entry.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
            output.putNextEntry(entry); output.write(new byte[]{3, 4, 5}); output.closeEntry();
        }
        ArchiveOps.setPassword(archive, password);
        try {
            assertEquals(List.of("secret.txt"), ArchiveOps.entryNames(archive));
            assertEquals("secret.txt", ArchiveOps.list(archive, "").get(0).path());
            assertArrayEquals(new byte[]{3, 4, 5}, ArchiveOps.bytes(archive, "secret.txt", 1024));
            File extracted = new File(work, "encrypted-output");
            ArchiveOps.extract(archive, "./secret.txt", extracted);
            assertArrayEquals(new byte[]{3, 4, 5}, Files.readAllBytes(new File(extracted, "secret.txt").toPath()));
        } finally { ArchiveOps.clearPassword(archive); }
    }
    @Test public void normalizedDuplicatesFailBeforeReadingOrPublishingChanges() throws Exception {
        File archive = zip("duplicate.zip", "notes.txt", "./notes.txt");
        byte[] before = Files.readAllBytes(archive.toPath());
        assertRejected(() -> ArchiveOps.list(archive, ""));
        assertRejected(() -> ArchiveOps.bytes(archive, "notes.txt", 1024));
        assertRejected(() -> ArchiveOps.entryNames(archive));
        assertRejected(() -> ArchiveOps.rewrite(archive, null, null, Map.of()));
        assertArrayEquals(before, Files.readAllBytes(archive.toPath()));
    }
    @Test public void parentAbsoluteAndEmptySegmentsStillFailWithoutModifyingArchive() throws Exception {
        int count = 0;
        for (String path : List.of("../outside.txt", "nested/../outside.txt", "/absolute.txt", "./C:/absolute.txt", "nested//empty.txt")) {
            File archive = zip("invalid-" + count++ + ".zip", path);
            byte[] before = Files.readAllBytes(archive.toPath());
            assertRejected(() -> ArchiveOps.list(archive, ""));
            assertRejected(() -> ArchiveOps.bytes(archive, "anything.txt", 1024));
            assertRejected(() -> ArchiveOps.rewrite(archive, null, null, Map.of()));
            assertArrayEquals(before, Files.readAllBytes(archive.toPath()));
        }
    }
    private interface Attempt { void run() throws Exception; }
    private void assertRejected(Attempt attempt) throws Exception {
        try { attempt.run(); fail("Invalid or ambiguous archive path accepted"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }
}
