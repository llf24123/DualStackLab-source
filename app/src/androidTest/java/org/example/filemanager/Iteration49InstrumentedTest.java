package org.example.filemanager;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class Iteration49InstrumentedTest {
    private File directory;
    private FileStore store;

    @Before public void prepare() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = Files.createTempDirectory(context.getCacheDir().toPath(), "iteration49-").toFile();
        store = new FileStore(context);
    }

    @After public void clean() throws Exception {
        try (var paths = Files.walk(directory.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test public void findsTextBeyondTwoMiBWithCorrectCrLfLineNumber() throws Exception {
        byte[] output = ("日志 " + "a".repeat(1024) + "\r\n").repeat(2300).concat("最后 needle-19").getBytes(StandardCharsets.UTF_8);
        assertTrue(output.length > 2 * 1024 * 1024);
        SearchJob.Hit hit = SearchJob.findStream(new ByteArrayInputStream(output), Pattern.compile("needle-\\d+"));
        assertNotNull(hit);
        assertEquals(2301, hit.line());
        assertEquals("needle-19", hit.text().substring(hit.start(), hit.end()));
    }

    @Test public void searchesWithLookaroundAndKeepsSmallMultilineExpressions() throws Exception {
        assertEquals(new SearchJob.Match(4, 8), new SearchJob.SearchPattern(Pattern.compile("(?<=key=)name")).find("key=name"));
        assertNotNull(SearchJob.findStream(new ByteArrayInputStream("one\ntwo".getBytes(StandardCharsets.UTF_8)),
                Pattern.compile("one\\ntwo")));
    }

    @Test public void streamComparisonAcceptsFilesLargerThanPasteLimit() throws Exception {
        String hash = "a".repeat(64);
        String input = (hash + "  other\n").repeat(15000) + hash + "  target\n";
        assertTrue(input.length() > 1000000);
        var comparison = ChecksumManifest.compareReader(new StringReader(input), Map.of("target", hash), 8 * 1024 * 1024);
        assertTrue(comparison.summary().startsWith("匹配 1，不匹配 0"));
        try {
            ChecksumManifest.compareReader(new StringReader(input), Map.of("target", hash), 1024);
            fail("overlong input must not look like a complete result");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("最大字符数")); }
    }

    @Test public void selectedSyncOverwritesOnlyReviewedTargetAndRejectsChangedFile() throws Exception {
        File left = Files.createDirectory(new File(directory, "left").toPath()).toFile();
        File right = Files.createDirectory(new File(directory, "right").toPath()).toFile();
        File source = write(left, "a.txt", "left content"), target = write(right, "a.txt", "right");
        FileStore.Node a = FileStore.local(source), b = FileStore.local(target);
        CompareReport.Row row = new CompareReport.Row("a.txt", "大小不同", DirectoryCompare.metadata(a), DirectoryCompare.metadata(b), "");
        var review = DirectorySync.preview(store, FileStore.local(left), FileStore.local(right), List.of(row), true);
        assertEquals(1, review.operations().size());
        assertEquals(0, DirectorySync.apply(store, review, FileStore.ConflictPolicy.SKIP));
        assertEquals(1, DirectorySync.apply(store, review, FileStore.ConflictPolicy.OVERWRITE));
        assertEquals("left content", read(target));
        try { DirectorySync.apply(store, review, FileStore.ConflictPolicy.OVERWRITE); fail("stale review"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("目标已变化")); }
        assertEquals("left content", read(source));
    }

    @Test public void joinSplitSevenZVolumesAndReadMemberThroughFileStore() throws Exception {
        File source = write(directory, "input.txt", "alpha\nbeta\n".repeat(500));
        File archive = new File(directory, "split.7z");
        ArchiveOps.pack(store, List.of(FileStore.local(source)), archive, "7z", new char[0], 0);
        List<File> pieces = ArchiveCreation.splitSevenZ(archive, 512);
        assertTrue(pieces.size() > 1);
        FileStore.Node root = FileStore.archiveNode(FileStore.local(pieces.get(0)), "", true, 0, 0);
        FileStore.Node entry = store.list(root).get(0);
        assertEquals("input.txt", entry.name);
        try (InputStream stream = store.read(entry)) {
            assertEquals(read(source), new String(FileStore.readBytes(stream, 1024 * 1024), StandardCharsets.UTF_8));
        }
    }

    @Test public void independentEditsMergeAndConflictRequiresExplicitChoice() throws Exception {
        String base = "first\nmiddle\nlast\n";
        assertEquals("FIRST\nmiddle\nLAST\n", ThreeWayMerge.plan(base, "FIRST\nmiddle\nlast\n",
                "first\nmiddle\nLAST\n", () -> false).render(List.of()));
        var conflict = ThreeWayMerge.plan(base, "first\nLEFT\nlast\n", "first\nRIGHT\nlast\n", () -> false);
        assertEquals(1, conflict.conflicts().size());
        assertEquals("first\nRIGHT\nlast\n", conflict.render(List.of(1)));
        try { conflict.render(List.of()); fail("unresolved conflict"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("冲突")); }
    }

    @Test public void textureSelectionUsesBestAvailableFormat() throws Exception {
        byte[] tcf = new byte[]{10, 2, 8, 10, 18, 2, 8, 9};
        assertTrue(ApksTargeting.texture(new ApksTargeting.Message(tcf), Set.of(10)));
        assertFalse(ApksTargeting.texture(new ApksTargeting.Message(tcf), Set.of(9, 10)));
    }
    @Test public void movesSmallMemberOutOfFortyMiBZipAndKeepsOtherContent() throws Exception {
        File archive = new File(directory, "source.zip");
        byte[] large = new byte[40 * 1024 * 1024];
        byte[] small = "small file\n".repeat(2300).getBytes(StandardCharsets.UTF_8);
        try (var out = new java.util.zip.ZipOutputStream(new FileOutputStream(archive))) {
            out.setLevel(0);
            out.putNextEntry(new java.util.zip.ZipEntry("large.bin"));
            out.write(large);
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("small.txt"));
            out.write(small);
            out.closeEntry();
        }
        File target = Files.createDirectory(new File(directory, "destination").toPath()).toFile();
        FileStore.Node source = FileStore.local(archive);
        FileStore.Node member = FileStore.archiveNode(source, "small.txt", false, small.length, 0);
        long start = android.os.SystemClock.elapsedRealtime();
        store.moveArchiveBatch(List.of(member), FileStore.local(target), output -> { });
        android.util.Log.i("DualStackBenchmark", "40 MiB ZIP, " + small.length + " member bytes, local private target: "
                + (android.os.SystemClock.elapsedRealtime() - start) + " ms");
        assertArrayEquals(small, Files.readAllBytes(new File(target, "small.txt").toPath()));
        try (var zip = new java.util.zip.ZipFile(archive)) {
            assertEquals(large.length, zip.getEntry("large.bin").getSize());
            assertNull(zip.getEntry("small.txt"));
        }
    }
    @Test public void sftpFixtureListsDownloadsUploadsAndRefusesOverwrite() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        String port = args.getString("sftpPort"), fingerprint = args.getString("sftpFingerprint");
        Assume.assumeTrue("requires isolated SFTP fixture", port != null && fingerprint != null);
        String address = "sftp://127.0.0.1:" + port + "/";
        byte[] content = "synthetic SFTP round trip".getBytes(StandardCharsets.UTF_8);
        try (RemoteStorage remote = RemoteStorage.connect(address, "fixture", "fixture-password", fingerprint)) {
            assertTrue(remote.list(List.of()).stream().anyMatch(entry -> entry.name().equals("fixture.txt")));
            ByteArrayOutputStream original = new ByteArrayOutputStream();
            remote.download(List.of("fixture.txt"), original);
            assertEquals("fixture data", original.toString("UTF-8"));
            String name = "round-trip-" + UUID.randomUUID() + ".bin";
            remote.upload(List.of(name), new ByteArrayInputStream(content));
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            remote.download(List.of(name), received);
            assertArrayEquals(content, received.toByteArray());
            try { remote.upload(List.of(name), new ByteArrayInputStream(new byte[0])); fail("overwrite accepted"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("同名")); }
        }
        try (RemoteStorage ignored = RemoteStorage.connect(address, "fixture", "fixture-password", "SHA256:" + "A".repeat(43))) {
            fail("untrusted host was accepted");
        } catch (com.jcraft.jsch.JSchException expected) { assertNotNull(expected.getMessage()); }
    }
    @Test public void webDavTlsListsOnlyDirectChildrenAndTransfersFiles() throws Exception {
        var certificate = new okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();
        var serverTls = new okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build();
        var clientTls = new okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        var client = new okhttp3.OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager());
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            server.useHttps(serverTls.sslSocketFactory(), false);
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody(
                    "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/share/</d:href><d:collection/></d:response>"
                       + "<d:response><d:href>/share/sub/</d:href><d:collection/></d:response>"
                       + "<d:response><d:href>/share/a%20b.txt</d:href><d:getcontentlength>7</d:getcontentlength></d:response>"
                       + "<d:response><d:href>/outside.txt</d:href></d:response></d:multistatus>"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("content"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(201));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(412));
            try (RemoteStorage remote = new RemoteStorage.Dav(server.url("/share/").uri(), "fixture", "file-password", client)) {
                assertEquals(List.of(new RemoteStorage.Entry("sub", true, -1), new RemoteStorage.Entry("a b.txt", false, 7)), remote.list(List.of()));
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                remote.download(List.of("a b.txt"), data);
                assertEquals("content", data.toString("UTF-8"));
                remote.upload(List.of("new name.txt"), new ByteArrayInputStream(data.toByteArray()));
                try { remote.upload(List.of("new name.txt"), new ByteArrayInputStream(new byte[0])); fail("overwrite accepted"); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("已存在")); }
            }
            assertEquals("PROPFIND", server.takeRequest().getMethod());
            assertEquals("/share/a%20b.txt", server.takeRequest().getPath());
            var upload = server.takeRequest();
            assertEquals("PUT", upload.getMethod());
            assertEquals("/share/new%20name.txt", upload.getPath());
            assertEquals("*", upload.getHeader("If-None-Match"));
            assertEquals("content", upload.getBody().readUtf8());
        }
    }
    @Test public void smbFixtureListsDownloadsUploadsAndRefusesOverwrite() throws Exception {
        String port = InstrumentationRegistry.getArguments().getString("smbPort");
        Assume.assumeTrue("requires isolated SMB2 fixture", port != null);
        String name = "round-trip-" + UUID.randomUUID() + ".bin";
        try (RemoteStorage remote = RemoteStorage.connect("smb://127.0.0.1:" + port + "/fixture/", "fixture", "fixture-password", "")) {
            assertTrue(remote.list(List.of()).stream().anyMatch(entry -> entry.name().equals("fixture.txt")));
            ByteArrayOutputStream original = new ByteArrayOutputStream();
            remote.download(List.of("fixture.txt"), original);
            assertEquals("fixture data", original.toString("UTF-8"));
            remote.upload(List.of(name), new ByteArrayInputStream(original.toByteArray()));
            ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
            remote.download(List.of(name), uploaded);
            assertArrayEquals(original.toByteArray(), uploaded.toByteArray());
            try { remote.upload(List.of(name), new ByteArrayInputStream(new byte[0])); fail("overwrite accepted"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("同名")); }
        }
    }
    @Test public void rarSingleVolumePasswordAndUnencryptedSplitMemberAreReadable() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("rarFixturePath");
        Assume.assumeTrue("requires generated RAR fixture", path != null);
        File encrypted = new File(path, "encrypted.rar");
        assertTrue(ArchiveOps.isEncrypted(encrypted));
        try { ArchiveOps.list(encrypted, ""); fail("archive opened without password"); }
        catch (ArchiveOps.PasswordRequired expected) { }
        ArchiveOps.setPassword(encrypted, "fixture-password".toCharArray());
        try { assertFixtureContent(encrypted); }
        finally { ArchiveOps.clearPassword(encrypted); }
        assertFixtureContent(new File(path, "plain-flat.part1.rar"));
    }

    @Test public void encryptedSplitRarNeverReturnsUnverifiedContent() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("rarFixturePath");
        Assume.assumeTrue("requires generated RAR fixture", path != null);
        File archive = new File(path, "volume.part1.rar");
        ArchiveOps.setPassword(archive, "fixture-password".toCharArray());
        try { assertFixtureContent(archive); }
        catch (IOException checksumFailure) {
            assertTrue(checksumFailure.getMessage(), checksumFailure.getMessage().contains("加密 RAR 跨卷条目校验失败"));
        } finally { ArchiveOps.clearPassword(archive); }
    }

    @Test public void sevenZDetectsEncryptionWithPlainOrEncryptedHeaders() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("rarFixturePath");
        Assume.assumeTrue("requires generated 7Z fixture", path != null);
        for (String name : List.of("sealed.7z", "data-only.7z")) {
            File archive = new File(path, name);
            assertTrue("password probe: " + name, ArchiveOps.isEncrypted(archive));
            try (InputStream input = ArchiveOps.openEntry(archive, "fixture.txt")) {
                input.read();
                fail("archive opened without password");
            }
            catch (ArchiveOps.PasswordRequired expected) { }
            ArchiveOps.setPassword(archive, "fixture-password".toCharArray());
            try { assertFixtureContent(archive); }
            finally { ArchiveOps.clearPassword(archive); }
        }
    }

    private void assertFixtureContent(File archive) throws IOException {
        assertEquals("fixture.txt", ArchiveOps.list(archive, "").get(0).name());
        try (InputStream file = ArchiveOps.openEntry(archive, "fixture.txt")) {
            assertEquals("synthetic content".repeat(3300), new String(FileStore.readBytes(file, 128 * 1024), StandardCharsets.UTF_8));
        }
    }

    private static File write(File directory, String name, String value) throws IOException {
        File file = new File(directory, name);
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
