package org.example.filemanager;

import android.content.*;
import android.graphics.*;
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
public class ImagePagingPerformanceInstrumentedTest {
    Context context; File work; ActivityScenario<PreviewActivity> screen;
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        work=Files.createTempDirectory(context.getCacheDir().toPath(),"paging-perf-").toFile();
        context.getSharedPreferences("PreviewActivity",0).edit().putBoolean("thumbnails",false).commit();
    }
    @After public void cleanup() throws Exception {
        if(screen!=null)screen.close();new FileStore(context).delete(FileStore.local(work));
    }
    @Test public void localLargePhotosReuseDecodedNeighbours() throws Exception { benchmark(false); }
    @Test public void zipLargePhotosReuseDecodedNeighbours() throws Exception { benchmark(true); }
    private void benchmark(boolean archive) throws Exception {
        File photos=new File(work,"photos");assertTrue(photos.mkdir());
        Bitmap b=Bitmap.createBitmap(4000,3000,Bitmap.Config.ARGB_8888);int[] row=new int[4000];Random random=new Random(34);
        for(int y=0;y<3000;y++){for(int x=0;x<4000;x++)row[x]=0xff000000|random.nextInt(0xffffff);b.setPixels(row,0,4000,0,y,4000,1);}
        File first=new File(photos,"01.jpg");try(OutputStream out=new FileOutputStream(first)){assertTrue(b.compress(Bitmap.CompressFormat.JPEG,90,out));}finally{b.recycle();}
        Files.copy(first.toPath(),new File(photos,"02.jpg").toPath());Files.copy(first.toPath(),new File(photos,"03.jpg").toPath());
        FileStore.Node selected=FileStore.local(first);
        if(archive){File zip=new File(work,"photos.zip");try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zip))){for(File f:Objects.requireNonNull(photos.listFiles())){out.putNextEntry(new ZipEntry(f.getName()));Files.copy(f.toPath(),out);out.closeEntry();}}
            selected=FileStore.archiveNode(FileStore.local(zip),"01.jpg",false,first.length(),0);}
        Intent intent=new Intent(context,PreviewActivity.class);selected.put(intent);intent.putExtra("kind","image");
        screen=ActivityScenario.launch(intent);await(0);idle();
        long[] times=new long[4];
        for(int i=0;i<4;i++){
            int index=(i%2==0)?1:0;Object[] cached={null};
            screen.onActivity(a->{Map<?,?> map=(Map<?,?>)field(a,"pictureCache");List<FileStore.Node> nodes=(List<FileStore.Node>)field(a,"pictures");cached[0]=map.get(nodes.get(index).key());});
            long start=SystemClock.elapsedRealtime();screen.onActivity(a->call(a,"showPicture",index));await(index);times[i]=SystemClock.elapsedRealtime()-start;
            if(InstrumentationRegistry.getArguments().getString("requireCache","false").equals("true")){
                assertNotNull("Neighbour should remain cached",cached[0]);Bitmap expected=(Bitmap)field(field(cached[0],"picture"),"bitmap");
                screen.onActivity(a->assertSame("Paging should reuse decoded bitmap",expected,field(a,"bitmap")));
            }
            idle();
        }
        String report=(archive?"zip":"local")+" timesMs="+Arrays.toString(times)+" encodedBytes="+first.length();
        android.util.Log.i("ImagePagingTiming",report);
        Files.write(new File(context.getExternalFilesDir(null),"image-paging-"+(archive?"zip":"local")+".txt").toPath(),report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    void idle() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();ExecutorService[] worker={null};screen.onActivity(a->worker[0]=(ExecutorService)field(a,"pictureWorker"));worker[0].submit(()->{}).get(20,TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    void await(int index){boolean[] ready={false};long end=SystemClock.uptimeMillis()+20000;
        while(!ready[0]&&SystemClock.uptimeMillis()<end){screen.onActivity(a->{String status=((TextView)field(a,"status")).getText().toString();ready[0]=status.startsWith((index+1)+" / ")&&!status.contains("读取中")&&!status.contains("无法")&&field(a,"bitmap")!=null;});if(!ready[0])SystemClock.sleep(5);}assertTrue("Picture was not ready",ready[0]);}
    static Object field(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    static void call(Object o,String n,int v){try{var m=o.getClass().getDeclaredMethod(n,int.class);m.setAccessible(true);m.invoke(o,v);}catch(Exception e){throw new AssertionError(e);}}
}
