package org.example.filemanager;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.SystemClock;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchivePickerStateInstrumentedTest {
    private Instrumentation instrumentation;
    private Context app;
    private File work, archive;
    private ActivityScenario<ArchiveActivity> screen;
    private Instrumentation.ActivityMonitor picker;

    @Before public void setup() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        app = instrumentation.getTargetContext();
        File shared = new File(app.getCacheDir(), "shared");
        Files.createDirectories(shared.toPath());
        work = Files.createTempDirectory(shared.toPath(), "archive-picker-test-").toFile();
        archive = new File(work, "input.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            zip.putNextEntry(new ZipEntry("sub/old.txt"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        Intent intent = new Intent(app, ArchiveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        FileStore.local(archive).put(intent);
        screen = ActivityScenario.launch(intent);
        waitReady();
    }
    @After public void cleanup() throws Exception {
        if (picker != null) instrumentation.removeMonitor(picker);
        if (screen != null) screen.close();
        if (work != null) new FileStore(app).delete(FileStore.local(work));
    }
    private static void await(BooleanSupplier check) {
        long end = SystemClock.uptimeMillis() + 12000;
        while (!check.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(35);
        assertTrue(check.getAsBoolean());
    }
    private static Object field(ArchiveActivity activity, String name) {
        try {
            Field f = ArchiveActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(activity);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void waitReady() {
        await(() -> {
            boolean[] ready = {false};
            screen.onActivity(a -> ready[0] = (boolean) field(a, "archiveReady"));
            return ready[0];
        });
    }
    private void choose(String action, String target) throws Exception {
        IntentFilter filter = new IntentFilter(action);
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        filter.addDataType("*/*");
        picker = instrumentation.addMonitor(filter, null, true);
        screen.onActivity(a -> {
            try {
                Method method;
                if (target == null) {
                    method = ArchiveActivity.class.getDeclaredMethod("export");
                    method.setAccessible(true);
                    method.invoke(a);
                } else {
                    method = ArchiveActivity.class.getDeclaredMethod("pickImport", String.class);
                    method.setAccessible(true);
                    method.invoke(a, target);
                }
                assertTrue((int) field(a, "pickerRequest") != 0);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        assertEquals(1, picker.getHits());
    }
    private void deliver(int request, File file) {
        Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".files", file);
        screen.onActivity(a -> a.onActivityResult(request, Activity.RESULT_OK, new Intent().setData(uri)));
    }
    @Test public void returningFromCreatePickerAfterRecreationExportsTheReviewedArchive() throws Exception {
        File result = new File(work, "result.zip");
        Files.createFile(result.toPath());
        File[] oldWorkspace = {null};
        screen.onActivity(a -> oldWorkspace[0] = ((File) field(a, "archive")).getParentFile());
        choose(Intent.ACTION_CREATE_DOCUMENT, null);
        screen.recreate();
        deliver(302, result);
        await(() -> result.length() == archive.length());
        assertArrayEquals(Files.readAllBytes(archive.toPath()), Files.readAllBytes(result.toPath()));
        await(() -> !oldWorkspace[0].exists());
    }
    @Test public void returningFromReplacePickerAfterRecreationKeepsNestedTarget() throws Exception {
        File input = new File(work, "new-name.txt");
        Files.write(input.toPath(), "updated".getBytes(StandardCharsets.UTF_8));
        choose(Intent.ACTION_OPEN_DOCUMENT, "sub/old.txt");
        screen.recreate();
        deliver(301, input);
        await(() -> {
            try (ZipFile zip = new ZipFile(archive)) {
                return zip.size() == 1 && zip.getEntry("sub/old.txt").getSize() == input.length();
            } catch (IOException e) { return false; }
        });
        assertArrayEquals(Files.readAllBytes(input.toPath()), ArchiveOps.bytes(archive, "sub/old.txt", 100));
    }
    @Test public void structuredEntryOpensFromSourceWithoutAnExtraArchiveCopy() throws Exception {
        File apk = new File(work, "fixture.apk");
        try (var bytes = instrumentation.getContext().getAssets().open("install-fixture.apk")) {
            Files.copy(bytes, apk.toPath());
        }
        Intent intent = new Intent(app, EditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        FileStore.local(apk).put(intent);
        intent.putExtra("archiveEntry", "AndroidManifest.xml").putExtra("mode", "xml");
        try (ActivityScenario<EditorActivity> editor = ActivityScenario.launch(intent)) {
            await(() -> {
                boolean[] loaded = {false};
                editor.onActivity(a -> {
                    try {
                        Field flag = EditorActivity.class.getDeclaredField("loaded"); flag.setAccessible(true);
                        if (!flag.getBoolean(a)) return;
                        Field source = EditorActivity.class.getDeclaredField("archive"); source.setAccessible(true);
                        File privateCopy = (File) source.get(a);
                        assertNotEquals(apk, privateCopy);
                        assertTrue(privateCopy.isFile());
                        assertEquals(apk.length(), privateCopy.length());
                        loaded[0] = true;
                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                });
                return loaded[0];
            });
        }
    }
    @Test public void closingWorkspaceWaitsForSubmittedJobsAndKeepsOtherScreens() throws Exception {
        ArchiveWorkspace workspace = new ArchiveWorkspace();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        File[] held = {null};
        workspace.execute(() -> {
            try {
                held[0] = workspace.create(app.getCacheDir());
                started.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (Exception e) { throw new AssertionError(e); }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            workspace.close();
            assertTrue(held[0].isDirectory());
        } finally { release.countDown(); workspace.close(); }
        await(() -> !held[0].exists());
        screen.onActivity(a -> assertTrue(((File) field(a, "archive")).isFile()));
    }
}
