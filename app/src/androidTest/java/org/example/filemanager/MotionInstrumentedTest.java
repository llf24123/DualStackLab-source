package org.example.filemanager;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ListView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MotionInstrumentedTest {
    private Context context;
    private File work;
    private ActivityScenario<MainActivity> scenario;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "motion-").toFile();
        for (String name : new String[]{"a.txt", "b.txt"}) Files.write(new File(work, name).toPath(), new byte[]{65});
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("left", work.getPath()).putString("right", work.getPath()).commit();
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void activePaneIndicatorAndSelectionSettleOnTheirFinalColours() throws Exception {
        scenario = ActivityScenario.launch(MainActivity.class);
        await(() -> row("a.txt") != null);
        scenario.onActivity(activity -> invoke(activity, "activate", get(activity, "right")));
        SystemClock.sleep(Ui.MOTION_SHORT + 150);
        scenario.onActivity(activity -> {
            assertEquals(Ui.ACCENT, color((View) get(get(activity, "right"), "indicator")));
            assertEquals(Ui.LINE, color((View) get(get(activity, "left"), "indicator")));
            invoke(activity, "activate", get(activity, "left"));
            Object pane = get(activity, "left");
            invoke(pane, "toggle", ((java.util.List<?>) get(pane, "entries")).get(0));
        });
        SystemClock.sleep(Ui.MOTION_SHORT + 250);
        scenario.onActivity(activity -> {
            assertEquals(Ui.ACCENT, color((View) get(get(activity, "left"), "indicator")));
            View selectedRow = row("a.txt");
            assertEquals(Ui.TINT, rowColor(selectedRow));
            View icon = ((ViewGroup) ((ViewGroup) selectedRow).getChildAt(0)).getChildAt(0);
            assertEquals(1f, icon.getScaleX(), .01f);
            assertEquals("Fade must end fully opaque", 1f, ((View) get(activity, "actions")).getAlpha(), .01f);
            Object pane = get(activity, "left");
            invoke(pane, "resetSelection"); invoke(pane, "selectionChanged");
        });
        SystemClock.sleep(Ui.MOTION_SHORT + 150);
        scenario.onActivity(activity -> assertEquals(Ui.SURFACE, rowColor(row("a.txt"))));
    }
    @Test public void launcherKeepsBothListsFlushAndRendersEachMaterialsFolder() throws Exception {
        assertTrue(new File(work, "fixture-folder").mkdir());
        var appearance = context.getSharedPreferences("appearance", 0);
        String previous = appearance.getString("mode", null);
        String previousMaterial = appearance.getString("material", null);
        var savedIcons=new java.util.EnumMap<ThemeMaterial,IconStyle>(ThemeMaterial.class);
        for(var material:ThemeMaterial.values())savedIcons.put(material,IconStyle.forMaterial(context,material));
        var launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        assertEquals(MainActivity.class.getName(), launch.getComponent().getClassName());
        try {
            for (ThemeMaterial material : ThemeMaterial.values()) for (String mode : new String[]{"light", "dark"}) {
                var options=appearance.edit().putString("mode",mode).putString("material",material.id);
                IconStyle.write(options,material,IconStyle.FOLLOW);options.commit();
                scenario = ActivityScenario.launch(launch);
                await(() -> {
                    boolean[] ready = {true};
                    scenario.onActivity(a -> {
                        for (String side : new String[]{"left", "right"}) {
                            ListView list = (ListView) get(get(a, side), "list");
                            ready[0] &= list.getChildAt(1) != null;
                        }
                    });
                    return ready[0];
                });
                scenario.onActivity(a -> {
                    if (material==ThemeMaterial.FROST) {
                        ViewGroup dock=(ViewGroup)get(a,"actions");
                        View root=a.getWindow().getDecorView();int[] dockPos=new int[2],rootPos=new int[2];
                        dock.getLocationOnScreen(dockPos);root.getLocationOnScreen(rootPos);
                        assertTrue("The glass dock floats clear of both screen edges",dockPos[0]>rootPos[0]
                                && dockPos[0]+dock.getWidth()<rootPos[0]+root.getWidth());
                        assertEquals("The floating dock retains every file command",5,dock.getChildCount());
                    } else {
                        GradientDrawable toolbar=(GradientDrawable)((View)get(a,"headerHost")).getBackground();
                        if (material==ThemeMaterial.CLASSIC) assertNotNull(toolbar.getColor());
                        else { int[] stops=toolbar.getColors();assertNotNull(stops);assertNotEquals(stops[0],stops[stops.length-1]); }
                    }
                    for (String side : new String[]{"left", "right"}) {
                        ViewGroup pane = (ViewGroup) get(a, side);
                        ListView list = (ListView) get(pane, "list");
                        int[] paneAt = new int[2], listAt = new int[2];
                        pane.getLocationOnScreen(paneAt); list.getLocationOnScreen(listAt);
                        assertEquals("Only the active-pane indicator may precede the list",
                                Ui.dp(a, 2), listAt[1] - paneAt[1]);
                        ViewGroup folder = (ViewGroup) list.getChildAt(1);
                        assertNotNull(findText(folder, "fixture-folder"));
                        android.widget.ImageView icon=(android.widget.ImageView)get(folder.getTag(),"icon");
                        GradientDrawable tile=(GradientDrawable)icon.getBackground();
                        boolean dark=mode.equals("dark");int ink=icon.getImageTintList().getDefaultColor();
                        if(material==ThemeMaterial.CLASSIC||material==ThemeMaterial.FROST){
                            int expected=material==ThemeMaterial.CLASSIC?(dark?0xff000000:0xff303030):(dark?0xff48494b:0x00000000);
                            assertEquals(material.title,expected,tile.getColor().getDefaultColor());
                            assertEquals(material==ThemeMaterial.FROST&&!dark?material.tones(false).text():android.graphics.Color.WHITE,ink);
                        }else{int[] fill=tile.getColors();assertNotNull(material.title,fill);assertNotEquals(fill[0],fill[fill.length-1]);}
                    }
                });
                scenario.close(); scenario = null;
            }
        } finally {
            var restore=appearance.edit().putString("mode",previous).putString("material",previousMaterial);
            savedIcons.forEach((m,s)->IconStyle.write(restore,m,s));restore.commit();
        }
    }

    @Test public void comfortableDensityEnlargesRowsAndPersists() throws Exception {
        scenario = ActivityScenario.launch(MainActivity.class);
        await(() -> row("a.txt") != null);
        int[] compact = {0};
        scenario.onActivity(activity -> compact[0] = row("a.txt").getHeight());
        scenario.onActivity(activity -> activity.getPreferences(0).edit().putBoolean("comfortable", true).commit());
        scenario.recreate();
        await(() -> row("a.txt") != null && row("a.txt").getHeight() > 0);
        scenario.onActivity(activity -> {
            try {
                assertTrue("Comfortable rows are taller", row("a.txt").getHeight() > compact[0]);
                assertEquals(15f * activity.getResources().getDisplayMetrics().scaledDensity,
                        ((TextView) findText((ViewGroup) row("a.txt"), "a.txt")).getTextSize(), 1f);
            } finally { activity.getPreferences(0).edit().remove("comfortable").commit(); }
        });
    }

    @Test public void repeatedSelectionPreservesTheScrolledRowAndOffset() throws Exception {
        for (int i = 0; i < 240; i++) Files.write(new File(work, "entry-" + i + ".txt").toPath(), new byte[]{65});
        scenario = ActivityScenario.launch(MainActivity.class);
        await(() -> row("a.txt") != null);
        int anchor=80, contentOffset=-12;
        scenario.onActivity(activity -> ((ListView) get(get(activity, "left"), "list")).setSelectionFromTop(anchor, contentOffset));
        await(() -> { boolean[] scrolled = {false}; scenario.onActivity(activity -> {
            ListView list = (ListView) get(get(activity, "left"), "list");
            View target = list.getChildAt(anchor - list.getFirstVisiblePosition());
            scrolled[0] = target != null && target.getTop() - list.getListPaddingTop() == contentOffset;
        }); return scrolled[0]; });
        int[] drawn = new int[2];String[] key = new String[1];
        scenario.onActivity(activity -> {
            ListView list=(ListView)get(get(activity,"left"),"list");
            drawn[0]=list.getFirstVisiblePosition();drawn[1]=list.getChildAt(0).getTop();
            key[0]=((FileStore.Node)list.getItemAtPosition(anchor)).key();
        });
        for (int i = 0; i < 26; i++) {
            if (i == 6) graphics(" reset");
            scenario.onActivity(activity -> {
                Object pane = get(activity, "left");
                invoke(pane, "toggle", ((java.util.List<?>) get(pane, "entries")).get(82));
            });
            SystemClock.sleep(Ui.MOTION_SHORT + 45L);
            scenario.onActivity(activity -> {
                ListView list = (ListView) get(get(activity, "left"), "list");
                assertEquals(drawn[0],list.getFirstVisiblePosition());assertEquals(drawn[1],list.getChildAt(0).getTop());
                View target=list.getChildAt(anchor-list.getFirstVisiblePosition());assertNotNull(target);
                assertEquals(key[0],((FileStore.Node)get(target.getTag(),"node")).key());
                assertEquals(contentOffset,target.getTop()-list.getListPaddingTop());
            });
        }
        String result = graphics("");
        for (String line : result.split("\n")) if (line.matches(".*(Total frames rendered|Janky frames|percentile).*"))
            android.util.Log.i("DualStackMotion", line.trim());
    }

    private static String graphics(String argument) throws Exception {
        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("dumpsys gfxinfo " + BuildConfig.APPLICATION_ID + argument))) {
            return new String(FileStore.readBytes(input, 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private View row(String name) {
        View[] found = {null};
        scenario.onActivity(activity -> {
            ViewGroup list = (ViewGroup) get(get(activity, "left"), "list");
            for (int i = 0; i < list.getChildCount(); i++) if (findText((ViewGroup) list.getChildAt(i), name) != null) found[0] = list.getChildAt(i);
        });
        return found[0];
    }
    private static View findText(ViewGroup group, String text) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView view && text.contentEquals(view.getText())) return child;
            if (child instanceof ViewGroup nested) { View found = findText(nested, text); if (found != null) return found; }
        }
        return null;
    }
    private static int rowColor(View row) {
        return ((GradientDrawable) ((LayerDrawable) row.getBackground()).getDrawable(0)).getColor().getDefaultColor();
    }
    private static int color(View view) { return ((ColorDrawable) view.getBackground()).getColor(); }
    private static Object get(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException next) { } catch (IllegalAccessException error) { throw new AssertionError(error); }
        }
        throw new AssertionError("No field " + name);
    }
    private static void invoke(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getDeclaredMethods())
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                try { method.setAccessible(true); method.invoke(target, arguments); return; }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            }
        throw new AssertionError("No method " + name);
    }
    private static void await(BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30);
        assertTrue("Timed out", condition.getAsBoolean());
    }
}
