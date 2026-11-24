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
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class DirectoryCompareInstrumentedTest {
    @Test public void reportsVisibleMetadataDifferencesAndPreservesCaseSensitiveNames() throws Exception {
        File root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(),
                "directory-compare-").toFile();
        File a = new File(root, "a"), b = new File(root, "b"); assertTrue(a.mkdir()); assertTrue(b.mkdir());
        try {
            write(a, "equal.txt", "x", 1000); write(b, "equal.txt", "x", 1000);
            write(a, "size.txt", "x", 1000); write(b, "size.txt", "xx", 1000);
            write(a, "time.txt", "x", 1000); write(b, "time.txt", "x", 2000);
            write(a, "type", "x", 1000); assertTrue(new File(b, "type").mkdir());
            write(a, "Case", "x", 1000); write(b, "case", "x", 1000);
            write(a, "only-left", "x", 1000); write(b, "only-right", "x", 1000);
            List<DirectoryCompare.Difference> differences = DirectoryCompare.compare(nodes(a), nodes(b));
            Map<String, DirectoryCompare.Kind> kinds = new HashMap<>();
            for (DirectoryCompare.Difference difference : differences) kinds.put(difference.name(), difference.kind());
            assertEquals(7, differences.size());
            assertFalse(kinds.containsKey("equal.txt"));
            assertEquals(DirectoryCompare.Kind.SIZE, kinds.get("size.txt"));
            assertEquals(DirectoryCompare.Kind.MODIFIED, kinds.get("time.txt"));
            assertEquals(DirectoryCompare.Kind.TYPE, kinds.get("type"));
            assertEquals(DirectoryCompare.Kind.LEFT_ONLY, kinds.get("Case"));
            assertEquals(DirectoryCompare.Kind.RIGHT_ONLY, kinds.get("case"));
            assertEquals(DirectoryCompare.Kind.LEFT_ONLY, kinds.get("only-left"));
            assertEquals(DirectoryCompare.Kind.RIGHT_ONLY, kinds.get("only-right"));
        } finally { new FileStore(InstrumentationRegistry.getInstrumentation().getTargetContext()).delete(FileStore.local(root)); }
    }
    @Test public void currentDirectoryMenuOpensTheComparisonReport() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        var preferences = context.getSharedPreferences("MainActivity", 0); Map<String, ?> previous = new HashMap<>(preferences.getAll());
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "compare-ui-").toFile();
        File left = new File(root, "left"), right = new File(root, "right"); assertTrue(left.mkdir()); assertTrue(right.mkdir());
        write(left, "left-only.txt", "one", 1000); write(right, "right-only.txt", "two", 1000);
        ActivityScenario<MainActivity> scenario = null;
        try {
            device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
            preferences.edit().putString("left", left.getPath()).putString("right", right.getPath()).commit();
            scenario = ActivityScenario.launch(MainActivity.class);
            UiObject2 more = device.wait(Until.findObject(By.desc("更多操作")), 10000); assertNotNull(more); more.click();
            UiObject2 compare = device.wait(Until.findObject(By.text("比较另一窗口")), 5000); assertNotNull(compare); compare.click();
            assertNotNull(device.wait(Until.findObject(By.desc("仅当前窗口 · left-only.txt")), 8000));
            assertNotNull(device.wait(Until.findObject(By.desc("仅另一窗口 · right-only.txt")), 3000));
        } finally {
            if (scenario != null) scenario.close();
            var editor = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : previous.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String text) editor.putString(entry.getKey(), text);
                else if (value instanceof Boolean flag) editor.putBoolean(entry.getKey(), flag);
                else if (value instanceof Integer number) editor.putInt(entry.getKey(), number);
                else if (value instanceof Long number) editor.putLong(entry.getKey(), number);
            }
            editor.commit(); new FileStore(context).delete(FileStore.local(root));
        }
    }
    private static List<FileStore.Node> nodes(File directory) throws Exception {
        try (var children = Files.list(directory.toPath())) { return children.map(path -> FileStore.local(path.toFile())).toList(); }
    }
    private static void write(File parent, String name, String value, long modified) throws Exception {
        File file = new File(parent, name); Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(modified));
    }
}
