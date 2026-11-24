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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FileIntegrityInstrumentedTest {
    private static final String SHA = "15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225";
    private Context context;
    private UiDevice device;
    private File work, left, right, fixture;
    private ActivityScenario<MainActivity> scenario;
    private Map<String, ?> previous;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        var preferences = context.getSharedPreferences("MainActivity", 0);
        previous = new HashMap<>(preferences.getAll());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "integrity-test-").toFile();
        left = new File(work, "left"); right = new File(work, "right");
        assertTrue(left.mkdir()); assertTrue(right.mkdir());
        fixture = new File(left, "checksum.txt"); Files.write(fixture.toPath(), "123456789".getBytes(StandardCharsets.US_ASCII));
        preferences.edit().putString("left", left.getPath()).putString("right", right.getPath()).commit();
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
    }

    @After public void tearDown() throws Exception {
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
        if (work != null) new FileStore(context).delete(FileStore.local(work));
    }

    @Test public void knownShaAndCrcValuesAreShownAndComparedInFileProperties() throws Exception {
        FileIntegrity.Values values = FileIntegrity.calculate(new FileStore(context), FileStore.local(fixture));
        assertEquals(SHA, values.sha256());
        assertEquals("cbf43926", values.crc32());
        assertEquals("SHA-256 匹配", FileIntegrity.compare("SHA-256: " + SHA.toUpperCase(), values));
        assertEquals("CRC32 匹配", FileIntegrity.compare("CRC32: cbf43926", values));
        assertEquals("SHA-256 不匹配", FileIntegrity.compare("0".repeat(64), values));

        scenario = ActivityScenario.launch(MainActivity.class);
        UiObject2 row = device.wait(Until.findObject(By.text(fixture.getName())), 10000);
        assertNotNull(row); row.longClick();
        UiObject2 properties = device.wait(Until.findObject(By.desc("文件操作 属性")), 5000);
        assertNotNull(properties); properties.click();
        assertNull("Opening properties must not start the full-file checksum", device.findObject(By.desc("文件摘要值，可长按复制")));
        UiObject2 checksum = device.wait(Until.findObject(By.text("校验")), 5000);
        assertNotNull(checksum); checksum.click();
        UiObject2 digest = device.wait(Until.findObject(By.desc("文件摘要值，可长按复制")), 5000);
        assertNotNull(digest);
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!digest.getText().contains(SHA) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        assertTrue(digest.getText(), digest.getText().contains(SHA));
        assertTrue(digest.getText(), digest.getText().contains("25f9e794323b453885f5181f1b624d0b"));
        assertTrue(digest.getText(), digest.getText().contains("f7c3bc1d808e04732adf679965ccc34ca7ae3441"));
        UiObject2 input = device.findObject(By.desc("输入校验值")); assertNotNull(input);
        input.setText(SHA); assertNotNull(device.wait(Until.findObject(By.text("SHA-256 匹配")), 3000));
        input.setText("cbf43926"); assertNotNull(device.wait(Until.findObject(By.text("CRC32 匹配")), 3000));
        input.setText("00000000"); assertNotNull(device.wait(Until.findObject(By.text("CRC32 不匹配")), 3000));
        input.setText("MD5: 25f9e794323b453885f5181f1b624d0b"); assertNotNull(device.wait(Until.findObject(By.text("MD5 匹配")), 3000));
    }
}
