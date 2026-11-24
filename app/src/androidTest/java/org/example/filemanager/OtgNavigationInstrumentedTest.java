package org.example.filemanager;

import android.content.*;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.*;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.io.*;
import java.nio.file.Files;
import java.lang.reflect.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OtgNavigationInstrumentedTest {
    private Context context;
    private UiDevice device;
    private ActivityScenario<MainActivity> screen;
    private final List<File> fixtures = new ArrayList<>();
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        screen = ActivityScenario.launch(MainActivity.class);
        find(By.desc("位置与工具")).click();
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        for (File fixture : fixtures) new FileStore(context).delete(FileStore.local(fixture));
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }
    @Test public void mountedDrawerOffersSystemDirectoryAuthorization() {
        Assume.assumeTrue("Requires a mounted removable volume",RemovableStorage.volumes(context).stream().anyMatch(RemovableStorage.Volume::mounted));
        find(By.text("授权 U 盘 / SD 卡"));
    }
    @Test public void noMountedDeviceHidesWholeRemovableSection() {
        Assume.assumeFalse("Requires no mounted removable volume",RemovableStorage.volumes(context).stream().anyMatch(RemovableStorage.Volume::mounted));
        screen.onActivity(activity->{
            Dialog drawer=(Dialog)field(activity,"drawer");View section=findDescription(drawer.getWindow().getDecorView(),"可移除存储分组");
            assertNotNull(section);assertEquals(View.GONE,section.getVisibility());
        });
        assertFalse(device.hasObject(By.text("U 盘 / SD 卡")));
        assertFalse(device.hasObject(By.text("授权 U 盘 / SD 卡")));
        assertFalse(device.hasObject(By.text("正在检测可移除存储…")));
        assertFalse(device.hasObject(By.text("未检测到已挂载的可移除存储")));
        assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "0645-drawer-no-device.png")));
    }
    @Test public void renderingFollowsMountedReadOnlyCheckingAndDetachedStates() {
        // Exercise the same renderer used by the media receiver; these are UI fixtures, not a hardware hotplug test.
        screen.onActivity(activity->{
            Dialog dialog=new Dialog(activity);LinearLayout section=new LinearLayout(activity),volumes=new LinearLayout(activity);
            TextView heading=new TextView(activity);heading.setText("U 盘 / SD 卡");section.addView(heading);section.addView(volumes);
            TextView authorize=new TextView(activity);authorize.setText("授权 U 盘 / SD 卡");section.addView(authorize);
            NavigationDrawer.renderRemovable(activity,dialog,section,volumes,List.of(),v->{});assertEquals(View.GONE,section.getVisibility());
            var mounted=new RemovableStorage.Volume("test-mounted","测试 U 盘",null,Environment.MEDIA_MOUNTED,null);
            var readOnly=new RemovableStorage.Volume("test-read-only","测试 SD 卡",null,Environment.MEDIA_MOUNTED_READ_ONLY,null);
            var stale=new RemovableStorage.Volume("old-grant","离线授权卷",null,Environment.MEDIA_UNMOUNTED,null);
            var checking=new RemovableStorage.Volume("checking","检查中",null,Environment.MEDIA_CHECKING,null);
            NavigationDrawer.renderRemovable(activity,dialog,section,volumes,List.of(mounted,stale,checking),v->{});
            assertEquals(View.VISIBLE,section.getVisibility());assertEquals(1,volumes.getChildCount());assertEquals("可移除存储 test-mounted",volumes.getChildAt(0).getContentDescription());
            NavigationDrawer.renderRemovable(activity,dialog,section,volumes,List.of(readOnly),v->{});
            assertEquals(View.VISIBLE,section.getVisibility());assertEquals(1,volumes.getChildCount());assertEquals("可移除存储 test-read-only",volumes.getChildAt(0).getContentDescription());
            NavigationDrawer.renderRemovable(activity,dialog,section,volumes,List.of(stale,checking),v->{});
            assertEquals(View.GONE,section.getVisibility());assertEquals(0,volumes.getChildCount());
        });
    }
    private View findDescription(View view,String description){
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View found=findDescription(group.getChildAt(i),description);if(found!=null)return found;}
        return null;
    }
    @Test public void mountedRemovableVolumesAppearInDrawer() throws Exception {
        StorageManager manager = context.getSystemService(StorageManager.class); int checked = 0;
        for (StorageVolume volume : manager.getStorageVolumes()) {
            if (volume.isPrimary() || !volume.isRemovable() || !(volume.getState().equals(Environment.MEDIA_MOUNTED)
                    || volume.getState().equals(Environment.MEDIA_MOUNTED_READ_ONLY))) continue;
            find(By.desc("可移除存储 " + volume.getUuid())); checked++;
        }
        Assume.assumeTrue("Requires a mounted removable volume", checked > 0);
        device.waitForIdle();
        assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "otg-drawer.png")));
    }
    @Test public void mountedVolumeOpensAndCopiesFilesInBothDirections() throws Exception {
        RemovableStorage.Volume volume = writableVolume();
        File fixture = new File(volume.directory(), "dualstack-otg-" + UUID.randomUUID()); assertTrue(fixture.mkdir());
        File local = Files.createTempDirectory(context.getCacheDir().toPath(), "otg-local-").toFile();
        FileStore store = new FileStore(context); byte[] bytes = new byte[32768]; new Random(22).nextBytes(bytes);
        try {
            Files.write(new File(fixture, "from-usb.bin").toPath(), bytes);
            find(By.desc("可移除存储 " + volume.id())).click();
            assertTrue(device.wait(Until.hasObject(By.text(fixture.getName())), 10000));
            FileStore.Node copied = store.copy(FileStore.local(new File(fixture, "from-usb.bin")), FileStore.local(local), false);
            assertArrayEquals(bytes, Files.readAllBytes(copied.file.toPath()));
            Files.write(new File(local, "to-usb.bin").toPath(), bytes);
            FileStore.Node output = store.copy(FileStore.local(new File(local, "to-usb.bin")), FileStore.local(fixture), false);
            assertArrayEquals(bytes, Files.readAllBytes(output.file.toPath()));
            assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "otg-directory.png")));
        } finally { store.delete(FileStore.local(fixture)); store.delete(FileStore.local(local)); }
    }
    @Test public void treeGrantMatchingKeepsVolumeAndSubtreeBoundaries() {
        String authority = "com.android.externalstorage.documents";
        Uri folder = android.provider.DocumentsContract.buildTreeDocumentUri(authority, "C81E-091B:Documents");
        Uri root = android.provider.DocumentsContract.buildTreeDocumentUri(authority, "C81E-091B:");
        Uri other = android.provider.DocumentsContract.buildTreeDocumentUri(authority, "C81E-091B-other:");
        Uri foreign = android.provider.DocumentsContract.buildTreeDocumentUri("other.provider", "C81E-091B:");
        assertEquals(root, RemovableStorage.matchingTree(List.of(folder, other, foreign, root), "c81e-091b"));
        assertEquals(folder, RemovableStorage.matchingTree(List.of(other, folder), "C81E-091B"));
        assertNull(RemovableStorage.matchingTree(List.of(other, foreign), "C81E-091B"));
    }
    @Test public void readOnlyAndDetachedStatesHaveAccuratePresence() {
        assertTrue(RemovableStorage.present(Environment.MEDIA_MOUNTED));
        assertTrue(RemovableStorage.present(Environment.MEDIA_MOUNTED_READ_ONLY));
        assertTrue(RemovableStorage.present(Environment.MEDIA_UNMOUNTABLE));
        for (String state : List.of(Environment.MEDIA_REMOVED, Environment.MEDIA_UNMOUNTED, Environment.MEDIA_BAD_REMOVAL))
            assertFalse(state, RemovableStorage.present(state));
        assertEquals("USB（只读）", new RemovableStorage.Volume("id", "USB", null, Environment.MEDIA_MOUNTED_READ_ONLY, null).title());
    }
    @Test public void removableMountRootsAreProtectedButChildFoldersRemainUsable() throws Exception {
        for (String path : List.of("/storage/C81E-091B", "/mnt/media_rw/C81E-091B", "/mnt/runtime/default/C81E-091B",
                "/mnt/runtime/read/C81E-091B", "/mnt/runtime/write/C81E-091B", "/mnt/user/0/C81E-091B")) {
            assertTrue(path, RootAccess.protectedStorageDirectory(new File(path)));
            assertFalse(path, RootAccess.protectedStorageDirectory(new File(path, "Documents")));
        }
        RemovableStorage.Volume volume = writableVolume();
        assertTrue(StorageNavigation.appManagedStorage(volume.directory()));
        assertTrue(RootAccess.protectedStorageDirectory(volume.directory()));
        try { new FileStore(context).delete(FileStore.directory(volume.directory(), false)); fail("Must reject volume-root deletion"); }
        catch (IOException expected) { assertTrue(volume.directory().isDirectory()); }
    }
    @Test public void mediaChangesRefreshTheOpenDrawer() throws Exception {
        String volumeId = InstrumentationRegistry.getArguments().getString("hotplugVolumeId", "");
        String uuid = InstrumentationRegistry.getArguments().getString("hotplugUuid", "");
        Assume.assumeTrue("Requires explicitly provisioned virtual removable volume", !volumeId.isEmpty() && !uuid.isEmpty());
        find(By.desc("可移除存储 " + uuid));
        try {
            device.executeShellCommand("sm unmount " + volumeId);
            assertTrue(device.wait(Until.gone(By.desc("可移除存储 " + uuid)), 15000));
            if(RemovableStorage.volumes(context).stream().noneMatch(RemovableStorage.Volume::mounted)){
                assertTrue(device.wait(Until.gone(By.text("U 盘 / SD 卡")),5000));
                assertFalse(device.hasObject(By.text("授权 U 盘 / SD 卡")));
            }
            device.executeShellCommand("sm mount " + volumeId);
            find(By.desc("可移除存储 " + uuid));
            find(By.text("U 盘 / SD 卡"));find(By.text("授权 U 盘 / SD 卡"));
        } finally { device.executeShellCommand("sm mount " + volumeId); }
    }
    @Test public void volumeAuthorizationReturnsATreeAndPersistsAcrossRecreation() throws Exception {
        RemovableStorage.Volume volume = writableVolume();
        File folder = new File(volume.directory(), "dualstack-auth-" + UUID.randomUUID()); assertTrue(folder.mkdir()); fixtures.add(folder);
        Intent authorization = volume.authorization().putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", volume.id() + ":" + folder.getName()));
        screen.onActivity(activity -> {
            try { ((android.app.Dialog) field(activity, "drawer")).dismiss(); }
            catch (Exception error) { throw new AssertionError(error); }
            activity.startActivityForResult(authorization, 100);
        });
        device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "otg-picker.xml"));
        UiObject2 use = device.wait(Until.findObject(By.res("com.google.android.documentsui", "action_menu_select")), 10000);
        if (use == null) use = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)use this folder|使用此文件夹|使用这个文件夹"))), 5000);
        assertNotNull("System folder picker must offer this mounted volume", use);
        assertTrue("Selected subdirectory must be eligible for authorization", use.isEnabled()); use.click();
        device.waitForIdle();
        UiObject2 allow = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)allow|允许"))), 5000);
        if (allow != null) allow.click();
        long until = android.os.SystemClock.uptimeMillis() + 10000;
        boolean[] opened = {false};
        while (!opened[0] && android.os.SystemClock.uptimeMillis() < until) {
            screen.onActivity(activity -> {
                FileStore.Node location = (FileStore.Node) field(field(activity, "left"), "location");
                opened[0] = location.uri != null;
            });
            if (!opened[0]) android.os.SystemClock.sleep(30);
        }
        if (!opened[0]) {
            device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "otg-grant-failure.xml"));
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "otg-grant-failure.png"));
        }
        assertTrue("Granted tree must open in the requesting pane", opened[0]);
        List<Uri> granted = new ArrayList<>();
        for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions())
            if (grant.isReadPermission()) granted.add(grant.getUri());
        Uri saved = RemovableStorage.matchingTree(granted, volume.id()); assertNotNull(saved);
        screen.recreate();
        FileStore.Node restored = RemovableStorage.open(context, new FileStore(context), volume);
        assertNotNull(restored); assertNotNull(restored.uri);
        FileStore store = new FileStore(context); FileStore.Node created = null;
        try {
            created = store.create(restored, "dualstack-authorized-" + UUID.randomUUID() + ".txt", false);
            try (OutputStream output = context.getContentResolver().openOutputStream(created.uri, "wt")) { output.write(new byte[]{1, 2, 3}); }
            try (InputStream input = store.read(created)) { assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes()); }
        } finally {
            if (created != null) store.delete(created);
            context.getContentResolver().releasePersistableUriPermission(saved, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
    }
    @Test public void temporaryTreePermissionStillOpensTheSelectedDirectory() throws Exception {
        java.util.concurrent.CountDownLatch granted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<android.os.Bundle> result = new java.util.concurrent.atomic.AtomicReference<>();
        String testPackage = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        Intent request = new Intent(CountingDocumentsProvider.AUTHORITY + ".GRANT")
                .setComponent(new ComponentName(testPackage, CountingDocumentsProvider.GrantReceiver.class.getName()));
        context.sendOrderedBroadcast(request, null, new BroadcastReceiver() {
            @Override public void onReceive(Context receiver, Intent intent) { result.set(getResultExtras(false)); granted.countDown(); }
        }, null, android.app.Activity.RESULT_CANCELED, null, null);
        assertTrue(granted.await(10, java.util.concurrent.TimeUnit.SECONDS)); assertNotNull(result.get());
        Uri uri = Uri.parse(result.get().getString("uri"));
        try {
            context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fail("Fixture grant must be transient");
        } catch (SecurityException expected) { }
        screen.onActivity(activity -> {
            ((android.app.Dialog) field(activity, "drawer")).dismiss();
            activity.onActivityResult(100, android.app.Activity.RESULT_OK, new Intent().setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        });
        long until = android.os.SystemClock.uptimeMillis() + 10000; boolean[] opened = {false};
        while (!opened[0] && android.os.SystemClock.uptimeMillis() < until) {
            screen.onActivity(activity -> opened[0] = uri.equals(((FileStore.Node) field(field(activity, "left"), "location")).uri));
            if (!opened[0]) android.os.SystemClock.sleep(30);
        }
        assertTrue("Transient grants must remain usable for this session", opened[0]);
    }
    private RemovableStorage.Volume writableVolume() {
        for (RemovableStorage.Volume volume : RemovableStorage.volumes(context))
            if (Environment.MEDIA_MOUNTED.equals(volume.state()) && volume.directory() != null && volume.directory().canWrite()) return volume;
        Assume.assumeTrue("Requires writable removable volume", false); return null;
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 view = device.wait(Until.findObject(selector), 10000); assertNotNull(selector.toString(), view); return view;
    }
}
