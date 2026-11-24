package org.example.filemanager;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.widget.VideoView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.Lifecycle;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MediaPlaybackInstrumentedTest {
    // Check both decoder output and the composed display: an opaque View background can hide a valid surface.
    @Test public void mp4PreviewRendersMovingFrames() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File video = File.createTempFile("preview-", ".mp4", app.getCacheDir());
        try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("video-motion.mp4");
             var output = new FileOutputStream(video)) { FileStore.pump(input, output); }
        try { assertPlayback(FileStore.local(video)); } finally { video.delete(); }
    }
    @Test public void mp4InsideZipRendersMovingFrames() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File archive = File.createTempFile("preview-", ".zip", app.getCacheDir());
        try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("video-motion.mp4");
             var output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("nested/video.mp4")); FileStore.pump(input, output); output.closeEntry();
        }
        try { assertPlayback(FileStore.archiveNode(FileStore.local(archive), "nested/video.mp4", false, 0, 0)); }
        finally { archive.delete(); }
    }
    private static void assertPlayback(FileStore.Node source) throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        Intent intent = new Intent(app, PreviewActivity.class).putExtra("kind", "video");
        source.put(intent);
        try (ActivityScenario<PreviewActivity> scenario = ActivityScenario.launch(intent)) {
            long deadline = SystemClock.uptimeMillis() + 10000;
            boolean started = false;
            while (SystemClock.uptimeMillis() < deadline && !started) {
                AtomicBoolean ready = new AtomicBoolean();
                scenario.onActivity(activity -> {
                    try {
                        var field = PreviewActivity.class.getDeclaredField("player"); field.setAccessible(true);
                        VideoView view = (VideoView) field.get(activity);
                        ready.set(view != null && view.isPlaying() && view.getCurrentPosition() >= 400);
                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                });
                started = ready.get(); if (!started) Thread.sleep(50);
            }
            assertTrue("MP4 playback did not start", started);
            Bitmap decoded = captureSurface(scenario);
            try { assertTrue("MP4 decoder surface is black", visible(decoded)); } finally { decoded.recycle(); }
            Bitmap first = capture(scenario); Thread.sleep(450); Bitmap second = capture(scenario);
            try {
                try (var output = new FileOutputStream(new File(app.getExternalFilesDir(null), "mp4-preview-frame.png"))) {
                    first.compress(Bitmap.CompressFormat.PNG, 100, output);
                }
                assertTrue("MP4 composed display is black", visible(first));
                assertTrue("MP4 playback frame did not advance", differs(first, second));
            } finally { first.recycle(); second.recycle(); }
            int pausedAt = position(scenario);
            scenario.moveToState(Lifecycle.State.STARTED);
            Thread.sleep(300);
            scenario.moveToState(Lifecycle.State.RESUMED);
            long resumeDeadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < resumeDeadline && position(scenario) <= pausedAt) Thread.sleep(50);
            assertTrue("MP4 stayed paused after returning to the preview", position(scenario) > pausedAt);
            scenario.onActivity(activity -> player(activity).pause());
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertPreparedAndPaused(scenario);
            scenario.recreate();
            assertPreparedAndPaused(scenario);
        }
    }
    private static Bitmap capture(ActivityScenario<PreviewActivity> scenario) throws Exception {
        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        var window = automation.getRootInActiveWindow();
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (window == null && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(50); window = automation.getRootInActiveWindow();
        }
        assertNotNull("No active preview window", window);
        assertEquals("Unexpected window covering preview", BuildConfig.APPLICATION_ID, window.getPackageName().toString());
        int[] bounds = new int[4];
        scenario.onActivity(activity -> {
            try {
                var field = PreviewActivity.class.getDeclaredField("player"); field.setAccessible(true);
                VideoView view = (VideoView) field.get(activity);
                view.getLocationOnScreen(bounds); bounds[2] = view.getWidth(); bounds[3] = view.getHeight();
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        Bitmap screen = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Unable to capture composed display", screen);
        try {
            return Bitmap.createBitmap(screen, bounds[0] + bounds[2] / 4, bounds[1] + bounds[3] / 4,
                    bounds[2] / 2, bounds[3] / 2);
        } finally { screen.recycle(); }
    }
    private static Bitmap captureSurface(ActivityScenario<PreviewActivity> scenario) throws Exception {
        AtomicReference<Bitmap> resultBitmap = new AtomicReference<>();
        AtomicInteger result = new AtomicInteger(-1); CountDownLatch copied = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            try {
                var field = PreviewActivity.class.getDeclaredField("player"); field.setAccessible(true);
                VideoView view = (VideoView) field.get(activity);
                Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                resultBitmap.set(bitmap);
                PixelCopy.request(view.getHolder().getSurface(), bitmap, code -> { result.set(code); copied.countDown(); },
                        new Handler(Looper.getMainLooper()));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        assertTrue("Timed out capturing MP4 frame", copied.await(5, TimeUnit.SECONDS));
        assertEquals(PixelCopy.SUCCESS, result.get()); return resultBitmap.get();
    }
    private static int position(ActivityScenario<PreviewActivity> scenario) {
        AtomicInteger result = new AtomicInteger(-1);
        scenario.onActivity(activity -> {
            try {
                var field = PreviewActivity.class.getDeclaredField("player"); field.setAccessible(true);
                VideoView view = (VideoView) field.get(activity);
                if (view != null) result.set(view.getCurrentPosition());
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        return result.get();
    }
    private static VideoView player(PreviewActivity activity) {
        try {
            var field = PreviewActivity.class.getDeclaredField("player"); field.setAccessible(true);
            return (VideoView) field.get(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void assertPreparedAndPaused(ActivityScenario<PreviewActivity> scenario) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        AtomicBoolean prepared = new AtomicBoolean();
        while (!prepared.get() && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                VideoView view = player(activity); prepared.set(view != null && view.getDuration() > 0);
            });
            if (!prepared.get()) Thread.sleep(50);
        }
        assertTrue("MP4 was not prepared after returning", prepared.get());
        Thread.sleep(300);
        scenario.onActivity(activity -> assertFalse("Manually paused video unexpectedly resumed", player(activity).isPlaying()));
    }
    private static boolean visible(Bitmap bitmap) {
        int cx = bitmap.getWidth() / 2, cy = bitmap.getHeight() / 2, count = 0;
        for (int y = cy - bitmap.getHeight() / 4; y < cy + bitmap.getHeight() / 4; y += 5)
            for (int x = cx - bitmap.getWidth() / 4; x < cx + bitmap.getWidth() / 4; x += 5)
                if ((bitmap.getPixel(x, y) & 0x00ffffff) != 0) count++;
        return count > 10;
    }
    private static boolean differs(Bitmap a, Bitmap b) {
        long total = 0;
        for (int y = 0; y < a.getHeight(); y += 8) for (int x = 0; x < a.getWidth(); x += 8) {
            int first = a.getPixel(x, y), second = b.getPixel(x, y);
            total += Math.abs(((first >> 16) & 255) - ((second >> 16) & 255));
            total += Math.abs(((first >> 8) & 255) - ((second >> 8) & 255));
            total += Math.abs((first & 255) - (second & 255));
        }
        return total > 1000;
    }
}
