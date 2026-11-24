package org.example.filemanager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import rikka.shizuku.Shizuku;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ShizukuStorageInstrumentedTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String PATH = "/storage/emulated/0/Android/data/org.example.installfixture/files/dualstack-0627";
    @Before public void requireBridge() {
        Assume.assumeTrue("Requires explicitly prepared shell Shizuku fixture", InstrumentationRegistry.getArguments().getString("shizukuFixture", "false").equals("true"));
        assertTrue(ShizukuStorage.granted()); assertEquals(2000, Shizuku.getUid()); assertFalse(RootAccess.available());
    }
    @Test public void shellBridgeListsAndReadsAnotherAppsDataWithoutRoot() throws Exception {
        assertNull("Ordinary app unexpectedly has directory access", new File(PATH).listFiles());
        FileStore store = new FileStore(context);
        FileStore.Node directory = AndroidDataAccess.resolve(context, store, FileStore.directory(new File(PATH), false));
        assertTrue(DataDocumentsProvider.owns(directory.uri)); assertFalse(directory.root); assertEquals(PATH, directory.path());
        List<FileStore.Node> entries = store.list(directory); assertEquals(261, entries.size());
        FileStore.Node readme = entries.stream().filter(node -> node.name.equals("readme.txt")).findFirst().orElseThrow();
        try (InputStream input = store.read(readme)) { assertEquals("shizuku-data-fixture", new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
        assertEquals(new File(PATH).getParent(), store.parent(directory).path());
        File output = Files.createTempDirectory(context.getCacheDir().toPath(), "shizuku-copy-").toFile();
        try {
            FileStore.Node copied = store.copyAll(List.of(readme), FileStore.directory(output, false), false).get(0);
            assertEquals("shizuku-data-fixture", new String(Files.readAllBytes(copied.file.toPath()), StandardCharsets.UTF_8));
        } finally { new FileStore(context).delete(FileStore.local(output)); }
    }
    @Test public void documentOperationsUseTheGrantedShellService() throws Exception {
        FileStore store = new FileStore(context);
        FileStore.Node directory = store.document(DataDocumentsProvider.uri(PATH));
        FileStore.Node created = store.create(directory, "created-by-client.txt", false);
        File payload = File.createTempFile("shizuku-payload-", ".txt", context.getCacheDir());
        try {
            Files.write(payload.toPath(), "client-content".getBytes(StandardCharsets.UTF_8));
            store.write(created, payload);
            try (InputStream input = store.read(created)) { assertEquals("client-content", new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
            created = store.rename(created, "renamed-by-client.txt");
            assertTrue(store.list(directory).stream().anyMatch(node -> node.name.equals("renamed-by-client.txt")));
        } finally { store.delete(created); Files.deleteIfExists(payload.toPath()); }
        assertTrue(store.list(directory).stream().noneMatch(node -> node.name.equals("renamed-by-client.txt") || node.name.equals("created-by-client.txt")));
    }
    @Test public void bridgeRejectsPathsOutsideDataAndProtectsDataRoot() throws Exception {
        IDataAccess service = ShizukuStorage.remote(context);
        for (String path : List.of("/data/system", "/storage/emulated/0/Download", PATH + "/../../../../../../../data/system")) {
            try { service.stat(path); fail("Must reject " + path); } catch (IllegalStateException expected) { }
        }
        try { service.delete(DataDocumentsProvider.root()); fail("Data root must be protected"); }
        catch (IllegalStateException expected) { }
        assertNotNull(service.stat(PATH));
        String nested = service.create(PATH, "Android", true);
        try { service.delete(service.create(nested, "data", true)); }
        finally { service.delete(nested); }
    }
    @Test public void expiredConnectionCanBeRetriedAndLateCallbacksDoNotPoisonTheNewBinding() throws Exception {
        IDataAccess first = ShizukuStorage.remote(context);
        try { first.destroy(); } catch (android.os.RemoteException closed) { }
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        while (first.asBinder().isBinderAlive() && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(20);
        assertFalse(first.asBinder().isBinderAlive());
        try { ShizukuStorage.remote(context, 0); fail("The new connection should expire without waiting"); }
        catch (IOException expected) { }
        assertNotNull(ShizukuStorage.remote(context).stat(PATH));
    }
    @Test public void foreignDataDirectoryCanBeBrowsedAndRestoredInTheMainPane() throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("MainActivity", 0);
        String left = preferences.getString("left", null), right = preferences.getString("right", null);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try {
            try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(
                    new Intent(context, MainActivity.class).putExtra("navigatePath", PATH))) {
                boolean listed = device.wait(Until.hasObject(By.textStartsWith("page-")), 15000);
                device.waitForIdle();
                assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null), "shizuku-browser-0627.png")));
                assertTrue("Foreign package files did not appear in the main pane", listed);
                activity.recreate();
                assertTrue(device.wait(Until.hasObject(By.textStartsWith("page-")), 15000));
            }
            assertEquals(PATH, preferences.getString("left", ""));
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (left == null) editor.remove("left"); else editor.putString("left", left);
            if (right == null) editor.remove("right"); else editor.putString("right", right);
            editor.commit();
        }
    }
}
