package org.example.filemanager;

import android.content.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.ListView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DirectorySheetLayoutInstrumentedTest {
    private Context context;
    private UiDevice device;
    private File work;
    private ActivityScenario<MainActivity> scenario;
    private Map<String, ?> mainBefore, placesBefore;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.setOrientationNatural();
        mainBefore = new HashMap<>(context.getSharedPreferences("MainActivity", 0).getAll());
        placesBefore = new HashMap<>(context.getSharedPreferences("directory-places", 0).getAll());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "tablet-sheet-").toFile();
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", work.getPath()).putString("right", work.getPath()).commit();
        DirectoryPlaces places = new DirectoryPlaces(context); places.clear(true);
        for (int i = 0; i < 28; i++) places.bookmark(DirectoryPlaces.Entry.from(
                FileStore.directory(new File(work, String.format(Locale.ROOT, "bookmark-%02d", i)), false)));
        scenario = ActivityScenario.launch(MainActivity.class);
        find(By.desc("双栈 Lab · " + work.getPath()));
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        new FileStore(context).delete(FileStore.local(work));
        restore("MainActivity", mainBefore); restore("directory-places", placesBefore);
        device.setOrientationNatural(); device.unfreezeRotation();
    }

    @Test public void fullWidthBottomSheetFitsNavigationCutoutsAndKeyboard() {
        for (int[] size : List.of(new int[]{2560, 1600}, new int[]{1600, 2560}, new int[]{1920, 1200}, new int[]{960, 640})) {
            Rect insets = new Rect(60, 70, 90, 110);
            Rect bounds = DirectorySheet.panelBounds(size[0], size[1], insets, 2f);
            assertEquals(insets.left, bounds.left); assertTrue(bounds.top >= insets.top);
            assertEquals(size[0] - insets.right, bounds.right);
            assertEquals("Drawer must meet the screen edge, not stop above the navigation bar", size[1], bounds.bottom);
            assertEquals(Math.max(400, Math.round((size[1] - insets.top - insets.bottom) * .54f)), bounds.height());
        }
        Rect keyboard = DirectorySheet.panelBounds(1920, 1200, new Rect(0, 60, 0, 650), 2f);
        assertEquals(1200, keyboard.bottom); assertTrue(keyboard.top >= 60); assertTrue(keyboard.height() <= 490);
        Rect phone = DirectorySheet.panelBounds(1080, 2400, new Rect(0, 72, 0, 100), 3f);
        assertEquals(1080, phone.width()); assertEquals(2400, phone.bottom);
    }

    @Test public void portraitPanelKeepsFooterAboveNavigationAndLastBookmarkReachable() {
        open(); assertFits();
        device.waitForIdle();
        Rect bounds = new Rect();
        int[] visible = new int[2];
        for (int i = 0; i < 64; i++) {
            scenario.onActivity(activity -> {
                ListView list = (ListView) get(get(activity, "sheet"), "list");
                int[] origin = new int[2];
                list.getLocationOnScreen(origin);
                bounds.set(origin[0], origin[1], origin[0] + list.getWidth(), origin[1] + list.getHeight());
                visible[0] = list.getLastVisiblePosition();
                visible[1] = list.getCount();
            });
            if (visible[0] == visible[1] - 1) break;
            int margin = Math.max(1, bounds.height() / 10);
            assertTrue(device.swipe(bounds.centerX(), bounds.bottom - margin,
                    bounds.centerX(), bounds.top + margin, 22));
            SystemClock.sleep(160);
        }
        assertEquals("Visible end of the bookmark list", visible[1] - 1, visible[0]);
        find(By.desc("目录快捷项 bookmark-27")); assertFits();
        capture("bookmarks-portrait-last-entry.png");
        find(By.desc("收起最近与书签")).click();
        assertTrue(device.wait(Until.gone(By.desc("最近与书签面板")), 5000));
    }

    @Test public void landscapeTabletKeepsWholePanelAndCloseControlInsideWindow() throws Exception {
        device.setOrientationLeft();
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline && device.getDisplayWidth() < device.getDisplayHeight()) SystemClock.sleep(60);
        assertTrue(device.getDisplayWidth() > device.getDisplayHeight());
        find(By.desc("双栈 Lab · " + work.getPath()));
        open(); assertFits();
        capture("bookmarks-tablet-landscape.png");
        find(By.desc("收起最近与书签")).click();
        assertTrue(device.wait(Until.gone(By.desc("最近与书签面板")), 5000));
    }

    private void open() {
        scenario.onActivity(activity -> {
            try { Method method = MainActivity.class.getDeclaredMethod("directorySheet"); method.setAccessible(true); method.invoke(activity); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        find(By.desc("书签标签")); find(By.text("bookmark-00"));
        SystemClock.sleep(250);
    }
    private void assertFits() {
        scenario.onActivity(activity -> {
            DirectorySheet sheet = (DirectorySheet) get(activity, "sheet");
            View body = (View) get(sheet, "body");
            Rect visible = new Rect(); assertTrue(body.getGlobalVisibleRect(visible));
            assertEquals(body.getWidth(), visible.width()); assertEquals(body.getHeight(), visible.height());
            Rect safe = new Rect();
            View decor = activity.getWindow().getDecorView();
            WindowInsets insets = decor.getRootWindowInsets();
            int bottom = android.os.Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).bottom
                    : insets.getSystemWindowInsetBottom();
            View close = findView(body, "收起最近与书签"); assertNotNull(close);
            assertTrue(close.getGlobalVisibleRect(safe));
            assertTrue("Down chevron overlaps navigation bar: " + safe + " screen=" + decor.getHeight()
                    + " bar=" + bottom, safe.bottom <= decor.getHeight() - bottom);
            assertTrue(visible.right <= decor.getWidth()); assertTrue(visible.top >= 0);
            int sideInsets;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                sideInsets = bars.left + bars.right;
            } else sideInsets = insets.getSystemWindowInsetLeft() + insets.getSystemWindowInsetRight();
            assertEquals("Bottom drawer must fill the safe width on tablets too", decor.getWidth() - sideInsets, body.getWidth());
            Rect bodyOnScreen = new Rect(); body.getGlobalVisibleRect(bodyOnScreen);
            assertEquals("Drawer must reach the bottom screen edge", decor.getHeight(), bodyOnScreen.bottom);
            assertEquals("Content padding must clear the navigation bar", bottom, body.getPaddingBottom());
            ListView list = (ListView) get(sheet, "list");
            assertTrue("List must keep room between tabs and footer", list.getHeight() >= Ui.dp(activity, 60));
        });
    }
    private void capture(String name) {
        File evidence = new File(context.getExternalFilesDir(null), "app-icons-tablet-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs()); assertTrue(device.takeScreenshot(new File(evidence, name)));
    }
    private UiObject2 find(BySelector selector) {
        UiObject2 result = device.wait(Until.findObject(selector), 15000); assertNotNull(selector.toString(), result); return result;
    }
    private static View findView(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = findView(group.getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void restore(String name, Map<String, ?> values) {
        SharedPreferences.Editor editor = context.getSharedPreferences(name, 0).edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String text) editor.putString(key, text);
            else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof Float number) editor.putFloat(key, number);
        }
        editor.commit();
    }
}
