package org.example.filemanager;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AuditFixesInstrumentedTest {
    Context context;
    File work;
    UiDevice device;
    ActivityScenario<PreviewActivity> preview;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        work = Files.createTempDirectory(context.getCacheDir().toPath(), "audit-repairs-").toFile();
    }
    @After public void cleanup() throws Exception {
        if (preview != null) preview.close();
        context.getSharedPreferences("PreviewActivity", 0).edit().putBoolean("thumbnails", false).commit();
        if (SearchJob.active() != null) SearchJob.active().cancel();
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void concurrentSharesKeepTheirOwnUrisAndOriginalDisplayNames() throws Exception {
        File a = new File(work, "a"), b = new File(work, "b"); assertTrue(a.mkdir()); assertTrue(b.mkdir());
        File first = new File(a, "same.apk"), second = new File(b, "same.apk");
        writeString(first.toPath(), "first"); writeString(second.toPath(), "second");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<File> staged = new ArrayList<>();
        try {
            List<Future<File>> copies = workers.invokeAll(List.of(
                    () -> ApkInstall.stageShared(context, FileStore.local(first), ".apk"),
                    () -> ApkInstall.stageShared(context, FileStore.local(second), ".apk")));
            for (Future<File> copy : copies) staged.add(copy.get(5, TimeUnit.SECONDS));
            assertNotEquals(staged.get(0), staged.get(1));
            for (int i = 0; i < staged.size(); i++) {
                Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", staged.get(i));
                try (var cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                    assertNotNull(cursor); assertTrue(cursor.moveToFirst());
                    assertEquals("same.apk", cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)));
                }
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    assertEquals(i == 0 ? "first" : "second", new String(FileStore.readBytes(input, 100)));
                }
            }
        } finally {
            workers.shutdownNow();
            for (File file : staged) new FileStore(context).delete(FileStore.local(file.getParentFile()));
        }
    }
    @Test public void expiredShareCleanupKeepsRecentSessionsAndDoesNotFollowSymlinks() throws Exception {
        File shares = new File(work, "shares"), old = new File(shares, "session-old"), recent = new File(shares, "session-new");
        assertTrue(old.mkdirs()); assertTrue(recent.mkdir());
        File outside = new File(work, "keep.txt"); writeString(outside.toPath(), "keep");
        writeString(new File(old, "old.txt").toPath(), "old");
        Files.createSymbolicLink(new File(old, "link").toPath(), outside.toPath());
        writeString(new File(recent, "new.txt").toPath(), "new");
        long now = System.currentTimeMillis(); assertTrue(old.setLastModified(now - TimeUnit.DAYS.toMillis(2)));
        ApkInstall.cleanExpiredShares(shares, now);
        assertFalse(old.exists()); assertTrue(recent.isDirectory()); assertEquals("keep", readString(outside.toPath()));
    }
    @Test public void failedShareLeavesNoSessionAndUnsafeDisplayNameCannotEscape() throws Exception {
        File shared = new File(context.getCacheDir(), "shared"); shared.mkdirs();
        Set<String> before = new HashSet<>(Arrays.asList(Objects.requireNonNull(shared.list())));
        try { ApkInstall.stage(context, FileStore.local(new File(work, "missing.apk"))); fail(); }
        catch (IOException expected) { }
        Set<String> after = new HashSet<>(Arrays.asList(Objects.requireNonNull(shared.list()))); after.removeAll(before);
        assertTrue("Failed copies must not leave an empty or partial session", after.isEmpty());
        File payload = new File(work, "payload"); writeString(payload.toPath(), "safe");
        FileStore.Node named = new FileStore.Node("..", payload, null, false, false, 4, 0);
        File staged = ApkInstall.stageShared(context, named, "-../../escape");
        try {
            assertEquals("shared-file.bin", staged.getName());
            assertEquals(shared.getCanonicalFile(), staged.getParentFile().getParentFile().getCanonicalFile());
            assertEquals("safe", readString(staged.toPath()));
        } finally { new FileStore(context).delete(FileStore.local(staged.getParentFile())); }
    }
    @Test public void unreadableExpiredSessionDoesNotBlockSharing() throws Exception {
        Context isolated = new ContextWrapper(context) { @Override public File getCacheDir() { return work; } };
        File old = new File(work, "shared/session-expired"), locked = new File(old, "locked"); assertTrue(locked.mkdirs());
        File payload = new File(work, "fresh.txt"); writeString(payload.toPath(), "fresh");
        assertTrue(old.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)));
        android.system.Os.chmod(locked.getPath(), 0);
        try {
            File shared = ApkInstall.stageShared(isolated, FileStore.local(payload), ".txt");
            assertEquals("fresh", readString(shared.toPath()));
        } finally { if (locked.exists()) android.system.Os.chmod(locked.getPath(), 0700); }
    }
    @Test public void cancelledProviderReadClosesItsPipeAndDoesNotPublishLateResults() throws Exception {
        Uri root = grant(); Bundle seed = new Bundle(); seed.putString("name", "blocked.txt");
        seed.putString("text", "needle" + "x".repeat(256 * 1024)); provider("seed", seed);
        Bundle config = new Bundle(); config.putString("blockReadName", "blocked.txt"); provider("configure", config);
        SearchJob job = SearchJob.start(context, new FileStore(context).document(root), "needle", Pattern.compile("needle"), true);
        try {
            assertTrue(waitFor(() -> provider("readState", null).getBoolean("prefixWritten"), 5000));
            long start = SystemClock.uptimeMillis();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(job::cancel);
            assertTrue("Cancel must not wait for the provider on the UI thread", SystemClock.uptimeMillis() - start < 1000);
            SearchJob.Snapshot stopped = job.snapshot(); assertTrue(stopped.cancelled()); assertTrue(stopped.finished());
            // Give asynchronous close a turn, then the provider's next write must see a closed pipe.
            Tasks.CONTROL.submit(() -> {}).get(3, TimeUnit.SECONDS); SystemClock.sleep(100);
            provider("releaseRead", null);
            assertTrue("The cancelled read descriptor must be closed", waitFor(() -> provider("readState", null).getString("error") != null, 5000));
            assertEquals(stopped.scanned(), job.snapshot().scanned()); assertEquals(stopped.matches(), job.snapshot().matches());
            assertNull(job.snapshot().error());
            writeString(new File(work, "target.txt").toPath(), "next");
            SearchJob next = SearchJob.start(context, FileStore.directory(work, false), "target", Pattern.compile("target"), false);
            assertTrue(waitFor(() -> next.snapshot().finished(), 1500)); assertEquals(1, next.snapshot().matches().size());
        } finally { job.cancel(); provider("releaseRead", null); }
    }
    @Test public void viewportCentreAndZoomSurviveResizeInBothDirections() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomImageView view = new ZoomImageView(context); Bitmap bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
            try {
                view.layout(0, 0, 400, 500); view.setImageBitmap(bitmap); view.zoom(3f, 250, 300);
                float[] original = centre(view);
                for (int[] size : new int[][]{{400, 700}, {700, 400}, {400, 500}}) {
                    view.layout(0, 0, size[0], size[1]);
                    assertEquals(3f, view.zoomFactor(), .001f); assertArrayEquals(original, centre(view), .002f);
                }
                view.reset(); view.layout(0, 0, 800, 400);
                assertEquals(1f, view.zoomFactor(), .001f); assertArrayEquals(new float[]{.5f, .5f}, centre(view), .002f);
            } finally { view.setImageDrawable(null); bitmap.recycle(); }
        });
    }
    @Test public void uncachedThumbnailsApplyAllEightExifTransforms() throws Exception {
        int R = Color.RED, G = Color.GREEN, B = Color.BLUE, Y = Color.YELLOW;
        int[][] expected = {{R,G,B,Y}, {G,R,Y,B}, {Y,B,G,R}, {B,Y,R,G}, {R,B,G,Y}, {B,R,Y,G}, {Y,G,B,R}, {G,Y,R,B}};
        var decode = PreviewActivity.class.getDeclaredMethod("decodeThumbnail", File.class); decode.setAccessible(true);
        for (int orientation = 1; orientation <= 8; orientation++) {
            File file = new File(work, orientation + ".jpg"); Bitmap b = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(b); Paint paint = new Paint(); int[] colors = {R,G,B,Y};
            for (int i = 0; i < 4; i++) { paint.setColor(colors[i]); canvas.drawRect(i % 2 * 60, i / 2 * 40, i % 2 * 60 + 60, i / 2 * 40 + 40, paint); }
            try (OutputStream output = new FileOutputStream(file)) { assertTrue(b.compress(Bitmap.CompressFormat.JPEG, 100, output)); }
            b.recycle(); ExifInterface exif = new ExifInterface(file); exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation)); exif.saveAttributes();
            Bitmap thumb = (Bitmap) decode.invoke(null, file); assertNotNull(thumb);
            try {
                assertEquals(orientation >= 5 ? 80 : 120, thumb.getWidth());
                for (int corner = 0; corner < 4; corner++) {
                    int pixel = thumb.getPixel(corner % 2 == 0 ? 10 : thumb.getWidth() - 11, corner / 2 == 0 ? 10 : thumb.getHeight() - 11);
                    int want = expected[orientation - 1][corner];
                    assertEquals("red orientation " + orientation, Color.red(want), Color.red(pixel), 12);
                    assertEquals("green orientation " + orientation, Color.green(want), Color.green(pixel), 12);
                    assertEquals("blue orientation " + orientation, Color.blue(want), Color.blue(pixel), 12);
                }
            } finally { thumb.recycle(); }
        }
    }
    @Test public void thumbnailViewsAndPixelsAreReusedThenRefreshedAfterFileChanges() throws Exception {
        for (int i = 1; i <= 8; i++) image(String.format(Locale.ROOT, "%02d.png", i), Color.RED);
        context.getSharedPreferences("PreviewActivity", 0).edit().putBoolean("thumbnails", true).commit();
        Intent intent = new Intent(context, PreviewActivity.class).putExtra("kind", "image"); FileStore.local(new File(work,"01.png")).put(intent);
        preview = ActivityScenario.launch(intent); awaitPicture(0); idle();
        ImageView[] oldView = {null}; Bitmap[] oldBitmap = {null};
        preview.onActivity(a -> { oldView[0] = (ImageView)((LinearLayout) field(a,"thumbnailItems")).getChildAt(3); oldBitmap[0] = ((BitmapDrawable)oldView[0].getDrawable()).getBitmap(); call(a,"showPicture",int.class,1); });
        awaitPicture(1); idle();
        preview.onActivity(a -> {
            LinearLayout strip = (LinearLayout)field(a,"thumbnailItems"); assertSame(oldView[0], strip.getChildAt(3));
            assertSame(oldBitmap[0], ((BitmapDrawable)oldView[0].getDrawable()).getBitmap());
        });
        image("04.png", Color.BLUE);
        preview.onActivity(a -> call(a,"showPicture",int.class,2)); awaitPicture(2); idle();
        preview.onActivity(a -> {
            Bitmap updated = ((BitmapDrawable)oldView[0].getDrawable()).getBitmap(); assertNotSame(oldBitmap[0],updated);
            assertTrue(oldBitmap[0].isRecycled()); assertTrue(Color.blue(updated.getPixel(10,10)) > 200);
            call(a,"showPicture",int.class,7);
        });
        awaitPicture(7); idle();
        preview.onActivity(a -> assertTrue(((Map<?,?>)field(a,"thumbnailCache")).size() <= 7));
    }
    @Test public void searchHistoryClearTargetExpandsForLargeText() throws Exception {
        var prefs = context.getSharedPreferences("search",0); String before = prefs.getString("history", "");
        prefs.edit().putString("history", "audit-query").commit();
        Intent intent = new Intent(context, SearchActivity.class).putExtra("rootKey", work.getPath()).putExtra("rootPath",work.getPath());
        try (ActivityScenario<SearchActivity> screen = ActivityScenario.launch(intent)) {
            screen.onActivity(a -> { TextView clear = (TextView)find(a.getWindow().getDecorView(), "清除搜索历史"); assertNotNull(clear); clear.setTextSize(32); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            UiObject2 clear = device.wait(Until.findObject(By.desc("清除搜索历史")),5000); assertNotNull(clear);
            assertTrue(clear.getVisibleBounds().width() >= Ui.dp(context,48)); assertTrue(clear.getVisibleBounds().height() >= Ui.dp(context,48));
            clear.click(); assertTrue(waitFor(() -> !prefs.contains("history"),3000));
        } finally { prefs.edit().putString("history", before).commit(); }
    }
    @Test public void comparisonShowsAndCopiesEveryDifferenceBeyond160() throws Exception {
        File left = new File(work,"left"), right = new File(work,"right"); assertTrue(left.mkdir()); assertTrue(right.mkdir());
        for (int i=0;i<175;i++) writeString(new File(left,String.format(Locale.ROOT,"item-%03d.txt",i)).toPath(),"x");
        var prefs = context.getSharedPreferences("MainActivity",0); Map<String,?> previous = new HashMap<>(prefs.getAll());
        device.executeShellCommand("cmd appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        prefs.edit().putString("left",left.getPath()).putString("right",right.getPath()).commit();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            UiObject2 more=device.wait(Until.findObject(By.desc("更多操作")),10000);assertNotNull(more);more.click();
            UiObject2 compare=device.wait(Until.findObject(By.text("比较另一窗口")),5000);assertNotNull(compare);compare.click();
            UiObject2 report=device.wait(Until.findObject(By.desc("窗口差异列表")),10000);assertNotNull(report);
            new UiScrollable(new UiSelector().description("窗口差异列表")).scrollTextIntoView("仅当前窗口 · item-174.txt");
            assertNotNull(device.findObject(By.desc("仅当前窗口 · item-174.txt")));
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "audit-0636-comparison.png"));
            UiObject2 copy=device.findObject(By.text("复制全部"));assertNotNull(copy);copy.click();
            screen.onActivity(a -> {
                ClipData data=((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();assertNotNull(data);
                String text=data.getItemAt(0).getText().toString();assertEquals(175,text.split("\n").length);assertTrue(text.contains("item-174.txt"));
            });
        } finally { restore(prefs,previous); }
    }
    @Test public void namesOnlyPreflightMatchesFullListingAndKeepsCreationRaceProtection() throws Exception {
        File target = new File(work,"target"); assertTrue(target.mkdir());
        for (String name : List.of("normal", ".hidden", "two words", "中文", "line\nname")) writeString(new File(target,name).toPath(),"x");
        assertTrue(new File(target,"directory").mkdir());
        Files.createSymbolicLink(new File(target,"dangling").toPath(),new File(work,"missing").toPath());
        FileStore store=new FileStore(context); FileStore.Node destination=FileStore.directory(target,false);
        Set<String> names=store.listNames(destination); assertEquals(7,names.size()); assertTrue(names.contains("dangling"));
        File source=new File(work,"later.txt");writeString(source.toPath(),"new");
        File raced=new File(target,"later.txt");writeString(raced.toPath(),"existing");
        try {store.copyAll(List.of(FileStore.local(source)),destination,false,FileStore.ConflictPolicy.KEEP_BOTH,names);fail("A stale preflight must not overwrite the newly created target");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("later.txt"));}
        assertEquals("existing",readString(raced.toPath()));assertEquals("new",readString(source.toPath()));
        List<FileStore.Node> result=store.copyAll(List.of(FileStore.local(source)),destination,false,FileStore.ConflictPolicy.KEEP_BOTH,store.listNames(destination));
        assertEquals("existing",readString(raced.toPath()));assertEquals("new",readString(result.get(0).file.toPath()));assertNotEquals("later.txt",result.get(0).name);
        Uri tree=grant();Bundle seed=new Bundle();seed.putString("name","provider.txt");seed.putString("text","data");provider("seed",seed);
        provider("clearCounters",null); assertEquals(Set.of("provider.txt"),store.listNames(store.document(tree)));
        assertEquals(1,provider("stats",null).getInt("childQueries"));assertEquals(0,provider("stats",null).getInt("readOpens"));
    }
    @Test public void largeDirectoryNamesOnlyBenchmark() throws Exception {
        File directory=new File(work,"many");assertTrue(directory.mkdir());
        for(int i=0;i<2000;i++)assertTrue(new File(directory,"entry-"+i+".txt").createNewFile());
        FileStore store=new FileStore(context);FileStore.Node node=FileStore.directory(directory,false);
        store.list(node);store.listNames(node);
        long full=0,names=0;
        for(int repeat=0;repeat<5;repeat++){
            long start=SystemClock.elapsedRealtimeNanos();assertEquals(2000,store.list(node).size());full+=SystemClock.elapsedRealtimeNanos()-start;
            start=SystemClock.elapsedRealtimeNanos();assertEquals(2000,store.listNames(node).size());names+=SystemClock.elapsedRealtimeNanos()-start;
        }
        android.util.Log.i("AuditPreflight", "2000 entries, 5 runs: full="+(full/5_000_000d)+" ms names="+(names/5_000_000d)+" ms");
    }
    static void writeString(java.nio.file.Path path,String value) throws IOException {Files.write(path,value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static String readString(java.nio.file.Path path) throws IOException {return new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8);}
    void image(String name,int color) throws Exception {Bitmap b=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888);b.eraseColor(color);
        try(OutputStream out=new FileOutputStream(new File(work,name))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}}
    void awaitPicture(int index) {assertTrue(waitFor(()->{boolean[] ready={false};preview.onActivity(a->ready[0]=(int)field(a,"pictureIndex")==index && field(a,"displayedPicture")!=null);return ready[0];},10000));}
    void idle() throws Exception {InstrumentationRegistry.getInstrumentation().waitForIdleSync();ExecutorService[] worker={null};preview.onActivity(a->worker[0]=(ExecutorService)field(a,"pictureWorker"));worker[0].submit(()->{}).get(10,TimeUnit.SECONDS);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    Uri grant() throws Exception {CountDownLatch done=new CountDownLatch(1);Bundle[] result={null};
        Intent i=new Intent(CountingDocumentsProvider.AUTHORITY+".GRANT").setComponent(new ComponentName(InstrumentationRegistry.getInstrumentation().getContext().getPackageName(),CountingDocumentsProvider.GrantReceiver.class.getName())).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendOrderedBroadcast(i,null,new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){result[0]=getResultExtras(false);done.countDown();}},null,android.app.Activity.RESULT_CANCELED,null,null);
        assertTrue(done.await(10,TimeUnit.SECONDS));return Uri.parse(result[0].getString("uri"));}
    Bundle provider(String method,Bundle args){return context.getContentResolver().call(Uri.parse("content://"+CountingDocumentsProvider.AUTHORITY),method,null,args);}
    static float[] centre(ZoomImageView view){Matrix inverse=new Matrix();assertTrue(view.getImageMatrix().invert(inverse));float[] point={view.getWidth()/2f,view.getHeight()/2f};inverse.mapPoints(point);point[0]/=view.getDrawable().getIntrinsicWidth();point[1]/=view.getDrawable().getIntrinsicHeight();return point;}
    static View find(View view,String description){if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),description);if(result!=null)return result;}return null;}
    static void restore(SharedPreferences prefs,Map<String,?> values){var e=prefs.edit().clear();for(var entry:values.entrySet()){Object v=entry.getValue();String k=entry.getKey();if(v instanceof String s)e.putString(k,s);else if(v instanceof Boolean b)e.putBoolean(k,b);else if(v instanceof Integer i)e.putInt(k,i);else if(v instanceof Long l)e.putLong(k,l);else if(v instanceof Float f)e.putFloat(k,f);}e.commit();}
    static boolean waitFor(BooleanSupplier condition,long timeout){long end=SystemClock.uptimeMillis()+timeout;while(!condition.getAsBoolean()){if(SystemClock.uptimeMillis()>=end)return false;SystemClock.sleep(20);}return true;}
    static Object field(Object o,String name){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    static void call(Object o,String name,Class<?> type,Object value){try{var m=o.getClass().getDeclaredMethod(name,type);m.setAccessible(true);m.invoke(o,value);}catch(Exception e){throw new AssertionError(e);}}
}
