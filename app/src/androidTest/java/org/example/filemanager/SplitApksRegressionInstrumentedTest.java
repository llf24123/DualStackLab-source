package org.example.filemanager;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Reproducible resource-free split fixtures; no downloaded APK or device-specific package is needed. */
@RunWith(AndroidJUnit4.class)
public class SplitApksRegressionInstrumentedTest {
    private Context context;
    private File work, base;
    private SplitApks.Manifest identity;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "apks-regression-").toFile();
        base = new File(work, "base.apk");
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("install-fixture.apk")) {
            Files.copy(input, base.toPath());
        }
        identity = SplitApks.manifest(base);
    }

    @After public void cleanup() throws Exception {
        if (work != null) new FileStore(context).delete(FileStore.local(work));
    }

    @Test public void binaryManifestDoesNotRequireResourceTableForAbiOrAssetSplits() throws Exception {
        for (String name : List.of("config.arm64_v8a", "obbassets")) {
            File split = split(name, 0, false);
            try (ZipFile zip = new ZipFile(split)) {
                assertNull(zip.getEntry("resources.arsc"));
            }
            SplitApks.Manifest parsed = SplitApks.manifest(split);
            assertEquals(identity.packageName(), parsed.packageName());
            assertEquals(identity.version(), parsed.version());
            assertEquals(name, parsed.split());
        }
    }

    @Test public void exportedSetKeepsRequiredAssetAndAbiSplitsAndWritesAllPartsToSession() throws Exception {
        String abi = Build.SUPPORTED_ABIS[0].replace('-', '_');
        File abiPart = split("config." + abi, 0, false);
        File assets = split("obbassets", 0, false);
        File language = split("config.zh", 0, false);
        File apks = archive("complete.apks", Map.of("nested/base.apk", base, "nested/abi.apk", abiPart,
                "asset-pack.apk", assets, "language.apk", language));
        File folder;
        try (SplitApks.Prepared prepared = prepare(apks)) {
            folder = prepared.folder;
            assertEquals(identity.packageName(), prepared.packageName);
            assertEquals(4, prepared.parts.size());
            assertEquals("", prepared.parts.get(0).manifest().split());
            assertEquals(Set.of("", "config." + abi, "obbassets", "config.zh"), splits(prepared));
            assertTrue(prepared.excluded.isEmpty());
            assertEquals(base.length() + abiPart.length() + assets.length() + language.length(), prepared.bytes);
            assertFalse("The full APKS container should be released after extraction", new File(folder, "source.apks").exists());
            assertEquals(1, prepared.parts.stream().filter(SplitApks::optional).count());
            int session = SplitApks.writeSession(context, prepared, new AtomicBoolean());
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            try (PackageInstaller.Session opened = installer.openSession(session)) {
                assertEquals(4, opened.getNames().length);
            } finally { installer.abandonSession(session); }
        }
        assertFalse("Closing the preview must remove extracted APKs", folder.exists());
    }

    @Test public void missingBaseDuplicateSplitAndDifferentLongVersionAreRejectedWithoutCacheLeaks() throws Exception {
        File assets = split("obbassets", 0, false);
        rejected(archive("missing-base.apks", Map.of("asset.apk", assets)), "缺少基础");
        rejected(archive("duplicate.apks", Map.of("base.apk", base, "a.apk", assets, "b.apk", assets)), "重复拆分");
        rejected(archive("other-version.apks", Map.of("base.apk", base, "asset.apk", split("obbassets", 1, false))), "不同版本");
    }

    @Test public void manifestRejectsMalformedOrUnresolvedMajorVersion() throws Exception {
        File bad = new File(work, "bad.apk");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(bad))) {
            output.putNextEntry(new ZipEntry("AndroidManifest.xml")); output.write(new byte[]{1, 2, 3}); output.closeEntry();
        }
        assertBadManifest(bad);
        assertBadManifest(split("obbassets", 0, true));
        assertEquals("1:" + identity.version().split(":")[1], SplitApks.manifest(split("major", 1, false)).version());
    }

    @Test public void rootSessionCommandsAndOutputParsingAreDeterministic() throws Exception {
        assertEquals("/system/bin/pm install-create -r --user 10 -S 300",
                SplitApks.installCreateCommand(300, 10));
        assertEquals("/system/bin/pm install-write -S 120 42 part-0.apk -",
                SplitApks.installWriteCommand(42, "part-0.apk", 120));
        assertEquals("/system/bin/pm install-commit 42", SplitApks.installCommitCommand(42));
        assertEquals("/system/bin/pm install-abandon 42", SplitApks.installAbandonCommand(42));
        assertEquals(321, SplitApks.parseInstallSessionId("Success: created install session [321]\n"));
        assertEquals(654, SplitApks.parseInstallSessionId("654\n"));
        try { SplitApks.installWriteCommand(42, "part/0.apk", 120); fail("Unsafe split name was accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("参数")); }
        for (String invalid : List.of("Failure [INSTALL_FAILED_INVALID_APK]\n17", "Failure: Success: created install session [42]",
                "Success: created install session [42] unexpected", "Success: created install session [2147483648]")) {
            try { SplitApks.parseInstallSessionId(invalid); fail("Invalid output was accepted: " + invalid); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("会话")); }
        }
    }

    @Test public void shellRunnerStdinPreservesBinaryApkBytes() throws Exception {
        File copied = new File(work, "stdin-copy.apk");
        ShellRunner.Result result;
        try (InputStream input = new FileInputStream(base)) {
            result = new ShellRunner().run("cat > " + FileStore.quote(copied.getAbsolutePath()),
                    null, false, 30000, null, false, input);
        }
        assertEquals(result.output(), 0, result.exitCode());
        assertArrayEquals(Files.readAllBytes(base.toPath()), Files.readAllBytes(copied.toPath()));
    }

    @Test public void cleanupPreservesActivePreviewsAndCancellationRemovesNewWorkspace() throws Exception {
        File apks = archive("base-only.apks", Map.of("base.apk", base));
        try (SplitApks.Prepared first = prepare(apks); SplitApks.Prepared second = prepare(apks)) {
            File stale = Files.createTempDirectory(context.getCacheDir().toPath(), "split-install-stale-").toFile();
            Files.write(new File(stale, "partial.apk").toPath(), new byte[]{1});
            SplitApks.cleanAbandonedWorkspaces(context.getCacheDir());
            assertFalse(stale.exists());
            assertTrue(first.parts.get(0).file().isFile());
            assertTrue(second.parts.get(0).file().isFile());
            Set<String> before = workspaces();
            try (SplitApks.Prepared ignored = SplitApks.prepare(context, FileStore.local(apks), new AtomicBoolean(true))) {
                fail("Cancelled preparation was accepted");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("取消")); }
            assertEquals(before, workspaces());
        }
    }

    private File split(String name, int major, boolean invalidMajor) throws Exception {
        AndroidManifestBlock manifest = AndroidManifestBlock.empty();
        manifest.setPackageName(identity.packageName());
        manifest.setVersionCode(Integer.parseInt(identity.version().split(":")[1]));
        manifest.setSplit(name, true);
        if (major != 0 || invalidMajor) {
            var attribute = manifest.getManifestElement().getOrCreateAndroidAttribute("versionCodeMajor", android.R.attr.versionCodeMajor);
            if (invalidMajor) attribute.setValueAsString("unresolved");
            else { attribute.setValueType(com.reandroid.arsc.value.ValueType.DEC); attribute.setData(major); }
        }
        manifest.refreshFull();
        File file = new File(work, name + "-" + major + "-" + invalidMajor + ".apk");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            output.putNextEntry(new ZipEntry("AndroidManifest.xml")); output.write(manifest.getBytes()); output.closeEntry();
        }
        return file;
    }

    private File archive(String name, Map<String, File> parts) throws Exception {
        File file = new File(work, name);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            output.putNextEntry(new ZipEntry("manifest.json")); output.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
            for (var part : parts.entrySet()) {
                output.putNextEntry(new ZipEntry(part.getKey())); Files.copy(part.getValue().toPath(), output); output.closeEntry();
            }
        }
        return file;
    }
    private SplitApks.Prepared prepare(File file) throws Exception { return SplitApks.prepare(context, FileStore.local(file), new AtomicBoolean()); }
    private Set<String> splits(SplitApks.Prepared prepared) {
        Set<String> result = new HashSet<>();
        for (SplitApks.Part part : prepared.parts) result.add(part.manifest().split());
        return result;
    }
    private Set<String> workspaces() {
        String[] folders = context.getCacheDir().list((directory, name) -> name.startsWith("split-install-"));
        return folders == null ? Set.of() : new HashSet<>(Arrays.asList(folders));
    }
    private void rejected(File file, String message) throws Exception {
        SplitApks.cleanAbandonedWorkspaces(context.getCacheDir());
        Set<String> before = workspaces();
        try (SplitApks.Prepared ignored = prepare(file)) { fail("Invalid APKS was accepted"); }
        catch (IOException expected) { assertTrue(expected.toString(), expected.getMessage().contains(message)); }
        assertEquals(before, workspaces());
    }
    private void assertBadManifest(File file) throws Exception {
        try { SplitApks.manifest(file); fail("Malformed manifest was accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("清单") || expected.getMessage().contains("无效")); }
    }
}
