package org.example.filemanager;

import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.widget.ListView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Regression coverage for the 0.6.32 review findings. */
@RunWith(AndroidJUnit4.class)
public class ReviewFixesInstrumentedTest {
    Context context; File root; FileStore store; ActivityScenario<?> screen;
    Map<String, ?> oldPreferences;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); store = new FileStore(context);
        root = Files.createTempDirectory(context.getCacheDir().toPath(), "review-0632-").toFile();
        oldPreferences = new HashMap<>(context.getSharedPreferences("MainActivity", 0).getAll());
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(
                "appops set " + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
    }
    @After public void cleanup() throws Exception {
        if (screen != null) screen.close();
        var edit = context.getSharedPreferences("MainActivity", 0).edit().clear();
        for (var entry : oldPreferences.entrySet()) {
            Object v = entry.getValue(); String k = entry.getKey();
            if (v instanceof String x) edit.putString(k,x); else if (v instanceof Boolean x) edit.putBoolean(k,x);
            else if (v instanceof Integer x) edit.putInt(k,x); else if (v instanceof Long x) edit.putLong(k,x);
        }
        edit.commit(); store.delete(FileStore.local(root));
    }
    @Test public void archiveBatchFailureReportsPublishedOutputWithoutClaimingMoveCompleted() throws Exception {
        File zip = new File(root,"source.zip"), target = new File(root,"target"); assertTrue(target.mkdir());
        zip(zip, Map.of("first.txt",new byte[]{1}));
        Exception caught = null;
        try {
            store.copyAll(List.of(FileStore.archiveNode(FileStore.local(zip),"first.txt",false,1,0),
                    FileStore.archiveNode(FileStore.local(zip),"missing.txt",false,1,0)), FileStore.directory(target,false),true);
        } catch (Exception error) { caught = error; }
        assertTrue(String.valueOf(caught), caught instanceof Tasks.PartialTransferException);
        Tasks.PartialTransferException partial = (Tasks.PartialTransferException)caught;
        assertTrue(partial.completed.isEmpty()); assertEquals(2, partial.remaining.size());
        assertEquals(1, partial.published.size()); assertNotNull(partial.archiveMove);
        assertTrue(new File(target,"first.txt").isFile());
        assertTrue(ArchiveOps.entryNames(zip).contains("first.txt"));
    }
    @Test public void overwriteMovePreservesItsContainingArchive() throws Exception {
        File zip = new File(root,"bundle.zip"); byte[] replacement = {1,2,3};
        zip(zip, Map.of("bundle.zip",replacement,"keep.txt",new byte[]{9}));
        byte[] original = Files.readAllBytes(zip.toPath());
        Exception caught = null;
        try {
            store.copyAll(List.of(FileStore.archiveNode(FileStore.local(zip),"bundle.zip",false,3,0)),
                    FileStore.directory(root,false),true,FileStore.ConflictPolicy.OVERWRITE,Set.of("bundle.zip"));
        } catch (Exception error) { caught = error; }
        assertNotNull("Self-container overwrite must be rejected before writing", caught);
        assertArrayEquals(original, Files.readAllBytes(zip.toPath()));
        assertTrue(ArchiveOps.entryNames(zip).contains("keep.txt"));
    }
    @Test public void localImageCacheReloadsAfterFileIsReplaced() throws Exception {
        File photo = new File(root,"photo.png"); picture(photo, Color.RED);
        picture(new File(root,"z-next.png"),Color.GREEN);
        Intent intent = new Intent(context,PreviewActivity.class); FileStore.local(photo).put(intent); intent.putExtra("kind","image");
        ActivityScenario<PreviewActivity> preview = ActivityScenario.launch(intent); screen=preview;
        awaitPicture(preview,Color.RED);
        preview.onActivity(a -> call(a,"changePicture",new Class<?>[]{int.class},1));
        awaitPicture(preview,Color.GREEN);
        File replacement = new File(root,"replace.png"); picture(replacement,Color.BLUE);
        Files.move(replacement.toPath(),photo.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        preview.onActivity(a -> call(a,"changePicture",new Class<?>[]{int.class},-1));
        awaitPicture(preview,Color.BLUE);
        preview.onActivity(a -> assertEquals("Replaced file must be decoded again", Color.BLUE,((Bitmap)get(a,"bitmap")).getPixel(0,0)));
        Bitmap actual = BitmapFactory.decodeFile(photo.getPath()); assertEquals(Color.BLUE,actual.getPixel(0,0)); actual.recycle();
    }
    @Test public void asynchronousFilterRestoresScrollPositionOnRecreation() throws Exception {
        for(int i=0;i<200;i++) Files.write(new File(root,String.format(Locale.ROOT,"f%03d.txt",i)).toPath(),new byte[]{1});
        context.getSharedPreferences("MainActivity",0).edit().clear().putString("left",root.getPath()).putString("right",root.getPath())
                .putString("filter-left",".*").putBoolean("regex-left",true).commit();
        ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class); screen=main;
        awaitEntries(main,200);
        main.onActivity(a -> ((ListView)get(get(a,"left"),"list")).setSelectionFromTop(80,0));
        SystemClock.sleep(250); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main.onActivity(a -> assertTrue(((ListView)get(get(a,"left"),"list")).getFirstVisiblePosition()>=75));
        main.recreate(); awaitEntries(main,200); SystemClock.sleep(250);
        main.onActivity(a -> {
            int restored=((ListView)get(get(a,"left"),"list")).getFirstVisiblePosition();
            assertTrue("Scroll position was not restored: " + restored,restored>=75);
        });
    }
    @Test public void copiedArchiveOutputsAreReusedOnRetry() throws Exception {
        File archive = new File(root,"retry.zip"), target = new File(root,"target"); assertTrue(target.mkdir());
        zip(archive,Map.of("a.txt",new byte[]{1},"b.txt",new byte[]{2},"keep.txt",new byte[]{3}));
        FileStore.Node container=FileStore.local(archive);
        List<FileStore.Node> sources=List.of(FileStore.archiveNode(container,"a.txt",false,1,0),FileStore.archiveNode(container,"b.txt",false,1,0));
        Tasks.PartialTransferException partial;
        try {
            store.moveArchiveBatch(sources,FileStore.local(target),node -> { throw new IOException("injected interruption after publication"); });
            throw new AssertionError("Expected injected failure");
        } catch (Tasks.PartialTransferException failure) { partial=failure; }
        assertEquals(1,partial.published.size()); assertEquals(3,ArchiveOps.entryNames(archive).size());
        String firstIdentity=FileStore.fileIdentity(android.system.Os.stat(new File(target,"a.txt").getPath()));
        List<FileStore.Node> outputs=store.resumeArchiveMove(partial.archiveMove);
        assertEquals(2,outputs.size()); assertEquals(2,Objects.requireNonNull(target.list()).length);
        assertEquals(firstIdentity,FileStore.fileIdentity(android.system.Os.stat(new File(target,"a.txt").getPath())));
        assertEquals(List.of("keep.txt"),ArchiveOps.entryNames(archive));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(new File(target,"a.txt").toPath()));
        assertArrayEquals(new byte[]{2},Files.readAllBytes(new File(target,"b.txt").toPath()));
    }
    @Test public void retryRejectsModifiedPublishedFileAndPreservesArchive() throws Exception {
        File archive = new File(root,"retry.zip"), target = new File(root,"target"); assertTrue(target.mkdir());
        zip(archive,Map.of("a.txt",new byte[]{1},"b.txt",new byte[]{2}));
        byte[] original=Files.readAllBytes(archive.toPath()); FileStore.Node container=FileStore.local(archive);
        Tasks.PartialTransferException partial;
        try {
            store.moveArchiveBatch(List.of(FileStore.archiveNode(container,"a.txt",false,1,0),FileStore.archiveNode(container,"b.txt",false,1,0)),
                    FileStore.local(target),node -> { throw new IOException("injected"); });
            throw new AssertionError();
        } catch(Tasks.PartialTransferException failure){ partial=failure; }
        Files.write(new File(target,"a.txt").toPath(),new byte[]{9});
        try {store.resumeArchiveMove(partial.archiveMove);fail("Changed output accepted");}catch(IOException expected){}
        assertArrayEquals(original,Files.readAllBytes(archive.toPath()));
        assertEquals(List.of("a.txt"),Arrays.asList(Objects.requireNonNull(target.list())));
    }
    @Test public void overwriteCopyRejectsAliasPathToItsContainer() throws Exception {
        File archive=new File(root,"archive.zip"), child=new File(root,"child");assertTrue(child.mkdir());
        File aliasParent=new File(child,"..");
        zip(archive,Map.of("archive.zip",new byte[]{1},"keep",new byte[]{2}));
        byte[] original=Files.readAllBytes(archive.toPath());
        try {store.copyAll(List.of(FileStore.archiveNode(FileStore.local(archive),"archive.zip",false,1,0)),FileStore.local(aliasParent),false,
                FileStore.ConflictPolicy.OVERWRITE,Set.of("archive.zip"));fail("Alias overwrite accepted");}
        catch(IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("压缩包"));}
        assertArrayEquals(original,Files.readAllBytes(archive.toPath()));
    }
    @Test public void overwriteCopyRejectsNestedContainer() throws Exception {
        File inner=new File(root,"inner.zip");zip(inner,Map.of("inner.zip",new byte[]{1},"keep",new byte[]{2}));
        File outer=new File(root,"outer.zip");zip(outer,Map.of("inner.zip",Files.readAllBytes(inner.toPath()),"other",new byte[]{3}));
        byte[] original=Files.readAllBytes(outer.toPath());
        FileStore.Node parent=FileStore.archiveNode(FileStore.local(outer),"",true,0,0);
        FileStore.Node container=FileStore.archiveNode(FileStore.local(outer),"inner.zip",false,inner.length(),0);
        try {store.copyAll(List.of(FileStore.archiveNode(container,"inner.zip",false,1,0)),parent,false,
                FileStore.ConflictPolicy.OVERWRITE,Set.of("inner.zip","other"));fail("Nested overwrite accepted");}catch(IOException expected){}
        assertArrayEquals(original,Files.readAllBytes(outer.toPath()));
    }
    @Test public void digestCancellationStopsReadingAndClosesStream() throws Exception {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean(); int[] reads={0}; boolean[] closed={false};
        InputStream input=new InputStream(){
            public int read(){throw new AssertionError("Expected buffered reads");}
            public int read(byte[] buffer,int offset,int length){reads[0]++;cancelled.set(true);Arrays.fill(buffer,offset,offset+length,(byte)1);return length;}
            public void close(){closed[0]=true;}
        };
        try{FileIntegrity.calculate(input,cancelled);fail("Cancellation ignored");}catch(Tasks.CancelledException expected){}
        assertEquals(1,reads[0]);assertTrue(closed[0]);
    }
    @Test public void closingPropertiesCancelsQueuedDigest() throws Exception { propertiesCancellation(false); }
    @Test public void destroyingActivityCancelsQueuedDigest() throws Exception { propertiesCancellation(true); }
    private void propertiesCancellation(boolean destroy) throws Exception {
        var pool=(java.util.concurrent.ThreadPoolExecutor)Tasks.INTEGRITY;
        var started=new java.util.concurrent.CountDownLatch(2); var release=new java.util.concurrent.CountDownLatch(1);
        for(int i=0;i<2;i++) pool.submit(()->{started.countDown();try{release.await();}catch(InterruptedException ignored){}});
        assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
        try {
            File file=new File(root,"checksum.txt");Files.write(file.toPath(),new byte[]{1});
            context.getSharedPreferences("MainActivity",0).edit().clear().putString("left",root.getPath()).putString("right",root.getPath()).commit();
            ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class);screen=main;
            android.app.AlertDialog[] dialog={null};
            main.onActivity(a->dialog[0]=FileProperties.showChecksums(a,FileStore.local(file)));
            long deadline=SystemClock.uptimeMillis()+5000;
            while(pool.getQueue().isEmpty() && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(20);
            java.util.concurrent.Future<?> queued=(java.util.concurrent.Future<?>)pool.getQueue().peek();assertNotNull(queued);
            if(destroy){main.close();screen=null;}else main.onActivity(a->dialog[0].dismiss());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Dismiss/destroy must cancel pending file read",queued.isCancelled());
        } finally {release.countDown();}
    }
    @Test public void archiveFilterRestoresScrollAndSelectionAfterRecreation() throws Exception {
        File archive=new File(root,"gallery.zip");Map<String,byte[]> members=new LinkedHashMap<>();
        for(int i=0;i<200;i++) members.put(String.format(Locale.ROOT,"f%03d.txt",i),new byte[]{1});zip(archive,members);
        FileStore.Node folder=FileStore.archiveNode(FileStore.local(archive),"",true,0,0);
        context.getSharedPreferences("MainActivity",0).edit().clear().putString("left",folder.key()).putString("right",root.getPath())
                .putString("filter-left",".*").putBoolean("regex-left",true).commit();
        ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class);screen=main;awaitEntries(main,200);
        main.onActivity(a->{Object pane=get(a,"left");((ListView)get(pane,"list")).setSelectionFromTop(80,0);
            ((Set<String>)get(pane,"selected")).add(FileStore.archiveNode(FileStore.local(archive),"f080.txt",false,1,0).key());});
        SystemClock.sleep(200);main.recreate();awaitEntries(main,200);SystemClock.sleep(200);
        main.onActivity(a->{Object pane=get(a,"left");assertTrue(((ListView)get(pane,"list")).getFirstVisiblePosition()>=75);
            assertEquals(1,((Set<?>)get(pane,"selected")).size());});
    }
    @Test public void asynchronousFilterAppliesRequestedRevealAfterResultsArrive() throws Exception {
        for(int i=0;i<200;i++)Files.write(new File(root,String.format(Locale.ROOT,"f%03d.txt",i)).toPath(),new byte[]{1});
        context.getSharedPreferences("MainActivity",0).edit().clear().putString("left",root.getPath()).putString("right",root.getPath())
                .putString("filter-left",".*").putBoolean("regex-left",true).commit();
        ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class);screen=main;awaitEntries(main,200);
        main.onActivity(a->{Object pane=get(a,"left");try{var field=pane.getClass().getDeclaredField("reveal");field.setAccessible(true);field.set(pane,"f150.txt");}
            catch(Exception e){throw new AssertionError(e);}call(pane,"reload",new Class<?>[]{});});
        boolean[] visible={false};long deadline=SystemClock.uptimeMillis()+5000;
        while(!visible[0]&&SystemClock.uptimeMillis()<deadline){main.onActivity(a->{ListView list=(ListView)get(get(a,"left"),"list");visible[0]=list.getFirstVisiblePosition()>=145;});SystemClock.sleep(25);}
        assertTrue("Requested result must be visible after asynchronous filtering",visible[0]);
    }
    @Test public void archiveMoveRetrySurvivesActivityRecreationWithoutDuplicates() throws Exception {
        File archive=new File(root,"source.zip"),target=new File(root,"target");assertTrue(target.mkdir());
        zip(archive,Map.of("a.txt",new byte[]{1},"b.txt",new byte[]{2},"keep",new byte[]{3}));
        FileStore.Node container=FileStore.local(archive),destination=FileStore.local(target);
        List<FileStore.Node> sources=List.of(FileStore.archiveNode(container,"a.txt",false,1,0),FileStore.archiveNode(container,"b.txt",false,1,0));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        context.getSharedPreferences("MainActivity",0).edit().clear().putString("left",root.getPath()).putString("right",target.getPath()).commit();
        ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class);screen=main;
        Tasks.Job<List<FileStore.Node>> job=()->{
            entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("test timeout");
            return new FileStore(context).moveArchiveBatch(sources,destination,node->{throw new IOException("injected publication failure");});
        };
        try {
            main.onActivity(a->call(a,"mutate",new Class<?>[]{Tasks.Job.class,Tasks.Done.class,boolean.class,Tasks.Done.class,Tasks.Transfer.class},
                job,(Tasks.Done<List<FileStore.Node>>)result->fail("expected partial failure"),false,null,new Tasks.Transfer(sources,destination,true,false,true)));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));main.recreate();release.countDown();
            UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            assertNotNull(device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.textContains("来源尚未删除")),10000));
            var retry=device.findObject(androidx.test.uiautomator.By.text("重试未完成项"));assertNotNull(retry);retry.click();
            long deadline=SystemClock.uptimeMillis()+10000;
            while((Tasks.activeMutation()!=null||!new File(target,"b.txt").isFile())&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(25);
            assertNull(Tasks.activeMutation());assertEquals(2,Objects.requireNonNull(target.list()).length);
            assertEquals(List.of("keep"),ArchiveOps.entryNames(archive));
        } finally {release.countDown();}
    }
    @Test public void retryRejectsChangedSourceArchive() throws Exception {
        File archive=new File(root,"source.zip"),target=new File(root,"target");assertTrue(target.mkdir());
        zip(archive,Map.of("a",new byte[]{1},"b",new byte[]{2}));FileStore.Node container=FileStore.local(archive);
        Tasks.PartialTransferException partial;
        try {store.moveArchiveBatch(List.of(FileStore.archiveNode(container,"a",false,1,0),FileStore.archiveNode(container,"b",false,1,0)),FileStore.local(target),
            node->{throw new IOException("injected");});throw new AssertionError();}catch(Tasks.PartialTransferException failure){partial=failure;}
        zip(archive,Map.of("a",new byte[]{8},"b",new byte[]{9}));byte[] changed=Files.readAllBytes(archive.toPath());
        try{store.resumeArchiveMove(partial.archiveMove);fail("Changed source accepted");}catch(IOException expected){}
        assertArrayEquals(changed,Files.readAllBytes(archive.toPath()));assertEquals(List.of("a"),Arrays.asList(Objects.requireNonNull(target.list())));
    }
    static Object get(Object target,String name) {
        try {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
        catch(Exception e){throw new AssertionError(e);}
    }
    static void call(Object target,String name,Class<?>[] types,Object...args) {
        try {var m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);}
        catch(Exception e){throw new AssertionError(e);}
    }
    void awaitPicture(ActivityScenario<PreviewActivity> preview,int color) {
        boolean[] ready={false};long until=SystemClock.uptimeMillis()+10000;
        while(!ready[0]&&SystemClock.uptimeMillis()<until){preview.onActivity(a->{Bitmap b=(Bitmap)get(a,"bitmap");ready[0]=b!=null&&!b.isRecycled()&&b.getPixel(0,0)==color;});SystemClock.sleep(25);}
        assertTrue(ready[0]);
    }
    void awaitEntries(ActivityScenario<MainActivity> main,int count) {
        boolean[] ready={false};long until=SystemClock.uptimeMillis()+10000;
        while(!ready[0]&&SystemClock.uptimeMillis()<until){main.onActivity(a->ready[0]=((List<?>)get(get(a,"left"),"entries")).size()==count);SystemClock.sleep(25);}
        assertTrue(ready[0]);
    }
    static void picture(File target,int color)throws Exception{
        Bitmap b=Bitmap.createBitmap(60,40,Bitmap.Config.ARGB_8888);b.eraseColor(color);
        try(OutputStream out=new FileOutputStream(target)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
    }
    static void zip(File target,Map<String,byte[]> contents)throws Exception{
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(target))){for(var e:contents.entrySet()){out.putNextEntry(new ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}}
    }
}
