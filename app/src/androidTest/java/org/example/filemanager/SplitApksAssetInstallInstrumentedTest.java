package org.example.filemanager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Optional large asset-split regression: supply -e dinosaurFixture /sdcard/Download/APKS-Regression.apks. */
@RunWith(AndroidJUnit4.class)
public class SplitApksAssetInstallInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File sample, work;
    private SplitApks.Manifest identity;
    private ActivityScenario<SplitInstallActivity> scenario;
    private boolean removeInstalledFixture;

    @Before public void setup() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("dinosaurFixture");
        Assume.assumeNotNull(path);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " REQUEST_INSTALL_PACKAGES allow");
        sample = new File(path);
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "asset-install-test-").toFile();
        File base = new File(work, "base.apk");
        try (ZipFile zip = new ZipFile(sample); InputStream input = zip.getInputStream(zip.getEntry("base.apk"))) {
            Files.copy(input, base.toPath());
        }
        identity = SplitApks.manifest(base);
    }

    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        if (removeInstalledFixture) device.executeShellCommand("pm uninstall " + identity.packageName());
        if (work != null) new FileStore(context).delete(FileStore.local(work));
    }

    @Test public void suppliedArchiveWritesAllFiveApksIncludingAssetsToOneSession() throws Exception {
        try (SplitApks.Prepared prepared = SplitApks.prepare(context, FileStore.local(sample), new AtomicBoolean())) {
            assertPrepared(prepared);
            long total = 0;
            try (ZipFile zip = new ZipFile(sample)) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith(".apk")) total += entry.getSize();
                }
            }
            assertEquals(total, prepared.bytes);
            int id = SplitApks.writeSession(context, prepared, new AtomicBoolean());
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            try (PackageInstaller.Session session = installer.openSession(id)) {
                assertEquals(5, session.getNames().length);
                byte[] buffer = new byte[128 * 1024];
                for (int i = 0; i < prepared.parts.size(); i++) {
                    long written = 0;
                    try (InputStream input = session.openRead("part-" + i + ".apk")) {
                        int count; while ((count = input.read(buffer)) != -1) written += count;
                    }
                    assertEquals(prepared.parts.get(i).file().length(), written);
                }
            } finally { installer.abandonSession(id); }
        }
    }

    @Test public void suppliedArchiveInstallsThroughSystemConfirmationWithAssetSplit() throws Exception {
        try {
            context.getPackageManager().getPackageInfo(identity.packageName(), 0);
            fail("The optional fixture must be absent before this installation test");
        } catch (PackageManager.NameNotFoundException expected) { }
        removeInstalledFixture = true;
        Set<Integer> sessionsBefore = new HashSet<>();
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        for (var session : installer.getMySessions()) sessionsBefore.add(session.getSessionId());
        Intent intent = new Intent(context, SplitInstallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        FileStore.local(sample).put(intent);
        scenario = ActivityScenario.launch(intent);
        UiObject2 prepared = device.wait(Until.findObject(By.pkg(context.getPackageName()).text("确认安装所选 APK")), 90000);
        assertNotNull("Could not prepare the APKS: " + dump(), prepared);
        assertTrue(device.hasObject(By.pkg(context.getPackageName()).textContains("选中 5 个 APK")));
        prepared.click();
        UiObject2 confirmation = systemInstallButton(90000);
        assertNotNull("System install confirmation was not shown: " + dump(), confirmation);
        assertTrue("The system should own an active five-part installation session", installer.getMySessions().stream()
                .anyMatch(session -> !sessionsBefore.contains(session.getSessionId()) && session.isSealed()
                        && identity.packageName().equals(session.getAppPackageName())));
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "apks-asset-system-confirm.png"));
        confirmation.click();
        PackageInfo installed = null;
        long deadline = SystemClock.uptimeMillis() + 120000;
        while (SystemClock.uptimeMillis() < deadline) {
            try { installed = context.getPackageManager().getPackageInfo(identity.packageName(), 0); break; }
            catch (PackageManager.NameNotFoundException pending) { SystemClock.sleep(200); }
        }
        assertNotNull("The confirmed APKS was not installed: " + dump(), installed);
        String[] version = identity.version().split(":");
        long expectedVersion = (Long.parseLong(version[0]) << 32) | Integer.toUnsignedLong(Integer.parseInt(version[1]));
        assertEquals(expectedVersion, installed.getLongVersionCode());
        assertEquals(Set.of("config.arm64_v8a", "config.xxxhdpi", "config.zh", "obbassets"),
                new HashSet<>(Arrays.asList(installed.splitNames)));
        assertEquals(4, installed.applicationInfo.splitSourceDirs.length);
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "apks-asset-installed.png"));
        long cleanupDeadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < cleanupDeadline && installer.getMySessions().stream()
                .anyMatch(session -> !sessionsBefore.contains(session.getSessionId()))) SystemClock.sleep(100);
        assertTrue(installer.getMySessions().stream().allMatch(session -> sessionsBefore.contains(session.getSessionId())));
    }

    private void assertPrepared(SplitApks.Prepared prepared) throws Exception {
        assertEquals(identity.packageName(), prepared.packageName);
        assertEquals(5, prepared.parts.size());
        assertEquals("", prepared.parts.get(0).manifest().split());
        Set<String> names = new HashSet<>();
        for (SplitApks.Part part : prepared.parts) {
            assertEquals(identity.version(), part.manifest().version());
            names.add(part.manifest().split());
            if (part.manifest().split().equals("config.arm64_v8a") || part.manifest().split().equals("obbassets"))
                try (ZipFile split = new ZipFile(part.file())) { assertNull(split.getEntry("resources.arsc")); }
        }
        assertEquals(Set.of("", "config.arm64_v8a", "config.xxxhdpi", "config.zh", "obbassets"), names);
        assertTrue(prepared.excluded.isEmpty());
    }
    private UiObject2 systemInstallButton(long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            for (UiObject2 button : device.findObjects(By.text(java.util.regex.Pattern.compile("(?i)install|安装")).enabled(true).clickable(true))) {
                String owner = button.getApplicationPackage();
                if (owner != null && !owner.equals(context.getPackageName())) {
                    try {
                        ApplicationInfo info = context.getPackageManager().getApplicationInfo(owner, 0);
                        if ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return button;
                    } catch (PackageManager.NameNotFoundException ignored) { }
                }
            }
            SystemClock.sleep(100);
        }
        return null;
    }
    private String dump() {
        try { File file = new File(work, "ui.xml"); device.dumpWindowHierarchy(file); return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception error) { return error.toString(); }
    }
}
