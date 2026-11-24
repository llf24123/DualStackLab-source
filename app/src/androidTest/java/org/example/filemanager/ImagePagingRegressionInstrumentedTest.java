package org.example.filemanager;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImagePagingRegressionInstrumentedTest {
    private Context context;
    private File work;
    private ActivityScenario<PreviewActivity> screen;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "paging-regression-").toFile();
        context.getSharedPreferences("PreviewActivity", 0).edit().putBoolean("thumbnails", false).commit();
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void corruptPictureClearsPreviousImageAndStillAllowsNextPage() throws Exception {
        File first = image("01.png", 80, 60, Color.RED);
        Files.write(new File(work, "02.png").toPath(), new byte[]{1, 2, 3});
        image("03.png", 80, 60, Color.BLUE);
        launch(FileStore.local(first)); idle();
        long deadline = SystemClock.uptimeMillis() + 5000;
        boolean[] listed = {false};
        while (!listed[0] && SystemClock.uptimeMillis() < deadline) {
            screen.onActivity(a -> listed[0] = ((List<?>)field(a, "pictures")).size() == 3);
            if (!listed[0]) SystemClock.sleep(20);
        }
        assertTrue("Gallery not loaded", listed[0]);
        screen.onActivity(a -> call(a, "showPicture", new Class<?>[]{int.class}, 1));
        idle();
        screen.onActivity(a -> {
            assertEquals(1, field(a, "pictureIndex"));
            assertTrue(((TextView)field(a, "status")).getText().toString().contains("无法预览"));
            assertNull(((ZoomImageView)field(a, "imageView")).getDrawable());
            assertNull(field(a, "bitmap"));
            assertNull(field(a, "displayedPicture"));
            assertTrue(((TextView)field(a, "next")).isEnabled());
            call(a, "showPicture", new Class<?>[]{int.class}, 2);
        });
        awaitPicture(2); idle();
        screen.onActivity(a -> assertEquals(Color.BLUE, ((Bitmap)field(a, "bitmap")).getPixel(0, 0)));
    }
    @Test public void zoomLoadsDetailWithoutMovingViewportAndPagingReleasesIt() throws Exception {
        File first = image("01.jpg", 4000, 3000, Color.RED);
        image("02.jpg", 4000, 3000, Color.BLUE);
        launch(FileStore.local(first)); idle();
        int[] fittedWidth = {0}; RectF[] before = {null};
        screen.onActivity(a -> {
            fittedWidth[0] = ((Bitmap)field(a, "bitmap")).getWidth();
            ZoomImageView view = (ZoomImageView)field(a, "imageView");
            view.zoom(2.5f, view.getWidth() * .7f, view.getHeight() * .4f);
            before[0] = bounds(view);
        });
        idle(); Bitmap[] detail = {null};
        screen.onActivity(a -> {
            detail[0] = (Bitmap)field(a, "bitmap");
            assertTrue("Zoom should decode more pixels", detail[0].getWidth() > fittedWidth[0]);
            ZoomImageView view = (ZoomImageView)field(a, "imageView");
            assertEquals(2.5f, view.zoomFactor(), .01f);
            RectF after = bounds(view);
            assertEquals(before[0].left, after.left, 1f); assertEquals(before[0].top, after.top, 1f);
            assertEquals(before[0].right, after.right, 1f); assertEquals(before[0].bottom, after.bottom, 1f);
            call(a, "showPicture", new Class<?>[]{int.class}, 1);
        });
        awaitPicture(1); idle();
        assertTrue("Uncached detail should be released after leaving", detail[0].isRecycled());
        screen.onActivity(a -> assertBlue((Bitmap)field(a, "bitmap")));
    }
    @Test public void detailQueuedDuringRapidPagingCannotReplaceNextPicture() throws Exception {
        File first = image("01.jpg", 4000, 3000, Color.RED);
        image("02.jpg", 4000, 3000, Color.BLUE);
        launch(FileStore.local(first)); idle();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        worker().submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException ignored) { } });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            screen.onActivity(a -> {
                ZoomImageView view = (ZoomImageView)field(a, "imageView"); view.zoom(2f, 100, 100);
                call(a, "showPicture", new Class<?>[]{int.class}, 1);
            });
        } finally { release.countDown(); }
        awaitPicture(1); idle();
        screen.onActivity(a -> {
            assertBlue((Bitmap)field(a, "bitmap"));
            assertEquals(1f, ((ZoomImageView)field(a, "imageView")).zoomFactor(), .01f);
        });
    }
    @Test public void pagingPreservesRequestedInFlightPrefetch() throws Exception {
        File first = image("01.jpg", 800, 600, Color.RED);
        image("02.jpg", 800, 600, Color.BLUE); image("03.jpg", 800, 600, Color.GREEN);
        launch(FileStore.local(first)); idle();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?>[] pending = {null}; Bitmap[] prefetched = {null};
        screen.onActivity(a -> {
            List<FileStore.Node> nodes = (List<FileStore.Node>)field(a, "pictures");
            FileStore.Node requested = nodes.get(2); int token = (int)field(a, "pictureGeneration");
            pending[0] = ((ExecutorService)field(a, "pictureWorker")).submit(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                call(a, "prefetchPicture", new Class<?>[]{int.class, FileStore.Node.class}, token, requested);
                Object cached = ((Map<?, ?>)field(a, "pictureCache")).get(requested.key());
                if (cached != null) prefetched[0] = (Bitmap)field(field(cached, "picture"), "bitmap");
            });
            ((Map<String, Future<?>>)field(a, "prefetchJobs")).put(requested.key(), pending[0]);
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            screen.onActivity(a -> call(a, "showPicture", new Class<?>[]{int.class}, 2));
            assertFalse("Requested prefetch must continue", pending[0].isCancelled());
        } finally { release.countDown(); }
        awaitPicture(2); idle();
        assertNotNull(prefetched[0]);
        screen.onActivity(a -> assertSame(prefetched[0], field(a, "bitmap")));
    }
    @Test public void providerArchivePagingDoesNotRereadWholeSourceAndRefreshesReplacement() throws Exception {
        Uri root = grantDocuments();
        File first = image("01.png", 80, 60, Color.RED), second = image("02.png", 80, 60, Color.BLUE);
        Bundle seed = new Bundle(); seed.putString("name", "photos.zip"); seed.putByteArray("bytes", zip(first, second));
        provider("seed", seed);
        FileStore store = new FileStore(context); FileStore.Node archive = store.list(store.document(root)).get(0);
        launch(FileStore.archiveNode(archive, "01.png", false, 0, 0)); idle();
        provider("clearCounters", null);
        for (int index : new int[]{1, 0, 1}) {
            screen.onActivity(a -> call(a, "showPicture", new Class<?>[]{int.class}, index)); awaitPicture(index); idle();
        }
        assertEquals("Paging must reuse the open archive", 0, provider("stats", null).getInt("readOpens"));
        Object[] originalSnapshot = {null};
        screen.onActivity(a -> originalSnapshot[0] = field(a, "gallerySnapshot"));
        context.getContentResolver().notifyChange(archive.uri, null);
        long observerDeadline = SystemClock.uptimeMillis() + 5000;
        long[] changes = {0};
        do {
            screen.onActivity(a -> changes[0] = ((java.util.concurrent.atomic.AtomicLong)field(a, "pictureSourceChanges")).get());
            if (changes[0] == 0) SystemClock.sleep(10);
        } while (changes[0] == 0 && SystemClock.uptimeMillis() < observerDeadline);
        assertTrue("Provider notifications should invalidate the preview source", changes[0] > 0);
        screen.onActivity(a -> call(a, "showPicture", new Class<?>[]{int.class}, 0)); awaitPicture(0); idle();
        screen.onActivity(a -> assertNotSame(originalSnapshot[0], field(a, "gallerySnapshot")));
        provider("clearCounters", null);
        first = image("01.png", 90, 70, Color.GREEN);
        try (OutputStream output = context.getContentResolver().openOutputStream(archive.uri, "wt")) { output.write(zip(first, second)); }
        screen.onActivity(a -> call(a, "showPicture", new Class<?>[]{int.class}, 0)); awaitPicture(0); idle();
        screen.onActivity(a -> {
            Bitmap displayed = (Bitmap)field(a, "bitmap"); assertEquals(Color.GREEN, displayed.getPixel(0, 0));
            assertEquals(90, displayed.getWidth());
        });
        assertTrue(provider("stats", null).getInt("readOpens") > 0);
    }
    @Test public void readableRootMarkedImageUsesOriginalFileAndKeepsItOnClose() throws Exception {
        File first = image("01.png", 80, 60, Color.RED); launch(FileStore.local(first)); idle();
        screen.onActivity(a -> assertEquals(first, call(a, "pictureFile", new Class<?>[]{FileStore.Node.class}, FileStore.local(first, true))));
        screen.close(); screen = null;
        assertTrue(first.isFile());
    }
    private File image(String name, int width, int height, int color) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color);
        File file = new File(work, name);
        try (OutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(name.endsWith(".png") ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 95, out));
        } finally { bitmap.recycle(); }
        return file;
    }
    private byte[] zip(File... files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (File file : files) { output.putNextEntry(new ZipEntry(file.getName())); Files.copy(file.toPath(), output); output.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private void launch(FileStore.Node source) {
        Intent intent = new Intent(context, PreviewActivity.class); source.put(intent); intent.putExtra("kind", "image");
        screen = ActivityScenario.launch(intent); awaitPicture(0);
    }
    private ExecutorService worker() {
        ExecutorService[] result = {null}; screen.onActivity(a -> result[0] = (ExecutorService)field(a, "pictureWorker")); return result[0];
    }
    private void idle() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        worker().submit(() -> {}).get(20, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void awaitPicture(int index) {
        boolean[] ready = {false}; long end = SystemClock.uptimeMillis() + 20000;
        while (!ready[0] && SystemClock.uptimeMillis() < end) {
            screen.onActivity(a -> {
                String status = ((TextView)field(a, "status")).getText().toString();
                ready[0] = status.startsWith((index + 1) + " / ") && !status.contains("读取中") && !status.contains("无法") && field(a, "bitmap") != null;
            });
            if (!ready[0]) SystemClock.sleep(10);
        }
        assertTrue("Picture did not finish loading", ready[0]);
    }
    private Uri grantDocuments() throws Exception {
        CountDownLatch done = new CountDownLatch(1); Bundle[] result = {null};
        Intent intent = new Intent(CountingDocumentsProvider.AUTHORITY + ".GRANT")
                .setComponent(new ComponentName(InstrumentationRegistry.getInstrumentation().getContext().getPackageName(), CountingDocumentsProvider.GrantReceiver.class.getName()))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { result[0] = getResultExtras(false); done.countDown(); }
        }, null, android.app.Activity.RESULT_CANCELED, null, null);
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertNotNull(result[0]);
        return Uri.parse(result[0].getString("uri"));
    }
    private Bundle provider(String method, Bundle arguments) {
        return context.getContentResolver().call(Uri.parse("content://" + CountingDocumentsProvider.AUTHORITY), method, null, arguments);
    }
    private static RectF bounds(ZoomImageView view) {
        RectF bounds = new RectF(0, 0, view.getDrawable().getIntrinsicWidth(), view.getDrawable().getIntrinsicHeight());
        view.getImageMatrix().mapRect(bounds); return bounds;
    }
    private static void assertBlue(Bitmap image) {
        int color = image.getPixel(0, 0);
        assertTrue("JPEG should still show the blue photo", Color.blue(color) >= 250 && Color.red(color) <= 5 && Color.green(color) <= 5);
    }
    private static Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
