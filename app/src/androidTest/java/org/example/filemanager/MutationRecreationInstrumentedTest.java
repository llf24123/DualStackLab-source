package org.example.filemanager;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MutationRecreationInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File root, left, right, output;
    private ActivityScenario<MainActivity> scenario;
    private Map<String, ?> previous;
    private CountDownLatch release;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        var preferences = context.getSharedPreferences("MainActivity", 0); previous = new HashMap<>(preferences.getAll());
        root = Files.createTempDirectory(context.getCacheDir().toPath(), "mutation-recreate-").toFile();
        left = new File(root, "left"); right = new File(root, "right");
        assertTrue(left.mkdir()); assertTrue(right.mkdir()); output = new File(right, "completed.txt");
        preferences.edit().putString("left", left.getPath()).putString("right", right.getPath()).commit();
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        scenario = ActivityScenario.launch(MainActivity.class);
        assertNotNull(device.wait(Until.findObject(By.desc("左窗口文件列表")), 10000));
    }

    @After public void tearDown() throws Exception {
        if (release != null) release.countDown();
        if (scenario != null) scenario.close();
        var editor = context.getSharedPreferences("MainActivity", 0).edit().clear();
        for (Map.Entry<String, ?> entry : previous.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) editor.putString(entry.getKey(), text);
            else if (value instanceof Boolean flag) editor.putBoolean(entry.getKey(), flag);
            else if (value instanceof Integer number) editor.putInt(entry.getKey(), number);
            else if (value instanceof Long number) editor.putLong(entry.getKey(), number);
        }
        editor.commit();
        if (root != null) new FileStore(context).delete(FileStore.local(root));
    }

    @Test public void runningMutationSurvivesActivityRecreationAndRefreshesItsOutput() throws Exception {
        CountDownLatch entered = new CountDownLatch(1); release = new CountDownLatch(1); AtomicBoolean oldCallback = new AtomicBoolean();
        Tasks.Job<List<FileStore.Node>> job = () -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
            if (!output.createNewFile()) throw new java.io.IOException("test output already exists");
            return List.of(FileStore.local(output));
        };
        Tasks.Done<List<FileStore.Node>> callback = result -> oldCallback.set(true);
        scenario.onActivity(activity -> {
            try {
                Method mutate = MainActivity.class.getDeclaredMethod("mutate", Tasks.Job.class, Tasks.Done.class);
                mutate.setAccessible(true); mutate.invoke(activity, job, callback);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        assertTrue("Mutation worker did not start", entered.await(5, TimeUnit.SECONDS));
        scenario.recreate();
        scenario.onActivity(activity -> assertTrue("New activity should reattach to the running mutation", bool(activity, "busy")));
        assertNotNull("Recreated operations keep their cancel control",
                device.wait(Until.findObject(By.desc("文件操作进度")), 3000));
        release.countDown();
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!output.isFile() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40);
        assertTrue(output.isFile());
        while (Tasks.activeMutation() != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40);
        assertNull(Tasks.activeMutation()); assertFalse("Old Activity callback must not be delivered", oldCallback.get());
        scenario.onActivity(activity -> {
            assertFalse(bool(activity, "busy"));
            try {
                Field field = MainActivity.class.getDeclaredField("right"); field.setAccessible(true);
                Object pane = field.get(activity); Field highlights = pane.getClass().getDeclaredField("resultHighlights"); highlights.setAccessible(true);
                assertTrue("Completed output should be selected for a short highlight", ((java.util.Set<?>)highlights.get(pane)).contains(FileStore.local(output).key()));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        UiObject2 row = device.wait(Until.findObject(By.text(output.getName())), 5000); assertNotNull(row);
        assertFalse(device.hasObject(By.desc("文件操作进度")));
    }

    @Test public void partialTransferAfterRecreationKeepsRetryAndOriginalDestination() throws Exception {
        File first = new File(left, "completed.txt"), missing = new File(left, "retry.txt");
        Files.write(first.toPath(), new byte[]{1});
        List<FileStore.Node> sources = List.of(FileStore.local(first), FileStore.local(missing));
        FileStore.Node destination = FileStore.directory(right, false);
        CountDownLatch entered = new CountDownLatch(1); release = new CountDownLatch(1);
        Tasks.Job<List<FileStore.Node>> job = () -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
            return new FileStore(context).copyAll(sources, destination, false);
        };
        scenario.onActivity(activity -> {
            try {
                Method mutate = MainActivity.class.getDeclaredMethod("mutate", Tasks.Job.class, Tasks.Done.class,
                        boolean.class, Tasks.Done.class, Tasks.Transfer.class);
                mutate.setAccessible(true);
                mutate.invoke(activity, job, (Tasks.Done<List<FileStore.Node>>) result -> fail("Expected partial failure"),
                        false, null, new Tasks.Transfer(sources, destination, true, false, false));
            } catch (Exception error) { throw new AssertionError(error); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS)); scenario.recreate(); release.countDown();
        assertNotNull(device.wait(Until.findObject(By.text("传输未完成")), 10000));
        assertTrue(output.isFile());
        assertNotNull(device.findObject(By.textContains("已成功 1 项，剩余 1 项未完成")));
        Files.write(missing.toPath(), new byte[]{2});
        UiObject2 retry = device.findObject(By.text("重试未完成项")); assertNotNull(retry); retry.click();
        long deadline = SystemClock.uptimeMillis() + 10000;
        while ((!new File(right, "retry.txt").isFile() || Tasks.activeMutation() != null)
                && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40);
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(new File(right, "retry.txt").toPath()));
        assertFalse("Retry must not duplicate the completed item", new File(right, "completed (1).txt").exists());
        assertNull(Tasks.activeMutation());
    }

    private static boolean bool(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.getBoolean(object); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
