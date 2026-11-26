package org.example.filemanager;

import android.content.*;
import android.graphics.*;
import android.os.Environment;
import android.os.SystemClock;
import android.view.*;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PreviewSettingsStateInstrumentedTest {
    private Context app;
    private File work;
    private ActivityScenario<PreviewActivity> preview;
    @Before public void setup() throws Exception {
        app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(app.getCacheDir().toPath(), "preview-settings-test-").toFile();
    }
    @After public void cleanup() throws Exception {
        if (preview != null) preview.close();
        if (work != null) new FileStore(app).delete(FileStore.local(work));
    }
    @Test public void filteredImagePagingKeepsItsListAndPositionAfterRecreation() throws Exception {
        File first = image("keep-1.png", Color.RED);
        image("keep-2.png", Color.BLUE);
        image("skip.png", Color.GREEN);
        Intent intent = new Intent(app, PreviewActivity.class);
        FileStore.local(first).put(intent);
        intent.putExtra("kind", "image").putExtra("imageFilter", "keep")
                .putExtra("imageParentKey", FileStore.local(work).key());
        preview = ActivityScenario.launch(intent);
        awaitGallery(List.of("keep-1.png", "keep-2.png"), 0, Color.RED);
        preview.onActivity(a -> ((TextView) field(a, "next")).performClick());
        awaitGallery(List.of("keep-1.png", "keep-2.png"), 1, Color.BLUE);
        preview.recreate();
        awaitGallery(List.of("keep-1.png", "keep-2.png"), 1, Color.BLUE);
    }
    @Test public void archiveImagePagingHonorsTheExplicitRegexFilter() throws Exception {
        File first = image("keep-1.png", Color.RED);
        File second = image("keep-2.png", Color.BLUE);
        File skipped = image("skip.png", Color.GREEN);
        File archive = new File(work, "photos.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            for (File file : List.of(first, second, skipped)) {
                output.putNextEntry(new ZipEntry("nested/" + file.getName()));
                Files.copy(file.toPath(), output); output.closeEntry();
            }
        }
        FileStore.Node container = FileStore.local(archive);
        Intent intent = new Intent(app, PreviewActivity.class);
        FileStore.archiveNode(container, "nested/keep-1.png", false, first.length(), 0).put(intent);
        intent.putExtra("kind", "image").putExtra("imageFilter", "^keep-[12]\\.png$").putExtra("imageRegex", true)
                .putExtra("imageParentKey", FileStore.archiveNode(container, "nested/", true, 0, 0).key());
        preview = ActivityScenario.launch(intent);
        awaitGallery(List.of("keep-1.png", "keep-2.png"), 0, Color.RED);
        preview.onActivity(a -> ((TextView) field(a, "next")).performClick());
        awaitGallery(List.of("keep-1.png", "keep-2.png"), 1, Color.BLUE);
    }
    @Test public void restoringDefaultHomeReplacesBothCustomPathsAndSurvivesRecreation() throws Exception {
        SharedPreferences prefs = app.getSharedPreferences("MainActivity", 0);
        String left = prefs.getString("home-left", null), right = prefs.getString("home-right", null);
        prefs.edit().putString("home-left", "/data/adb").putString("home-right", "/system").commit();
        try (var screen = ActivityScenario.launch(SettingsActivity.class)) {
            screen.onActivity(a -> {
                required(a.getWindow().getDecorView(), "设置分类：启动与权限").performClick();
                required(a.getWindow().getDecorView(), "恢复默认首页，左右窗口回到内部存储").performClick();
            });
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            UiObject2 accept = device.wait(Until.findObject(By.text("恢复")), 5000);
            assertNotNull(accept); accept.click();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            String home = Environment.getExternalStorageDirectory().getAbsolutePath();
            assertEquals(home, prefs.getString("home-left", null));
            assertEquals(home, prefs.getString("home-right", null));
            screen.recreate();
            assertEquals(home, prefs.getString("home-left", null));
            assertEquals(home, prefs.getString("home-right", null));
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (left == null) edit.remove("home-left"); else edit.putString("home-left", left);
            if (right == null) edit.remove("home-right"); else edit.putString("home-right", right);
            edit.commit();
        }
    }
    private File image(String name, int color) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color);
        File file = new File(work, name);
        try (OutputStream output = Files.newOutputStream(file.toPath())) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
        return file;
    }
    private void awaitGallery(List<String> names, int index, int color) {
        long deadline = SystemClock.uptimeMillis() + 12000;
        boolean[] ready = {false};
        do {
            preview.onActivity(a -> {
                List<FileStore.Node> pictures = (List<FileStore.Node>) field(a, "pictures");
                Bitmap bitmap = (Bitmap) field(a, "bitmap");
                ready[0] = !(boolean) field(a, "galleryLoading") && names.equals(pictures.stream().map(n -> n.name).toList())
                        && (int) field(a, "pictureIndex") == index && bitmap != null && bitmap.getPixel(0, 0) == color;
            });
            if (!ready[0]) SystemClock.sleep(20);
        } while (!ready[0] && SystemClock.uptimeMillis() < deadline);
        assertTrue("Filtered gallery or selected picture was not restored", ready[0]);
    }
    private static Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static View required(View root, String description) {
        View found = find(root, description); assertNotNull(description, found); return found;
    }
    private static View find(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = find(group.getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
}
