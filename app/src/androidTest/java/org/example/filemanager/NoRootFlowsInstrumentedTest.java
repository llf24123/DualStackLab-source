package org.example.filemanager;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.ListView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NoRootFlowsInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work, right;
    private SharedPreferences preferences, places;
    private String oldLeft, oldRight, oldBookmarks, oldLeftFilter;
    private Boolean oldLeftRegex;
    private boolean rootPreferenceChanged;
    private Boolean oldRoot;
    private ActivityScenario<MainActivity> scenario;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "no-root-flows-").toFile();
        assertTrue(new File(work, "child").mkdir()); right = new File(work, "right"); assertTrue(right.mkdir());
        Files.write(new File(work, "readme.txt").toPath(), new byte[]{1, 2, 3});
        preferences = context.getSharedPreferences("MainActivity", 0); places = context.getSharedPreferences("directory-places", 0);
        oldLeft = preferences.getString("left", null); oldRight = preferences.getString("right", null);
        oldLeftFilter=preferences.getString("filter-left",null);
        oldLeftRegex=preferences.contains("regex-left")?preferences.getBoolean("regex-left",false):null;
        oldBookmarks = places.getString("bookmarks", null);
        preferences.edit().putString("left", work.getPath()).putString("right", right.getPath())
                .remove("filter-left").remove("regex-left").commit();
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        if (rootPreferenceChanged) {
            SharedPreferences.Editor root = FileManagerSettings.prefs(context).edit();
            if (oldRoot == null) root.remove("root"); else root.putBoolean("root", oldRoot);
            root.commit(); RootAccess.setAutomatic(oldRoot == null || oldRoot);
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (oldLeft == null) editor.remove("left"); else editor.putString("left", oldLeft);
        if (oldRight == null) editor.remove("right"); else editor.putString("right", oldRight);
        editor.putString("filter-left",oldLeftFilter);
        if(oldLeftRegex==null) editor.remove("regex-left"); else editor.putBoolean("regex-left",oldLeftRegex);
        editor.commit();
        if (oldBookmarks == null) places.edit().remove("bookmarks").commit(); else places.edit().putString("bookmarks", oldBookmarks).commit();
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void missingBookmarkOpensItsExistingParentAndThePaneRemainsUsable() throws Exception {
        File missing = new File(work, "missing/nested");
        DirectoryPlaces bookmarks = new DirectoryPlaces(context);
        bookmarks.bookmark(DirectoryPlaces.Entry.from(FileStore.directory(missing, false)));
        launch();
        scenario.onActivity(activity -> invoke(activity, "directorySheet"));
        UiObject2 bookmark = device.wait(Until.findObject(By.desc("目录快捷项 nested")), 1500);
        if (bookmark == null) {
            UiObject2 panel = device.wait(Until.findObject(By.desc("最近与书签面板")), 5000); assertNotNull(panel);
            android.graphics.Rect bounds = panel.getVisibleBounds();
            for (int i = 0; i < 4 && bookmark == null; i++) {
                device.swipe(bounds.centerX(), bounds.bottom - 30, bounds.centerX(), bounds.top + 30, 12);
                bookmark = device.wait(Until.findObject(By.desc("目录快捷项 nested")), 1000);
            }
        }
        assertNotNull(bookmark); bookmark.click();
        await(work);
        device.wait(Until.gone(By.desc("最近与书签面板")), 5000);
        UiObject2 child = device.wait(Until.findObject(By.text("child")), 5000); assertNotNull(child); child.click();
        await(new File(work, "child"));
    }
    @Test public void failedNavigationRestoresLocationFilterAndHistory() throws Exception {
        launch();
        scenario.onActivity(activity -> {
            Object pane = field(activity, "left");
            try {
                Field filter = pane.getClass().getDeclaredField("filter"); filter.setAccessible(true); filter.set(pane, "readme");
                invoke(pane, "apply");
            } catch (Exception error) { throw new RuntimeException(error); }
            invoke(pane, "navigate", FileStore.directory(new File(work, "does-not-exist"), false));
        });
        await(work);
        scenario.onActivity(activity -> {
            Object pane = field(activity, "left");
            assertEquals("readme", field(pane, "filter"));
            assertTrue(((Collection<?>) field(pane, "backHistory")).isEmpty());
            assertEquals(right.getPath(), ((FileStore.Node) field(field(activity, "right"), "location")).path());
        });
    }
    @Test public void iconTapOpensDirectoryAndLongPressStillSelects() throws Exception {
        launch();
        android.graphics.Rect icon = iconOnLeft("child");
        assertTrue(device.click(icon.centerX(), icon.centerY()));
        await(new File(work, "child"));
        scenario.onActivity(activity -> {
            Object pane = field(activity, "left"); assertTrue(((Set<?>) field(pane, "selected")).isEmpty()); invoke(pane, "up");
        });
        await(work);
        icon = iconOnLeft("child");
        int holdSteps = (ViewConfiguration.getLongPressTimeout() + 250) / 5;
        assertTrue(device.swipe(icon.centerX(), icon.centerY(), icon.centerX(), icon.centerY(), holdSteps));
        AtomicBoolean selected = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 3000;
        do {
            scenario.onActivity(activity -> selected.set(((Set<?>)field(field(activity,"left"),"selected"))
                    .contains(FileStore.local(new File(work,"child")).key())));
            if (selected.get()) break;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("Long press did not select the row", selected.get());
    }
    private android.graphics.Rect iconOnLeft(String name) {
        UiObject2 pane=device.wait(Until.findObject(By.desc("左窗口文件列表").hasDescendant(By.text(name))),6000);
        assertNotNull("Expected a visible row named "+name,pane);device.waitForIdle();
        android.graphics.Rect icon=new android.graphics.Rect();
        scenario.onActivity(a->{
            ListView list=(ListView)field(field(a,"left"),"list");assertTrue(a.hasWindowFocus());
            for(int i=0;i<list.getChildCount();i++){
                ViewGroup row=(ViewGroup)list.getChildAt(i);Object node=field(row.getTag(),"node");
                if(node instanceof FileStore.Node entry && name.equals(entry.name)){
                    View frame=row.getChildAt(0);assertTrue(frame.getGlobalVisibleRect(icon));
                    assertEquals(name,frame.getContentDescription());return;
                }
            }
            fail("No bound icon for "+name);
        });return icon;
    }
    @Test public void freshBookmarksContainRequestedPathsAndInitializeOnlyOnce() {
        SharedPreferences isolated = context.getSharedPreferences(work.getName(), 0);
        Context wrapper = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return isolated; }
        };
        try {
            DirectoryPlaces first = new DirectoryPlaces(wrapper);
            Set<String> paths = new HashSet<>(); for (DirectoryPlaces.Entry entry : first.entries(true)) paths.add(entry.path());
            assertTrue(paths.containsAll(List.of("/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv",
                    "/storage/emulated/0/Download", "/data/adb",
                    "/storage/emulated/0/Android/data/tw.nekomimi.nekogram/files/Telegram/Telegram Files")));
            first.clear(true); assertTrue(new DirectoryPlaces(wrapper).entries(true).isEmpty());
        } finally { isolated.edit().clear().commit(); }
    }
    @Test public void grantedDocumentSubtreeCannotReachSiblingPackages() {
        DataDocumentsProvider provider = new DataDocumentsProvider();
        String parent = "/storage/emulated/0/Android/data/package.a";
        assertTrue(provider.isChildDocument(parent, parent + "/files/item.txt"));
        assertFalse(provider.isChildDocument(parent, parent + "/../package.b/files/item.txt"));
        assertFalse(provider.isChildDocument(parent, parent + "/files/../../package.b"));
        assertFalse(provider.isChildDocument(parent, parent + "/files//item.txt"));
        assertFalse(provider.isChildDocument(parent, parent + ".other/files"));
    }
    @Test public void apkInstallWithoutRootLaunchesTheSystemInstaller() throws Exception {
        try (SystemInstallUi system = new SystemInstallUi(context, device)) {
            system.requireInitialSource(true);
            File apk = installFixture("fixture.apk.1");
            UiObject2 install = openInstallDialog(apk);
            // The valid archive already shown in the dialog must survive loss of the original file.
            Files.delete(apk.toPath());
            install.click();
            system.awaitConfirmationAndCancel();
            awaitMainAfterInstall();
        }
    }
    @Test public void grantingApkSourceInSettingsContinuesToSystemConfirmation() throws Exception {
        try (SystemInstallUi system = new SystemInstallUi(context, device)) {
            system.requireInitialSource(false);
            File apk = installFixture("grant.apk");
            openInstallDialog(apk).click();
            system.openSourceSettings(); system.assertNoNewSession();
            system.grantSourceInSettings();
            device.pressBack();
            system.awaitConfirmationAndCancel();
            awaitMainAfterInstall();
        }
    }
    @Test public void leavingSourceDeniedReturnsToFilesWithoutStartingAnInstall() throws Exception {
        try (SystemInstallUi system = new SystemInstallUi(context, device)) {
            system.requireInitialSource(false);
            File apk = installFixture("deny.apk");
            openInstallDialog(apk).click();
            system.openSourceSettings(); system.assertNoNewSession();
            device.pressBack();
            awaitMainAfterInstall(); system.assertNoNewSession();
            // Granting later must not revive the cancelled request on the next resume.
            scenario.onActivity(activity -> activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.fromParts("package", activity.getPackageName(), null))));
            system.openSourceSettings(); system.grantSourceInSettings(); device.pressBack();
            awaitMainAfterInstall(); system.assertNoNewSession();
            scenario.recreate();
            awaitMainAfterInstall(); system.assertNoNewSession();
        }
    }
    @Test public void grantingApksSourceReturnsToSelectionBeforeStartingASession() throws Exception {
        try (SystemInstallUi system = new SystemInstallUi(context, device)) {
            system.requireInitialSource(false);
            File apks = new File(work, "bundle.apks");
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(apks)); InputStream in = fixtureBytes()) {
                out.putNextEntry(new ZipEntry("base.apk")); FileStore.pump(in, out); out.closeEntry();
            }
            openInstallDialog(apks).click();
            system.openSourceSettings(); system.assertNoNewSession();
            system.grantSourceInSettings(); device.pressBack();
            var selected = device.wait(Until.findObject(By.pkg(context.getPackageName()).text("确认安装所选 APK").enabled(true)), 15000);
            assertNotNull("APKS must return to component selection", selected); system.assertNoNewSession();
            selected.click(); system.awaitConfirmationAndCancel(); awaitMainAfterInstall();
        }
    }
    private File installFixture(String name) throws IOException {
        File apk = new File(work, name);
        try (InputStream in = fixtureBytes()) { Files.copy(in, apk.toPath()); }
        return apk;
    }
    private InputStream fixtureBytes() throws IOException {
        return InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("install-fixture.apk");
    }
    private UiObject2 openInstallDialog(File file) {
        SharedPreferences root = FileManagerSettings.prefs(context);
        oldRoot = root.contains("root") ? root.getBoolean("root", true) : null;
        rootPreferenceChanged = true;
        assertTrue(root.edit().putBoolean("root", false).commit()); RootAccess.setAutomatic(false);
        launch();
        UiObject2 item = device.wait(Until.findObject(By.pkg(context.getPackageName()).text(file.getName())), 10000);
        assertNotNull("File missing from pane: " + file.getName(), item); item.click();
        UiObject2 button = device.wait(Until.findObject(By.pkg(context.getPackageName()).text("安装").enabled(true)), 15000);
        assertNotNull("Install button did not become ready", button); return button;
    }
    private void awaitMainAfterInstall() {
        SystemInstallUi.await("Installation flow did not return to files", () ->
                scenario.getState() == androidx.lifecycle.Lifecycle.State.RESUMED
                && device.hasObject(By.pkg(context.getPackageName()).desc("左窗口文件列表")));
    }
    private void launch() { scenario = ActivityScenario.launch(MainActivity.class); await(work); }
    private void await(File expected) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        do {
            AtomicBoolean ready = new AtomicBoolean();
            scenario.onActivity(activity -> {
                Object pane = field(activity, "left");
                FileStore.Node location = (FileStore.Node) field(pane, "location");
                ListView list = (ListView) field(pane, "list");
                ready.set(location.path().equals(expected.getPath()) && field(pane, "loading") == null && list.isEnabled() && list.getVisibility() == View.VISIBLE);
            });
            if (ready.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Pane did not become usable at " + expected);
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void invoke(Object target, String name, Object... arguments) {
        try {
            for (Method method : target.getClass().getDeclaredMethods()) if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                method.setAccessible(true); method.invoke(target, arguments); return;
            }
            throw new NoSuchMethodException(name);
        } catch (Exception error) { throw new RuntimeException(error); }
    }
}
