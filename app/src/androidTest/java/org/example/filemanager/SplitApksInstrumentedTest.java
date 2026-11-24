package org.example.filemanager;

import android.content.*;
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

@RunWith(AndroidJUnit4.class)
public class SplitApksInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File sample, work;
    private ActivityScenario<MainActivity> scenario;
    private SharedPreferences preferences;
    private String left, right;
    private static final String PACKAGE = "gr.nikolasspyr.integritycheck";
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String path = InstrumentationRegistry.getArguments().getString("apksFixture");
        Assume.assumeNotNull(path);
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " REQUEST_INSTALL_PACKAGES allow");
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "apks-test-").toFile();
        sample = new File(work, "Play Integrity API Checker_2.2.apks");
        Files.copy(new File(path).toPath(), sample.toPath());
        preferences = context.getSharedPreferences("MainActivity", 0);
        left = preferences.getString("left", null); right = preferences.getString("right", null);
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        if (preferences != null) {
            SharedPreferences.Editor e = preferences.edit();
            if (left == null) e.remove("left"); else e.putString("left", left);
            if (right == null) e.remove("right"); else e.putString("right", right);
            e.commit();
        }
        if (work != null) new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void suppliedArchiveProducesBaseAndBothSplitsInOneSession() throws Exception {
        Set<String> cache = caches();
        try (SplitApks.Prepared p = prepare(sample)) {
            assertEquals(PACKAGE, p.packageName); assertEquals(3, p.parts.size());
            assertEquals("", p.parts.get(0).manifest().split());
            assertEquals(Set.of("", "config.xxxhdpi", "config.zh"), splitNames(p));
            assertEquals(2402064, p.bytes);
            int id = SplitApks.writeSession(context, p, new AtomicBoolean());
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            try (PackageInstaller.Session s = installer.openSession(id)) { assertEquals(3, s.getNames().length); }
            finally { installer.abandonSession(id); }
        }
        assertEquals(cache, caches());
    }
    @Test public void rejectsMissingBaseAndDuplicateSplitsAndCleansCache() throws Exception {
        File missing = rewrite("missing.apks", false, false, false);
        assertRejected(missing, "缺少基础");
        File duplicate = rewrite("duplicate.apks", true, true, false);
        assertRejected(duplicate, "重复拆分");
        File invalid = rewrite("invalid.apks", true, false, true);
        assertRejected(invalid, "");
        assertRejected(rewrite("traversal.apks", true, false, false), "");
    }
    @Test public void cancellationCleansCacheAndDoesNotCreateSession() throws Exception {
        Set<String> cache = caches(); int sessions = context.getPackageManager().getPackageInstaller().getMySessions().size();
        try { SplitApks.prepare(context, FileStore.local(sample), new AtomicBoolean(true)); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("取消")); }
        assertEquals(cache, caches());
        assertEquals(sessions, context.getPackageManager().getPackageInstaller().getMySessions().size());
    }
    @Test public void fileManagerInstallsTheSuppliedArchiveThroughSystemConfirmation() throws Exception {
        try { context.getPackageManager().getPackageInfo(PACKAGE, 0); fail("Fixture package must be absent before this test"); }
        catch (PackageManager.NameNotFoundException expected) { }
        try {
            openFromFileManager();
            UiObject2 install = systemInstallButton();
            assertNotNull("System confirmation not shown: " + dump(), install);
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "apks-confirm-0628.png"));
            install.click();
            PackageInfo installed = null;
            long until = SystemClock.uptimeMillis() + 30000;
            while (SystemClock.uptimeMillis() < until) {
                try { installed = context.getPackageManager().getPackageInfo(PACKAGE, 0); break; }
                catch (PackageManager.NameNotFoundException absent) { SystemClock.sleep(100); }
            }
            assertNotNull("Installation did not complete: " + dump(), installed);
            assertEquals(22, installed.getLongVersionCode());
            assertEquals(Set.of("config.xxxhdpi", "config.zh"), new HashSet<>(Arrays.asList(installed.splitNames)));
            assertEquals(2, installed.applicationInfo.splitSourceDirs.length);
        } finally { device.executeShellCommand("pm uninstall " + PACKAGE); }
    }
    @Test public void systemCancelLeavesNoInstalledPackageOrOwnedSession() throws Exception {
        int before = context.getPackageManager().getPackageInstaller().getMySessions().size();
        Set<String> cache = caches();
        openFromFileManager(); assertNotNull(systemInstallButton());
        UiObject2 cancel = device.findObject(By.text(java.util.regex.Pattern.compile("(?i)cancel|取消")));
        assertNotNull(cancel); cancel.click();
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline && context.getPackageManager().getPackageInstaller().getMySessions().size() != before)
            SystemClock.sleep(100);
        assertEquals(before, context.getPackageManager().getPackageInstaller().getMySessions().size());
        assertEquals(cache, caches());
        try { context.getPackageManager().getPackageInfo(PACKAGE, 0); fail("Cancelled package was installed"); }
        catch (PackageManager.NameNotFoundException expected) { }
    }
    private void openFromFileManager() {
        preferences.edit().putString("left", work.getPath()).putString("right", work.getPath()).commit();
        scenario = ActivityScenario.launch(MainActivity.class);
        UiObject2 file = device.wait(Until.findObject(By.text(sample.getName())), 10000); assertNotNull(file); file.click();
        UiObject2 install = device.wait(Until.findObject(By.text("安装")), 5000); assertNotNull(install); install.click();
        UiObject2 selected = device.wait(Until.findObject(By.text("确认安装所选 APK")), 15000); assertNotNull(selected); selected.click();
    }
    private UiObject2 systemInstallButton() {
        long until = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < until) {
            UiObject2 button = device.findObject(By.text(java.util.regex.Pattern.compile("(?i)install|安装")));
            if (button != null && !context.getPackageName().equals(button.getApplicationPackage()) && button.isEnabled()) return button;
            SystemClock.sleep(100);
        }
        return null;
    }
    private String dump() {
        try { File f = new File(work, "ui.xml"); device.dumpWindowHierarchy(f); return new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return e.toString(); }
    }
    private Set<String> caches() {
        return new HashSet<>(Arrays.asList(context.getCacheDir().list((d, name) -> name.startsWith("split-install-"))));
    }
    private SplitApks.Prepared prepare(File file) throws Exception { return SplitApks.prepare(context, FileStore.local(file), new AtomicBoolean()); }
    private Set<String> splitNames(SplitApks.Prepared p) {
        Set<String> names = new HashSet<>(); for (SplitApks.Part part : p.parts) names.add(part.manifest().split()); return names;
    }
    private void assertRejected(File file, String text) throws Exception {
        Set<String> cache = caches();
        try (SplitApks.Prepared ignored = prepare(file)) { fail("Invalid APKS accepted"); }
        catch (Exception expected) { if (!text.isEmpty()) assertTrue(expected.toString(), expected.getMessage().contains(text)); }
        assertEquals(cache, caches());
    }
    private File rewrite(String name, boolean base, boolean duplicate, boolean corrupt) throws Exception {
        File file = new File(work, name);
        try (ZipFile in = new ZipFile(sample); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement(); if (!base && entry.getName().equals("base.apk")) continue;
                byte[] data; try (InputStream stream = in.getInputStream(entry)) { data = stream.readAllBytes(); }
                if (corrupt && entry.getName().equals("base.apk")) data = new byte[]{1,2,3};
                out.putNextEntry(new ZipEntry((name.equals("traversal.apks") ? "../" : "nested/") + entry.getName())); out.write(data); out.closeEntry();
                if (duplicate && entry.getName().equals("split_config.zh.apk")) {
                    out.putNextEntry(new ZipEntry("another-name.apk")); out.write(data); out.closeEntry();
                }
            }
        }
        return file;
    }
}
