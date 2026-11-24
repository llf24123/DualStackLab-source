package org.example.filemanager;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImageSwipeInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work, images, shared;
    private ActivityScenario<?> scenario;
    private SharedPreferences preferences;
    private String oldLeft, oldRight;
    private Boolean oldHidden;
    private static final String RED = "01-red.png", BLUE = "02-blue.JPG", GREEN = "03-green.webp";
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "image-swipe-").toFile();
        images = new File(work, "photos"); assertTrue(images.mkdir());
        image(new File(images, RED), Color.RED, Bitmap.CompressFormat.PNG);
        image(new File(images, BLUE), Color.BLUE, Bitmap.CompressFormat.JPEG);
        image(new File(images, GREEN), Color.GREEN, Bitmap.CompressFormat.WEBP_LOSSLESS);
        image(new File(images, ".hidden.png"), Color.BLACK, Bitmap.CompressFormat.PNG);
        File child = new File(images, "child"); assertTrue(child.mkdir()); image(new File(child, "nested.png"), Color.YELLOW, Bitmap.CompressFormat.PNG);
        Files.write(new File(images, "readme.txt").toPath(), new byte[]{1});
        preferences = context.getSharedPreferences("MainActivity", 0);
        oldLeft = preferences.getString("left", null); oldRight = preferences.getString("right", null);
        oldHidden = preferences.contains("showHidden") ? preferences.getBoolean("showHidden", true) : null;
    }
    @After public void cleanup() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)))
                if (activity instanceof PreviewActivity) activity.finish();
        });
        if (scenario != null) scenario.close();
        SharedPreferences.Editor e = preferences.edit();
        if (oldLeft == null) e.remove("left"); else e.putString("left", oldLeft);
        if (oldRight == null) e.remove("right"); else e.putString("right", oldRight);
        if (oldHidden == null) e.remove("showHidden"); else e.putBoolean("showHidden", oldHidden);
        e.commit();
        if (shared != null) Files.deleteIfExists(shared.toPath());
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void directoryClickSwipesBothWaysAndHonorsFirstAndLast() throws Exception {
        launchMain(FileStore.directory(images, false));
        await(BLUE, 2, 3, Color.BLUE); swipe(true); await(GREEN, 3, 3, Color.GREEN);
        assertFalse(device.findObject(By.desc("图片下一张")).isEnabled());
        swipe(true); await(GREEN, 3, 3, Color.GREEN);
        swipe(false); await(BLUE, 2, 3, Color.BLUE);
        device.findObject(By.desc("图片上一张")).click(); await(RED, 1, 3, Color.RED);
        assertFalse(device.findObject(By.desc("图片上一张")).isEnabled());
        for (String name : new String[]{RED, BLUE, GREEN}) assertTrue(new File(images, name).isFile());
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "image-directory-0630.png"));
    }
    @Test public void archiveClickStaysInCurrentFolderAndSwipes() throws Exception {
        File zip = archive(); byte[] before = Files.readAllBytes(zip.toPath());
        launchMain(FileStore.archiveNode(FileStore.local(zip), "photos/", true, 0, 0));
        await(BLUE, 2, 3, Color.BLUE); swipe(true); await(GREEN, 3, 3, Color.GREEN);
        swipe(false); await(BLUE, 2, 3, Color.BLUE);
        assertArrayEquals(before, Files.readAllBytes(zip.toPath()));
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "image-archive-0630.png"));
    }
    @Test public void directoryAcceptsShortDiagonalAndSlowSwipes() throws Exception {
        launch(FileStore.local(new File(images, RED)), 0); naturalSwipes();
    }
    @Test public void archiveAcceptsShortDiagonalAndSlowSwipes() throws Exception {
        launch(FileStore.archiveNode(FileStore.local(archive()), "photos/" + RED, false, 0, 0), 0); naturalSwipes();
    }
    private void naturalSwipes() {
        await(RED, 1, 3, Color.RED);
        swipeBy(-40, 0, 30); await(BLUE, 2, 3, Color.BLUE);
        swipeBy(-70, 50, 45); await(GREEN, 3, 3, Color.GREEN);
        swipeBy(100, 0, 320); await(BLUE, 2, 3, Color.BLUE);
        swipeBy(40, 0, 30); await(RED, 1, 3, Color.RED);
    }
    private void swipeBy(int dxDp, int dyDp, int steps) {
        UiObject2 image = device.findObject(By.desc("图片预览")); assertNotNull(image);
        Rect bounds = image.getVisibleBounds();
        assertTrue(device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX() + Ui.dp(context, dxDp),
                bounds.centerY() + Ui.dp(context, dyDp), steps));
        device.waitForIdle();
    }
    @Test public void archiveToolsImageClickAlsoSupportsPaging() throws Exception {
        Intent intent = new Intent(context, ArchiveActivity.class); FileStore.local(archive()).put(intent);
        scenario = ActivityScenario.launch(intent);
        UiObject2 folder = device.wait(Until.findObject(By.text("photos")), 10000); assertNotNull(folder); folder.click();
        UiObject2 item = device.wait(Until.findObject(By.text(BLUE)), 10000); assertNotNull(item); item.click();
        await(BLUE, 2, 3, Color.BLUE); swipe(true); await(GREEN, 3, 3, Color.GREEN);
    }
    @Test public void zoomedDragKeepsCurrentImageAndFitAllowsPagingAgain() throws Exception {
        launch(FileStore.local(new File(images, RED)), 0);
        await(RED, 1, 3, Color.RED);
        device.findObject(By.desc("图片放大")).click();
        swipe(true); await(RED, 1, 3, Color.RED);
        AtomicReference<Float> zoom = new AtomicReference<>();
        main(() -> zoom.set(((ZoomImageView) field(preview(), "imageView")).zoomFactor()));
        assertTrue(zoom.get() > 1.05f);
        device.findObject(By.desc("图片适屏")).click();
        swipe(true); await(BLUE, 2, 3, Color.BLUE);
    }
    @Test public void brokenImageCanBeSkippedWithoutLeavingPreview() throws Exception {
        String broken = "02-broken.png"; Files.write(new File(images, broken).toPath(), new byte[]{0,1,2});
        launch(FileStore.local(new File(images, broken)), 0);
        assertTrue(device.wait(Until.hasObject(By.textContains("无法预览")), 10000));
        swipe(true); await(GREEN, 4, 4, Color.GREEN);
    }
    @Test public void rapidArchivePagingAndRecreationKeepLatestImageAndReleaseImports() throws Exception {
        Set<String> imports = imports(); File zip = archive(); byte[] before = Files.readAllBytes(zip.toPath());
        launch(FileStore.archiveNode(FileStore.local(zip), "photos/" + RED, false, 0, 0), 0);
        await(RED, 1, 3, Color.RED);
        main(() -> {
            PreviewActivity activity = preview();
            for (int i = 0; i < 8; i++) {
                ((TextView) field(activity, "next")).performClick();
                ((TextView) field(activity, "previous")).performClick();
            }
            ((TextView) field(activity, "next")).performClick(); ((TextView) field(activity, "next")).performClick();
        });
        await(GREEN, 3, 3, Color.GREEN); scenario.recreate(); await(GREEN, 3, 3, Color.GREEN);
        scenario.close(); scenario = null;
        long until = SystemClock.uptimeMillis() + 5000;
        while (!imports.equals(imports()) && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        assertEquals(imports, imports()); assertArrayEquals(before, Files.readAllBytes(zip.toPath()));
    }
    @Test public void modifiedOrderAndHiddenPreferenceAreRespected() throws Exception {
        assertTrue(new File(images, RED).setLastModified(900000));
        assertTrue(new File(images, GREEN).setLastModified(500000));
        assertTrue(new File(images, BLUE).setLastModified(100000));
        launch(FileStore.local(new File(images, GREEN)), 1);
        await(GREEN, 2, 3, Color.GREEN); swipe(true); await(BLUE, 3, 3, Color.BLUE);
    }
    @Test public void exifRotationIsAppliedAndReportedDimensionsFollowTheDisplayedImage() throws Exception {
        File oriented = new File(images, "04-oriented.jpg"); image(oriented, Color.MAGENTA, Bitmap.CompressFormat.JPEG);
        androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(oriented.getAbsolutePath());
        exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                Integer.toString(androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90));
        exif.saveAttributes();
        launch(FileStore.local(oriented), 0); await(oriented.getName(), 4, 4, Color.MAGENTA);
        AtomicReference<String> status = new AtomicReference<>();
        main(() -> status.set(((TextView) field(preview(), "status")).getText().toString()));
        assertTrue(status.get(), status.get().contains("120 × 160"));
    }
    @Test public void standaloneContentUriStillDisplaysOneImage() throws Exception {
        File folder = new File(context.getCacheDir(), "shared"); assertTrue(folder.isDirectory() || folder.mkdir());
        shared = new File(folder, "image-swipe-" + UUID.randomUUID() + ".png"); Files.copy(new File(images, RED).toPath(), shared.toPath());
        FileStore.Node node = new FileStore(context).document(FileProvider.getUriForFile(context, context.getPackageName() + ".files", shared));
        launch(node, 0); await(shared.getName(), 1, 1, Color.RED);
        assertFalse(device.findObject(By.desc("图片下一张")).isEnabled()); swipe(true); await(shared.getName(), 1, 1, Color.RED);
    }
    @Test public void externalImageDoesNotMixInTheUnrelatedDirectoryImages() throws Exception {
        preferences.edit().putString("left", images.getPath()).putString("right", images.getPath()).putBoolean("showHidden", false).commit();
        Intent intent = new Intent(context, MainActivity.class).setAction(Intent.ACTION_VIEW)
                .setDataAndType(android.net.Uri.parse("content://" + ExternalFixtureProvider.AUTHORITY + "/image"), "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        scenario = ActivityScenario.launch(intent); await("provider.png", 1, 1, Color.RED);
    }
    private void launchMain(FileStore.Node parent) {
        preferences.edit().putString("left", parent.key()).putString("right", work.getPath()).putBoolean("showHidden", false).commit();
        scenario = ActivityScenario.launch(MainActivity.class);
        UiObject2 item = device.wait(Until.findObject(By.text(BLUE)), 10000); assertNotNull(item); item.click();
    }
    private void launch(FileStore.Node node, int sort) {
        Intent intent = new Intent(context, PreviewActivity.class); node.put(intent);
        intent.putExtra("kind", "image").putExtra("imageSort", sort).putExtra("imageShowHidden", false);
        scenario = ActivityScenario.launch(intent);
    }
    private void await(String name, int index, int total, int color) {
        long until = SystemClock.uptimeMillis() + 10000;
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        do {
            main(() -> {
                PreviewActivity activity = preview(); if (activity == null) return;
                Bitmap bitmap = (Bitmap) field(activity, "bitmap");
                String title = ((TextView) field(activity, "title")).getText().toString();
                String status = ((TextView) field(activity, "status")).getText().toString();
                if (bitmap == null || bitmap.isRecycled()) return;
                int actual = bitmap.getPixel(bitmap.getWidth()/2, bitmap.getHeight()/2);
                boolean sameColor = Math.abs(Color.red(color)-Color.red(actual)) < 5 && Math.abs(Color.green(color)-Color.green(actual)) < 5 && Math.abs(Color.blue(color)-Color.blue(actual)) < 5;
                ready.set(title.equals(name) && status.startsWith(index + " / " + total + " · ") && !status.contains("读取") && sameColor);
            });
            if (ready.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < until);
        fail("Preview did not show " + name + " at " + index + "/" + total);
    }
    private void swipe(boolean next) {
        UiObject2 view = device.findObject(By.desc("图片预览")); assertNotNull(view);
        Rect r = view.getVisibleBounds(); int y = r.centerY();
        device.swipe(next ? r.left + r.width()*4/5 : r.left + r.width()/5, y,
                next ? r.left + r.width()/5 : r.left + r.width()*4/5, y, 20);
        device.waitForIdle();
    }
    private static PreviewActivity preview() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (activity instanceof PreviewActivity) return (PreviewActivity) activity;
        return null;
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private Set<String> imports() { return new HashSet<>(Arrays.asList(context.getCacheDir().list((d,n) -> n.startsWith("import-")))); }
    private File archive() throws Exception {
        File zip = new File(work, "images.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String name : new String[]{RED, BLUE, GREEN}) {
                out.putNextEntry(new ZipEntry("photos/" + name)); out.write(Files.readAllBytes(new File(images, name).toPath())); out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("photos/child/nested.png")); out.write(Files.readAllBytes(new File(images, RED).toPath())); out.closeEntry();
            out.putNextEntry(new ZipEntry("other.png")); out.write(Files.readAllBytes(new File(images, BLUE).toPath())); out.closeEntry();
        }
        return zip;
    }
    private static void image(File file, int color, Bitmap.CompressFormat format) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color);
        try (OutputStream out = new FileOutputStream(file)) { assertTrue(bitmap.compress(format, 100, out)); }
        finally { bitmap.recycle(); }
    }
}
