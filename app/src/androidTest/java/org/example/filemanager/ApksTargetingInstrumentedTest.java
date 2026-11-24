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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ApksTargetingInstrumentedTest {
    static byte[] cat(byte[]... items) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] item : items) out.write(item); return out.toByteArray(); }
    static void varint(ByteArrayOutputStream out, int value) { while ((value & ~127) != 0) { out.write((value & 127) | 128); value >>>= 7; } out.write(value); }
    static byte[] number(int field, int value) { ByteArrayOutputStream out = new ByteArrayOutputStream(); varint(out, field << 3); varint(out, value); return out.toByteArray(); }
    static byte[] msg(int field, byte[] value) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); varint(out, field << 3 | 2); varint(out, value.length); out.write(value); return out.toByteArray(); }
    static byte[] str(int field, String value) throws IOException { return msg(field, value.getBytes(StandardCharsets.UTF_8)); }
    static byte[] abi(int selected, int alternative) throws IOException { return cat(msg(1, number(1, selected)), alternative == 0 ? new byte[0] : msg(2, number(1, alternative))); }
    static byte[] density(int selected, int alternative) throws IOException { return cat(msg(1, number(2, selected)), msg(2, number(2, alternative))); }
    static byte[] apk(String path, byte[] target) throws IOException { return msg(2, cat(msg(1, target), str(2, path), msg(3, number(2, 1)))); }
    static byte[] module(String name, byte[] apks) throws IOException { return msg(2, cat(msg(1, cat(str(1, name), number(6, 1))), apks)); }
    static byte[] variant(int id, byte[] target, byte[] modules) throws IOException { return msg(1, cat(msg(1, target), number(3, id), modules)); }
    static ApksTargeting.Device device() { return new ApksTargeting.Device(List.of("arm64-v8a", "armeabi-v7a"), Set.of("zh"), 420, 36); }
    @Test public void bundleTocMatchesAbiDensityLanguageAndMandatoryMaster() throws Exception {
        byte[] module = module("base", cat(apk("base.apk", new byte[0]), apk("arm64.apk", msg(1, abi(3, 5))), apk("x86.apk", msg(1, abi(5, 3))),
                apk("480.apk", msg(4, density(480, 320))), apk("320.apk", msg(4, density(320, 480))), apk("zh.apk", msg(3, str(1, "zh"))), apk("en.apk", msg(3, str(1, "en")))));
        var selected = ApksTargeting.select(variant(0, new byte[0], module), device(), null);
        assertEquals(Set.of("base.apk", "arm64.apk", "480.apk", "zh.apk"), selected.paths());
    }
    @Test public void variantSelectionUsesOneCompatibleVariantWithHighestPriority() throws Exception {
        byte[] toc = cat(variant(1, new byte[0], module("base", apk("old.apk", new byte[0]))),
                variant(5, msg(2, abi(3, 0)), module("base", apk("new.apk", new byte[0]))),
                variant(9, msg(2, abi(5, 0)), module("base", apk("wrong.apk", new byte[0]))));
        assertEquals(Set.of("new.apk"), ApksTargeting.select(toc, device(), null).paths());
    }
    @Test public void sdkAlternativesAndLanguageFallbackAreRespected() throws Exception {
        byte[] sdk21 = msg(1, msg(1, number(1, 21))), sdk40 = msg(1, msg(1, number(1, 40)));
        byte[] toc = cat(variant(1, msg(1, sdk21), module("base", cat(apk("base.apk", new byte[0]), apk("fallback.apk", msg(3, str(2, "en")))))),
                variant(2, msg(1, sdk40), module("base", apk("future.apk", new byte[0]))));
        assertEquals(Set.of("base.apk", "fallback.apk"), ApksTargeting.select(toc, device(), null).paths());
    }
    @Test public void installTimeDependencyIsIncludedWhileUnrequestedOnDemandModuleIsExcluded() throws Exception {
        byte[] base = msg(2, cat(msg(1, cat(str(1, "base"), number(6, 1), str(4, "needed"))), apk("base.apk", new byte[0])));
        byte[] needed = msg(2, cat(msg(1, cat(str(1, "needed"), number(6, 2))), apk("needed.apk", new byte[0])));
        byte[] unused = msg(2, cat(msg(1, cat(str(1, "unused"), number(6, 2))), apk("unused.apk", new byte[0])));
        assertEquals(Set.of("base.apk", "needed.apk"), ApksTargeting.select(variant(0, new byte[0], cat(base, needed, unused)), device(), null).paths());
    }
    @Test public void malformedUnknownTargetAndEscapingPathsFailClosed() throws Exception {
        for (byte[] bytes : List.of(new byte[]{10, (byte)255}, variant(0, msg(99, number(1, 1)), module("base", apk("base.apk", new byte[0]))),
                variant(0, new byte[0], module("base", apk("../base.apk", new byte[0]))))) {
            try { ApksTargeting.select(bytes, device(), null); fail("Bad metadata accepted"); } catch (IOException expected) { }
        }
    }
    @Test public void androidDensityMatchingPrefersDownsample() {
        assertEquals(480, ApksTargeting.bestDensity(Set.of(320, 480), 400));
        assertEquals(320, ApksTargeting.bestDensity(Set.of(320, 480), 320));
        assertEquals(640, ApksTargeting.bestDensity(Set.of(160, 640), 800));
    }
    @Test public void multiAbiChoosesCompleteCompatibleSetOverSubset() throws Exception {
        byte[] single = number(1, 3);
        byte[] arm64 = msg(1, single), armBoth = cat(msg(1, number(1, 3)), msg(1, number(1, 2)));
        byte[] subsetTarget = cat(msg(1, arm64), msg(2, armBoth));
        byte[] fullTarget = cat(msg(1, armBoth), msg(2, arm64));
        byte[] toc = variant(0, new byte[0], module("base", cat(apk("base.apk", new byte[0]),
                apk("subset.apk", msg(7, subsetTarget)), apk("complete.apk", msg(7, fullTarget)))));
        assertEquals(Set.of("base.apk", "complete.apk"), ApksTargeting.select(toc, device(), null).paths());
    }
    @Test public void actualBundlePreparationExtractsOnlySelectedApksAndPreservesExportedSet() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        String path = InstrumentationRegistry.getArguments().getString("apksFixture"); Assume.assumeNotNull(path);
        File folder = Files.createTempDirectory(context.getCacheDir().toPath(), "targeting-test-").toFile();
        try {
            File apks = new File(folder, "bundle.apks");
            byte[] toc = variant(0, new byte[0], module("base", cat(apk("splits/base.apk", new byte[0]),
                    apk("splits/zh.apk", new byte[0]), apk("splits/density.apk", new byte[0]), apk("splits/unmatched.apk", msg(5, msg(1, msg(1, number(1, 99))))))));
            try (ZipFile sample = new ZipFile(path); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(apks))) {
                out.putNextEntry(new ZipEntry("toc.pb")); out.write(toc); out.closeEntry();
                for (var pair : Map.of("base.apk", "splits/base.apk", "split_config.zh.apk", "splits/zh.apk", "split_config.xxxhdpi.apk", "splits/density.apk").entrySet()) {
                    out.putNextEntry(new ZipEntry(pair.getValue())); try (InputStream in = sample.getInputStream(sample.getEntry(pair.getKey()))) { in.transferTo(out); } out.closeEntry();
                }
                out.putNextEntry(new ZipEntry("splits/unmatched.apk")); out.write(new byte[]{1,2,3}); out.closeEntry();
            }
            try (var prepared = SplitApks.prepare(context, FileStore.local(apks), new AtomicBoolean())) {
                assertEquals(3, prepared.parts.size()); assertEquals(List.of("splits/unmatched.apk"), prepared.excluded);
                assertTrue(prepared.selection.contains("标准 APKS"));
            }
            try (var prepared = SplitApks.prepare(context, FileStore.local(new File(path)), new AtomicBoolean())) {
                assertEquals(3, prepared.parts.size()); assertTrue(prepared.excluded.isEmpty());
            }
        } finally { new FileStore(context).delete(FileStore.local(folder)); }
    }
}
