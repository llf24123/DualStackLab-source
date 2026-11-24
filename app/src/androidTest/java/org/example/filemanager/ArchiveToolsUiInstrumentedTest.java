package org.example.filemanager;

import android.content.*;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.zip.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArchiveToolsUiInstrumentedTest {
    private Context context;private UiDevice device;private File work;private String originalTheme;private boolean hadTheme;
    private String originalMaterial;
    @Before public void setup()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        work=Files.createTempDirectory(context.getCacheDir().toPath(),"archive-tools-ui-").toFile();
        SharedPreferences prefs=context.getSharedPreferences("appearance",0);hadTheme=prefs.contains("mode");originalTheme=prefs.getString("mode","system");originalMaterial=prefs.getString("material",null);prefs.edit().putString("mode","light").putString("material","classic").commit();
    }
    @After public void cleanup()throws Exception{
        var edit=context.getSharedPreferences("appearance",0).edit();if(hadTheme)edit.putString("mode",originalTheme);else edit.remove("mode");edit.putString("material",originalMaterial).commit();
        try(var paths=Files.walk(work.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private File archive(String name)throws Exception{
        File archive=new File(work,name);try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(archive))){out.putNextEntry(new ZipEntry("content.txt"));out.write("original content".getBytes(StandardCharsets.UTF_8));out.closeEntry();}return archive;
    }
    private ActivityScenario<ArchiveToolsActivity> launch(File archive){Intent intent=new Intent(context,ArchiveToolsActivity.class);FileStore.local(archive).put(intent);return ActivityScenario.launch(intent);}
    private Object field(Object target,String name){return Iteration38InstrumentedTest.field(target,name);}
    private void ready(ActivityScenario<ArchiveToolsActivity> scenario){waitFor(scenario,a->field(a,"tools")!=null&&!(Boolean)field(a,"busy"));}
    private void waitFor(ActivityScenario<ArchiveToolsActivity> scenario,Predicate<ArchiveToolsActivity> condition){
        long until=SystemClock.uptimeMillis()+15000;boolean[] done={false};while(SystemClock.uptimeMillis()<until&&!done[0]){scenario.onActivity(a->done[0]=condition.test(a));if(!done[0])SystemClock.sleep(30);}assertTrue("archive tools timeout",done[0]);
    }
    private UiObject2 object(BySelector selector){UiObject2 object=device.wait(Until.findObject(selector),5000);assertNotNull(selector.toString(),object);return object;}
    private Button button(ArchiveToolsActivity activity,String name){return (Button)field(activity,name);}
    @Test public void apkCommentConfirmationCancelAndSaveAreRealActions()throws Exception{
        File archive=archive("application.apk");byte[] original=Files.readAllBytes(archive.toPath());
        try(var screen=launch(archive)){
            ready(screen);screen.onActivity(a->{((EditText)field(a,"comment")).setText("已编辑注释");button(a,"save").performClick();});
            object(By.text("确认修改应用包注释"));object(By.textContains("v2/v3"));object(By.text("取消")).click();
            assertArrayEquals(original,Files.readAllBytes(archive.toPath()));
            screen.onActivity(a->{assertEquals("已编辑注释",((EditText)field(a,"comment")).getText().toString());button(a,"save").performClick();});
            object(By.text("修改并保存")).click();waitFor(screen,a->((TextView)field(a,"report")).getText().toString().equals("注释已保存"));
            try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(archive)){assertEquals("已编辑注释",zip.getComment());}
            assertEquals("original content",new String(ArchiveOps.bytes(archive,"content.txt",1024),StandardCharsets.UTF_8));
        }
    }
    @Test public void unsavedCommentAndReportSurviveRecreationAndThemeChange()throws Exception{
        File archive=archive("restore.zip");
        try(var screen=launch(archive)){
            ready(screen);screen.onActivity(a->button(a,"verify").performClick());waitFor(screen,a->((TextView)field(a,"report")).getText().toString().contains("校验完成"));
            String[] report={null};screen.onActivity(a->{EditText comment=(EditText)field(a,"comment");comment.setText("尚未保存的中文草稿");comment.setSelection(2,5);report[0]=((TextView)field(a,"report")).getText().toString();});
            screen.recreate();ready(screen);screen.onActivity(a->{EditText comment=(EditText)field(a,"comment");assertEquals("尚未保存的中文草稿",comment.getText().toString());assertEquals(2,comment.getSelectionStart());assertEquals(5,comment.getSelectionEnd());assertEquals(report[0],((TextView)field(a,"report")).getText().toString());Ui.chooseTheme(a);});
            object(By.text("深色")).click();object(By.text("应用")).click();waitFor(screen,a->Ui.dark&&field(a,"tools")!=null&&!(Boolean)field(a,"busy"));screen.onActivity(a->{assertTrue(Ui.dark);assertEquals("尚未保存的中文草稿",((EditText)field(a,"comment")).getText().toString());assertEquals(report[0],((TextView)field(a,"report")).getText().toString());List<?> cards=(List<?>)field(a,"cards");assertEquals(0xff232b2b,((GradientDrawable)((View)cards.get(0)).getBackground()).getColor().getDefaultColor());});
            try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(archive)){assertTrue(zip.getComment()==null||zip.getComment().isEmpty());}
        }
    }
    @Test public void stopCancelsQueuedIntegrityAndKeepsSource()throws Exception{
        File archive=archive("stop.zip");byte[] original=Files.readAllBytes(archive.toPath());CountDownLatch occupied=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var screen=launch(archive)){
            ready(screen);Tasks.FILES.execute(()->{occupied.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(occupied.await(3,TimeUnit.SECONDS));
            screen.onActivity(a->{button(a,"verify").performClick();assertEquals(View.VISIBLE,button(a,"stop").getVisibility());button(a,"stop").performClick();assertFalse(button(a,"stop").isEnabled());});
            release.countDown();waitFor(screen,a->!(Boolean)field(a,"busy"));screen.onActivity(a->{assertTrue(((TextView)field(a,"report")).getText().toString().contains("已停止"));assertTrue(button(a,"verify").isEnabled());assertEquals(View.GONE,button(a,"stop").getVisibility());});
            assertArrayEquals(original,Files.readAllBytes(archive.toPath()));
        }finally{release.countDown();}
    }
}
