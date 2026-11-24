package org.example.filemanager;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.AnimatedImageDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;
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
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RemainingOptimizationsInstrumentedTest {
    private Context context;
    private File work;
    private UiDevice device;
    private ActivityScenario<?> screen;
    private Map<String, ?> oldPrefs;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        oldPrefs = new HashMap<>(context.getSharedPreferences("MainActivity", 0).getAll());
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        context.getSharedPreferences("PreviewActivity", 0).edit().clear().commit();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "remaining-opt-").toFile();
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        var edit = context.getSharedPreferences("MainActivity", 0).edit().clear();
        for (var entry : oldPrefs.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) edit.putString(entry.getKey(), text);
            else if (value instanceof Boolean flag) edit.putBoolean(entry.getKey(), flag);
            else if (value instanceof Integer number) edit.putInt(entry.getKey(), number);
            else if (value instanceof Long number) edit.putLong(entry.getKey(), number);
        }
        edit.commit(); context.getSharedPreferences("PreviewActivity", 0).edit().clear().commit();
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void invalidRegexStaysOpenAndValidModeSurvivesRecreationAndRelaunch() throws Exception {
        Files.write(new File(work, "one.txt").toPath(), new byte[]{1});
        Files.write(new File(work, "two.bin").toPath(), new byte[]{2});
        ActivityScenario<MainActivity> main = openMain();
        main.onActivity(a -> call(a, "filterDialog"));
        UiObject2 input = object(By.clazz(EditText.class)); input.setText("[");
        object(By.text("正则表达式")).click(); object(By.text("应用")).click();
        assertTrue(device.hasObject(By.text("当前目录过滤")));
        main.onActivity(a -> assertEquals("", field(field(a, "left"), "filter")));
        input.setText(".*\\.txt$"); object(By.text("应用")).click();
        waitEntries(main, 1);
        main.recreate(); waitEntries(main, 1);
        main.onActivity(a -> {
            assertEquals(true, field(field(a, "left"), "regex"));
            assertEquals(".*\\.txt$", field(field(a, "left"), "filter"));
        });
        main.close(); screen = null;
        main = ActivityScenario.launch(MainActivity.class); screen = main; waitEntries(main, 1);
        main.onActivity(a -> assertEquals(true, field(field(a, "left"), "regex")));
    }
    @Test public void cancelledComplexRegexCannotBlockLatestDirectoryFilter() throws Exception {
        Files.write(new File(work, "a".repeat(200) + "!").toPath(), new byte[]{1});
        Files.write(new File(work, "wanted.txt").toPath(), new byte[]{2});
        ActivityScenario<MainActivity> main = openMain(); waitEntries(main, 2);
        main.onActivity(a -> {
            Object pane = field(a, "left"); set(pane, "regex", true); set(pane, "filter", "(a+)+$"); call(pane, "apply");
        });
        SystemClock.sleep(100);
        main.onActivity(a -> {
            Object pane = field(a, "left"); set(pane, "filter", "^wanted"); call(pane, "apply");
        });
        waitEntries(main, 1);
        main.onActivity(a -> assertEquals("wanted.txt", ((FileStore.Node)((List<?>)field(field(a, "left"), "entries")).get(0)).name));
    }
    @Test public void thumbnailsSelectImageAndSingleTapHidesAndRestoresControls() throws Exception {
        image("01.png", Color.RED); image("02.png", Color.BLUE); image("03.png", Color.GREEN);
        Intent intent = new Intent(context, PreviewActivity.class); FileStore.local(new File(work, "01.png")).put(intent);
        intent.putExtra("kind", "image");
        ActivityScenario<PreviewActivity> preview = ActivityScenario.launch(intent); screen = preview;
        awaitText("1 / 3");
        object(By.desc("切换图片缩略图")).click();
        object(By.desc("图片缩略图 2 02.png")).click(); awaitText("2 / 3");
        preview.onActivity(a -> {
            assertEquals("02.png", ((TextView)field(a, "title")).getText().toString());
            call(a, "setPictureFullscreen", boolean.class, true);
            assertEquals(View.GONE, ((View)field(a, "imageControls")).getVisibility());
        });
        preview.onActivity(a -> a.getOnBackPressedDispatcher().onBackPressed());
        preview.onActivity(a -> assertEquals(View.VISIBLE, ((View)field(a, "imageControls")).getVisibility()));
        UiObject2 view = object(By.desc("图片预览")); view.click(); SystemClock.sleep(450);
        preview.onActivity(a -> assertEquals(View.GONE, ((View)field(a, "imageControls")).getVisibility()));
        object(By.desc("图片预览")).click(); SystemClock.sleep(450);
        preview.onActivity(a -> assertEquals(View.VISIBLE, ((View)field(a, "imageControls")).getVisibility()));
    }
    @Test public void interruptedStreamStopsBeforeWritingAndCleanupStillRuns() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(), cleanup = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try { FileStore.pump(new ByteArrayInputStream(new byte[262144]), new ByteArrayOutputStream()); }
            catch (Tasks.CancelledException expected) { cancelled.set(true); }
            catch (IOException error) { throw new AssertionError(error); }
            try { Tasks.Progress.cleanup(() -> { Tasks.Progress.check(); cleanup.set(true); }); }
            catch (IOException error) { throw new AssertionError(error); }
            assertTrue(Thread.currentThread().isInterrupted());
        });
        worker.start(); worker.join(3000); assertFalse(worker.isAlive()); assertTrue(cancelled.get()); assertTrue(cleanup.get());
    }
    @Test public void animatedGifInArchiveKeepsItsBackingFileUntilNextImage() throws Exception {
        byte[] frame = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", 0);
        frame[23] = 10;
        ByteArrayOutputStream animated = new ByteArrayOutputStream();
        animated.write(frame, 0, 19);
        animated.write(new byte[]{0x21, (byte)0xff, 0x0b});
        animated.write("NETSCAPE2.0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        animated.write(new byte[]{3, 1, 0, 0, 0});
        animated.write(frame, 19, frame.length - 20); animated.write(frame, 19, frame.length - 20); animated.write(0x3b);
        image("02.png", Color.BLUE); File archive = new File(work, "pictures.zip");
        zip(archive, Map.of("01.gif", animated.toByteArray(), "02.png", Files.readAllBytes(new File(work, "02.png").toPath())));
        Intent intent = new Intent(context, PreviewActivity.class);
        FileStore.archiveNode(FileStore.local(archive), "01.gif", false, animated.size(), 0).put(intent); intent.putExtra("kind", "image");
        ActivityScenario<PreviewActivity> preview = ActivityScenario.launch(intent); screen = preview;
        assertTrue(device.wait(Until.hasObject(By.textContains("动图")), 10000));
        File[] backing = {null};
        preview.onActivity(a -> {
            assertTrue(((ZoomImageView)field(a, "imageView")).getDrawable() instanceof android.graphics.drawable.Animatable);
            assertTrue(((android.graphics.drawable.Animatable)((ZoomImageView)field(a, "imageView")).getDrawable()).isRunning());
            backing[0] = (File)field(a, "animationFile"); assertNotNull(backing[0]); assertTrue(backing[0].isFile());
        });
        object(By.desc("图片下一张")).click(); awaitText("2 / 2");
        long until = SystemClock.uptimeMillis() + 5000;
        while (backing[0].exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30);
        assertFalse(backing[0].exists());
    }
    @Test public void avifAndAnimatedWebpDecodeThroughNormalPreview() throws Exception {
        Context instrumentation = InstrumentationRegistry.getInstrumentation().getContext();
        for (String name : new String[]{"red.avif", "animated.webp"})
            try (InputStream input = instrumentation.getAssets().open("image-fixtures/" + name)) {
                Files.copy(input, new File(work, name).toPath());
            }
        Intent intent = new Intent(context, PreviewActivity.class); FileStore.local(new File(work, "red.avif")).put(intent);
        intent.putExtra("kind", "image");
        ActivityScenario<PreviewActivity> preview = ActivityScenario.launch(intent); screen = preview;
        assertTrue(device.wait(Until.hasObject(By.textContains("16 × 16")), 10000));
        preview.onActivity(a -> assertNotNull(field(a, "bitmap")));
        object(By.desc("图片上一张")).click();
        assertTrue(device.wait(Until.hasObject(By.textContains("动图")), 10000));
        preview.onActivity(a -> assertTrue(((ZoomImageView)field(a, "imageView")).getDrawable() instanceof AnimatedImageDrawable));
    }
    @Test public void largerSystemFontKeepsMainPathAndImageControlsVisible() throws Exception {
        String previousScale = device.executeShellCommand("settings get system font_scale").trim();
        if (previousScale.isEmpty() || previousScale.equals("null")) previousScale = "1.0";
        try {
            device.executeShellCommand("settings put system font_scale 1.6"); SystemClock.sleep(700);
            image("01.png", Color.RED);
            ActivityScenario<MainActivity> main = openMain(); waitEntries(main, 1);
            main.onActivity(a -> {
                assertTrue(a.getResources().getConfiguration().fontScale >= 1.5f);
                TextView title = (TextView)field(a, "title");
                assertTrue("Main path text must fit its row", title.getHeight() >= title.getLineHeight());
            });
            main.close(); screen = null;
            Intent intent = new Intent(context, PreviewActivity.class); FileStore.local(new File(work, "01.png")).put(intent);
            intent.putExtra("kind", "image");
            ActivityScenario<PreviewActivity> preview = ActivityScenario.launch(intent); screen = preview;
            assertTrue(device.wait(Until.hasObject(By.textContains("80 × 60")), 10000));
            preview.onActivity(a -> {
                LinearLayout controls = (LinearLayout)field(a, "imageControls");
                for (int i = 0; i < controls.getChildCount(); i++) {
                    TextView control = (TextView)controls.getChildAt(i);
                    assertTrue(control.getHeight() >= control.getLineHeight());
                    assertTrue(control.getHeight() >= Ui.dp(a, 48));
                }
            });
        } finally {
            if (screen != null) { screen.close(); screen = null; }
            device.executeShellCommand("settings put system font_scale " + previousScale);
            SystemClock.sleep(500);
        }
    }
    @Test public void providerWithoutChangeNotificationsRefreshesOpenDirectory() throws Exception {
        android.net.Uri root = grantDocuments(); seed("first.txt", new byte[]{1});
        context.getSharedPreferences("MainActivity", 0).edit().putString("left", root.toString()).putString("right", work.getPath()).commit();
        ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class); screen = main; waitEntries(main, 1);
        SystemClock.sleep(1500); seed("second.txt", new byte[]{2}); waitEntries(main, 2);
        main.onActivity(a -> assertEquals(root, ((FileStore.Node)field(field(a, "left"), "location")).uri));
    }
    @Test public void providerArchivePreviewReusesSnapshotAndReleasesItOnClose() throws Exception {
        android.net.Uri root = grantDocuments(); image("01.png", Color.RED); image("02.png", Color.BLUE);
        File archive = new File(work, "provider.zip");
        zip(archive, Map.of("01.png", Files.readAllBytes(new File(work, "01.png").toPath()),
                "02.png", Files.readAllBytes(new File(work, "02.png").toPath())));
        seed("pictures.zip", Files.readAllBytes(archive.toPath()));
        FileStore store = new FileStore(context); FileStore.Node source = store.list(store.document(root)).get(0);
        Intent intent = new Intent(context, PreviewActivity.class);
        FileStore.archiveNode(source, "01.png", false, 0, 0).put(intent); intent.putExtra("kind", "image");
        ActivityScenario<PreviewActivity> preview = ActivityScenario.launch(intent); screen = preview;
        assertTrue(device.wait(Until.hasObject(By.textContains("80 × 60")), 10000));
        Object[] snapshot = {null};
        preview.onActivity(a -> { snapshot[0] = field(a, "gallerySnapshot"); assertNotNull(snapshot[0]); });
        object(By.desc("图片下一张")).click();
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("2 / 2 · 80")), 10000));
        preview.onActivity(a -> assertSame(snapshot[0], field(a, "gallerySnapshot")));
        File backing = (File)field(snapshot[0], "copy"); assertTrue(backing.isFile());
        preview.close(); screen = null;
        long until = SystemClock.uptimeMillis() + 5000;
        while (backing.exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30);
        assertFalse("Preview snapshot must be released", backing.exists());
    }
    @Test public void nestedArchiveMoveKeepsOuterRevisionAndOtherEntries() throws Exception {
        File inner = new File(work, "inner.zip");
        zip(inner, Map.of("small.txt", "small".getBytes(), "keep.txt", "keep".getBytes()));
        File outer = new File(work, "outer.zip");
        zip(outer, Map.of("inner.zip", Files.readAllBytes(inner.toPath()), "outer-keep", new byte[]{7}));
        File target = new File(work, "target"); assertTrue(target.mkdir());
        FileStore store = new FileStore(context);
        FileStore.Node nested = FileStore.archiveNode(FileStore.local(outer), "inner.zip", false, inner.length(), 0);
        FileStore.Node source = FileStore.archiveNode(nested, "small.txt", false, 5, 0);
        store.copyAll(List.of(source), FileStore.directory(target, false), true);
        assertEquals("small", new String(Files.readAllBytes(new File(target, "small.txt").toPath()), java.nio.charset.StandardCharsets.UTF_8));
        try (InputStream input = store.read(nested)) { Files.copy(input, new File(work, "readback.zip").toPath()); }
        assertEquals(List.of("keep.txt"), ArchiveOps.entryNames(new File(work, "readback.zip")));
        assertTrue(ArchiveOps.entryNames(outer).contains("outer-keep"));
    }
    @Test public void moving32KbFrom40MbZipReportsBytesAndPreservesCompressedData() throws Exception {
        File archive = new File(work, "large.zip"), target = new File(work, "moved"); assertTrue(target.mkdir());
        byte[] chunk = new byte[1024 * 1024], small = new byte[32768]; new Random(29).nextBytes(chunk); new Random(11).nextBytes(small);
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
            out.setLevel(0); out.putNextEntry(new ZipEntry("large.bin"));
            for (int i = 0; i < 40; i++) out.write(chunk);
            out.closeEntry(); out.putNextEntry(new ZipEntry("small.bin")); out.write(small); out.closeEntry();
        }
        FileStore store = new FileStore(context); Tasks.Progress progress = new Tasks.Progress();
        long started = SystemClock.elapsedRealtime();
        progress.run(() -> store.copyAll(List.of(FileStore.archiveNode(FileStore.local(archive), "small.bin", false, small.length, 0)),
                FileStore.directory(target, false), true));
        long elapsed = SystemClock.elapsedRealtime() - started;
        assertArrayEquals(small, Files.readAllBytes(new File(target, "small.bin").toPath()));
        try (ZipFile zip = new ZipFile(archive); InputStream in = zip.getInputStream(zip.getEntry("large.bin"))) {
            assertNull(zip.getEntry("small.bin"));
            assertEquals(40L * chunk.length, zip.getEntry("large.bin").getSize());
            byte[] actual = in.readNBytes(chunk.length); assertArrayEquals(chunk, actual);
        }
        assertTrue(progress.transferred.get() >= small.length);
        String report = "archiveBytes=" + archive.length() + " movedBytes=" + small.length + " elapsedMs=" + elapsed
                + " processedBytes=" + progress.transferred.get();
        android.util.Log.i("RemainingOptimizationTiming", report);
        Files.write(new File(context.getExternalFilesDir(null), "archive-move-0632.txt").toPath(), report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private ActivityScenario<MainActivity> openMain() {
        context.getSharedPreferences("MainActivity", 0).edit().putString("left", work.getPath()).putString("right", work.getPath()).commit();
        ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class); screen = main; return main;
    }
    private android.net.Uri grantDocuments() throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        android.os.Bundle[] result = {null};
        Intent intent = new Intent(CountingDocumentsProvider.AUTHORITY + ".GRANT")
                .setComponent(new ComponentName(InstrumentationRegistry.getInstrumentation().getContext().getPackageName(),
                        CountingDocumentsProvider.GrantReceiver.class.getName())).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent received) { result[0] = getResultExtras(false); done.countDown(); }
        }, null, android.app.Activity.RESULT_CANCELED, null, null);
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS)); assertNotNull(result[0]);
        assertNotNull(result[0].getString("uri")); return android.net.Uri.parse(result[0].getString("uri"));
    }
    private void seed(String name, byte[] bytes) {
        android.os.Bundle values = new android.os.Bundle(); values.putString("name", name); values.putByteArray("bytes", bytes);
        context.getContentResolver().call(android.net.Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY), "seed", null, values);
    }
    private void waitEntries(ActivityScenario<MainActivity> main, int count) {
        boolean[] ready = {false}; long until = SystemClock.uptimeMillis() + 10000;
        while (!ready[0] && SystemClock.uptimeMillis() < until) {
            main.onActivity(a -> ready[0] = ((List<?>)field(field(a, "left"), "entries")).size() == count
                    && ((ListView)field(field(a, "left"), "list")).isEnabled());
            if (!ready[0]) SystemClock.sleep(30);
        }
        assertTrue("Expected " + count + " visible entries", ready[0]);
    }
    private void awaitText(String text) { assertTrue(device.wait(Until.hasObject(By.textStartsWith(text)), 10000)); }
    private UiObject2 object(BySelector selector) {
        UiObject2 found = device.wait(Until.findObject(selector), 10000); assertNotNull(selector.toString(), found); return found;
    }
    private void image(String name, int color) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color);
        try (OutputStream out = new FileOutputStream(new File(work, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
    }
    private void zip(File file, Map<String, byte[]> entries) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (var entry : entries.entrySet()) { out.putNextEntry(new ZipEntry(entry.getKey())); out.write(entry.getValue()); out.closeEntry(); }
        }
    }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void call(Object target, String name) {
        try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void call(Object target, String name, Class<?> type, Object value) {
        try { Method m = target.getClass().getDeclaredMethod(name, type); m.setAccessible(true); m.invoke(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
