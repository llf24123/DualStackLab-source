package org.example.filemanager;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.*;
import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Regression coverage for the four independently reproduced 0.6.35 audit findings. */
@RunWith(AndroidJUnit4.class)
public class WholeAppAuditInstrumentedTest {
    Context context; File work; ActivityScenario<PreviewActivity> screen;
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        work=Files.createTempDirectory(context.getCacheDir().toPath(),"whole-audit-").toFile();
    }
    @After public void cleanup() throws Exception {
        if(screen!=null)screen.close();
        context.getSharedPreferences("PreviewActivity",0).edit().putBoolean("thumbnails",false).commit();
        new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void sharingSameNamedFilesMustKeepBothContents() throws Exception {
        File a=new File(work,"a"),b=new File(work,"b");assertTrue(a.mkdir());assertTrue(b.mkdir());
        String name=work.getName()+".txt";
        File first=new File(a,name),second=new File(b,name);
        Files.write(first.toPath(),"first-file".getBytes());Files.write(second.toPath(),"second-file".getBytes());
        File sharedA=null,sharedB=null;
        try {
            sharedA=ApkInstall.stageShared(context,FileStore.local(first),".txt");
            sharedB=ApkInstall.stageShared(context,FileStore.local(second),".txt");
            assertEquals("The first share must not be replaced by a later same-name share","first-file",new String(Files.readAllBytes(sharedA.toPath())));
            assertNotEquals(sharedA,sharedB);
        } finally { if(sharedA!=null)Files.deleteIfExists(sharedA.toPath());if(sharedB!=null)Files.deleteIfExists(sharedB.toPath()); }
    }
    @Test public void cancelledBlockedSearchMustNotBlockNextSearch() throws Exception {
        Uri root=grant();Bundle seed=new Bundle();seed.putString("name","blocked.txt");seed.putString("text","needle");provider("seed",seed);
        Bundle config=new Bundle();config.putString("blockReadName","blocked.txt");provider("configure",config);
        File nextRoot=new File(work,"next");assertTrue(nextRoot.mkdir());Files.write(new File(nextRoot,"target.txt").toPath(),"next".getBytes());
        SearchJob previous=null,next=null;
        try {
            previous=SearchJob.start(context,new FileStore(context).document(root),"needle",Pattern.compile("needle"),true);
            assertTrue(waitFor(()->provider("readState",null).getBoolean("prefixWritten"),5000));
            previous.cancel();
            next=SearchJob.start(context,FileStore.directory(nextRoot,false),"target",Pattern.compile("target"),false);
            SearchJob expected=next;
            assertTrue("A new local search remains queued behind the cancelled provider read",waitFor(()->expected.snapshot().finished(),1500));
            assertEquals(1,next.snapshot().matches().size());
        } finally {
            provider("releaseRead",null);
            if(previous!=null){previous.cancel();SearchJob p=previous;waitFor(()->p.snapshot().finished(),5000);}
            if(next!=null){SearchJob n=next;waitFor(()->n.snapshot().finished(),5000);}
        }
    }
    @Test public void uncachedThumbnailMustHonorExifOrientation() throws Exception {
        for(int i=1;i<=4;i++)image("0"+i+".jpg",true);
        context.getSharedPreferences("PreviewActivity",0).edit().putBoolean("thumbnails",true).commit();
        launch(new File(work,"01.jpg")); idle();
        screen.onActivity(a->{
            LinearLayout strip=(LinearLayout)field(a,"thumbnailItems");assertEquals(4,strip.getChildCount());
            Bitmap far=((BitmapDrawable)((ImageView)strip.getChildAt(3)).getDrawable()).getBitmap();
            assertTrue("Uncached EXIF-rotated photo should have a portrait thumbnail",far.getHeight()>far.getWidth());
        });
    }
    @Test public void enteringFullscreenMustPreserveZoom() throws Exception {
        image("01.jpg",false);context.getSharedPreferences("PreviewActivity",0).edit().putBoolean("thumbnails",false).commit();
        launch(new File(work,"01.jpg"));idle();int[] height={0};
        screen.onActivity(a->{ZoomImageView v=(ZoomImageView)field(a,"imageView");v.zoom(2.5f,100,100);height[0]=v.getHeight();call(a,"setPictureFullscreen",boolean.class,true);});
        boolean[] resized={false};assertTrue(waitFor(()->{screen.onActivity(a->resized[0]=((ZoomImageView)field(a,"imageView")).getHeight()!=height[0]);return resized[0];},5000));
        screen.onActivity(a->assertEquals("Fullscreen should preserve the inspected detail",2.5f,((ZoomImageView)field(a,"imageView")).zoomFactor(),.01f));
    }
    void image(String name,boolean rotate) throws Exception {
        File file=new File(work,name);Bitmap b=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888);b.eraseColor(Color.RED);
        try(OutputStream out=new FileOutputStream(file)){assertTrue(b.compress(Bitmap.CompressFormat.JPEG,95,out));}finally{b.recycle();}
        if(rotate){ExifInterface exif=new ExifInterface(file);exif.setAttribute(ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();}
    }
    void launch(File file){Intent intent=new Intent(context,PreviewActivity.class);FileStore.local(file).put(intent);intent.putExtra("kind","image");screen=ActivityScenario.launch(intent);
        boolean[] ready={false};assertTrue(waitFor(()->{screen.onActivity(a->ready[0]=field(a,"bitmap")!=null);return ready[0];},10000));}
    void idle() throws Exception {InstrumentationRegistry.getInstrumentation().waitForIdleSync();ExecutorService[] worker={null};screen.onActivity(a->worker[0]=(ExecutorService)field(a,"pictureWorker"));worker[0].submit(()->{}).get(10,TimeUnit.SECONDS);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    Uri grant() throws Exception {CountDownLatch done=new CountDownLatch(1);Bundle[] result={null};
        Intent intent=new Intent(CountingDocumentsProvider.AUTHORITY+".GRANT").setComponent(new ComponentName(InstrumentationRegistry.getInstrumentation().getContext().getPackageName(),CountingDocumentsProvider.GrantReceiver.class.getName())).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendOrderedBroadcast(intent,null,new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){result[0]=getResultExtras(false);done.countDown();}},null,android.app.Activity.RESULT_CANCELED,null,null);
        assertTrue(done.await(10,TimeUnit.SECONDS));return Uri.parse(result[0].getString("uri"));}
    Bundle provider(String method,Bundle args){return context.getContentResolver().call(Uri.parse("content://"+CountingDocumentsProvider.AUTHORITY),method,null,args);}
    static boolean waitFor(BooleanSupplier condition,long timeout){long end=SystemClock.uptimeMillis()+timeout;while(!condition.getAsBoolean()){if(SystemClock.uptimeMillis()>=end)return false;SystemClock.sleep(20);}return true;}
    static Object field(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    static void call(Object o,String n,Class<?> type,Object value){try{var m=o.getClass().getDeclaredMethod(n,type);m.setAccessible(true);m.invoke(o,value);}catch(Exception e){throw new AssertionError(e);}}
}
