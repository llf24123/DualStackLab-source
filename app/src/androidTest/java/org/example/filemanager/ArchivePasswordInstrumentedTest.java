package org.example.filemanager;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import net.lingala.zip4j.ZipFile;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchivePasswordInstrumentedTest {
    private Context context;
    private File work;
    private FileStore store;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "password-zip-").toFile();
        store = new FileStore(context);
    }
    @After public void cleanup() throws Exception { store.delete(FileStore.local(work)); }

    @Test public void aes256PasswordZipIsCreatedAndRequiresThePassword() throws Exception {
        File source = new File(work, "secret.txt");
        Files.write(source.toPath(), "payload".getBytes(StandardCharsets.UTF_8));
        File archive = new File(work, "secret.zip");
        char[] password = "s3cret-密码".toCharArray();
        ArchiveOps.pack(store, List.of(FileStore.local(source)), archive, "zip", password);
        ArchiveOps.clearPassword(archive);
        try { new java.util.zip.ZipFile(archive); fail("JDK ZipFile must reject an AES ZIP"); }
        catch (ZipException expected) {
            assertTrue(String.valueOf(expected.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("encrypted"));
        }
        assertEquals("secret.txt", ArchiveOps.list(archive, "").get(0).name());
        try { ArchiveOps.openEntry(archive, "secret.txt"); fail("Encrypted entry opened without a password"); }
        catch (ArchiveOps.PasswordRequired expected) { assertEquals(archive.getCanonicalFile(), expected.file.getCanonicalFile()); }
        ZipFile encrypted = new ZipFile(archive, password);
        assertTrue(encrypted.isEncrypted());
        try (InputStream input = encrypted.getInputStream(encrypted.getFileHeader("secret.txt"))) {
            assertEquals("payload", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        ArchiveOps.setPassword(archive, password);
        try (InputStream input = ArchiveOps.openEntry(archive, "secret.txt")) {
            assertEquals("payload", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        File extracted = new File(work, "encrypted-extract");
        ArchiveOps.extract(archive, "", extracted);
        assertEquals("payload", new String(Files.readAllBytes(new File(extracted, "secret.txt").toPath()), StandardCharsets.UTF_8));
        Files.deleteIfExists(source.toPath());
        File plainArchive = new File(work, "plain.zip");
        Files.write(source.toPath(), "plain".getBytes(StandardCharsets.UTF_8));
        ArchiveOps.pack(store, List.of(FileStore.local(source)), plainArchive, "zip", new char[0]);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(plainArchive)) {
            try (InputStream input = zip.getInputStream(zip.getEntry("secret.txt"))) {
                assertEquals("plain", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
    @Test public void copyReaderFallsBackForEncryptedZipAndRollsBackWrongPassword() throws Exception {
        File source = new File(work, "payload.bin"), archive = new File(work, "copy.zip"), output = new File(work, "output");
        byte[] payload = new byte[32768]; new java.util.Random(21).nextBytes(payload);
        Files.write(source.toPath(), payload); assertTrue(output.mkdir());
        ArchiveOps.pack(store, List.of(FileStore.local(source)), archive, "zip", "correct".toCharArray());
        FileStore.Node member = FileStore.archiveNode(FileStore.local(archive), "payload.bin", false, 0, 0);
        ArchiveOps.clearPassword(archive);
        try { store.copy(member, FileStore.local(output), false); fail("Must request a password"); }
        catch (ArchiveOps.PasswordRequired expected) { assertEquals(0, output.list().length); }
        ArchiveOps.setPassword(archive, "wrong".toCharArray());
        try { store.copy(member, FileStore.local(output), false); fail("Must reject wrong password"); }
        catch (IOException expected) { assertEquals(0, output.list().length); }
        ArchiveOps.setPassword(archive, "correct".toCharArray());
        try (ArchiveOps.ReadSession reader = new ArchiveOps.ReadSession(archive); InputStream input = reader.open("payload.bin")) {
            assertEquals(payload.length, reader.size("payload.bin")); assertArrayEquals(payload, input.readAllBytes());
        }
        FileStore.Node copied = store.copy(member, FileStore.local(output), false);
        assertArrayEquals(payload, Files.readAllBytes(copied.file.toPath()));
        ArchiveOps.clearPassword(archive);
    }
    @Test public void compressionLevelsRoundTripPlainAndEncryptedPayloads() throws Exception {
        File source=new File(work,"levels.txt");byte[] payload="compressible test payload\n".repeat(4000).getBytes(StandardCharsets.UTF_8);Files.write(source.toPath(),payload);
        for(int level:List.of(0,1,6,9))for(boolean encrypted:List.of(false,true)){
            File archive=new File(work,"level-"+level+"-"+encrypted+".zip");char[] password=encrypted?"test-password".toCharArray():new char[0];
            ArchiveOps.pack(store,List.of(FileStore.local(source)),archive,"zip",password,level);
            try(ZipFile zip=new ZipFile(archive,password)){
                var header=zip.getFileHeader("levels.txt");assertEquals(encrypted,header.isEncrypted());
                try(InputStream input=zip.getInputStream(header)){assertArrayEquals(payload,input.readAllBytes());}
                if(!encrypted&&level==0)assertEquals(net.lingala.zip4j.model.enums.CompressionMethod.STORE,header.getCompressionMethod());
                if(level>0)assertTrue(archive.length()<payload.length/2);
            }
        }
    }
}
