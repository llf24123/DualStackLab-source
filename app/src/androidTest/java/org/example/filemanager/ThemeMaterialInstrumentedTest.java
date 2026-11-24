package org.example.filemanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.widget.ListView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ThemeMaterialInstrumentedTest {
    private final Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private UiObject2 find(BySelector sel) {
        UiObject2 node=device.wait(Until.findObject(sel),6000);assertNotNull(sel.toString(),node);return node;
    }
    private Object field(Object target,String name){return Iteration38InstrumentedTest.field(target,name);}
    @Test public void pickerCancelsSurvivesRotationAndAppliesWithoutLosingBrowserState() throws Exception {
        SharedPreferences theme=ctx.getSharedPreferences("appearance",0),main=ctx.getSharedPreferences("MainActivity",0),settings=FileManagerSettings.prefs(ctx);
        String mode=theme.getString("mode",null),material=theme.getString("material",null),left=main.getString("left",null),right=main.getString("right",null);
        Integer accent=settings.contains("accent")?settings.getInt("accent",0):null;
        File dir=Files.createTempDirectory(ctx.getCacheDir().toPath(),"theme-browser").toFile();
        try {
            for(int i=0;i<88;i++)assertTrue(new File(dir,"theme-"+String.format(java.util.Locale.ROOT,"%02d",i)+".txt").createNewFile());
            main.edit().putString("left",dir.getPath()).putString("right",dir.getPath()).commit();
            theme.edit().putString("mode","light").remove("material").commit();settings.edit().putInt("accent",2).commit();
            try(var screen=ActivityScenario.launch(MainActivity.class)){
                find(By.text("theme-00.txt"));screen.onActivity(a->Ui.chooseTheme(a));
                find(By.desc("材质：暖纸")).click();find(By.text("深色")).click();screen.recreate();
                assertTrue(find(By.desc("材质：暖纸")).isChecked());assertTrue(find(By.text("深色")).isChecked());
                find(By.text("取消")).click();assertEquals("light",Ui.themeMode(ctx));assertEquals(ThemeMaterial.CLASSIC,ThemeMaterial.current(ctx));
                screen.onActivity(a->{Object pane=field(a,"left");ListView list=(ListView)field(pane,"list");ManualAlignmentInstrumentedTest.invoke(pane,"toggle",((java.util.List<?>)field(pane,"entries")).get(39));list.setSelectionFromTop(40,3);});
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();int[] scroll={0,0};MainActivity[] original={null};
                screen.onActivity(a->{original[0]=a;ListView list=(ListView)field(field(a,"left"),"list");scroll[0]=list.getFirstVisiblePosition();scroll[1]=list.getChildAt(0).getTop();assertTrue(scroll[0]>20);Ui.chooseTheme(a);});
                find(By.desc("材质：潮汐渐变")).click();find(By.text("深色")).click();find(By.text("应用")).click();
                assertTrue(device.wait(Until.gone(By.text("主题外观")),6000));
                screen.onActivity(a->{assertSame(original[0],a);assertTrue(Ui.dark);assertEquals(ThemeMaterial.TIDE,Ui.material);assertEquals(0xffcbb1ee,Ui.ACCENT);
                    Object pane=field(a,"left");ListView list=(ListView)field(pane,"list");assertEquals(dir.getPath(),((FileStore.Node)field(pane,"location")).path());
                    assertEquals(1,((Set<?>)field(pane,"selected")).size());assertEquals(scroll[0],list.getFirstVisiblePosition());assertEquals(scroll[1],list.getChildAt(0).getTop());});
                screen.recreate();screen.onActivity(a->{assertTrue(Ui.dark);assertEquals(ThemeMaterial.TIDE,Ui.material);});
            }
        } finally {
            theme.edit().putString("mode",mode).putString("material",material).commit();main.edit().putString("left",left).putString("right",right).commit();
            var restore=settings.edit();if(accent==null)restore.remove("accent");else restore.putInt("accent",accent);restore.commit();
            new FileStore(ctx).delete(FileStore.local(dir));
        }
    }
    @Test public void textStaysLegibleAcrossAllMaterialSurfaces(){
        for(var s:ThemeMaterial.values())for(boolean dark:new boolean[]{false,true}){
            var t=s.tones(dark);for(int bg:new int[]{t.surface(),t.panel()})for(int fg:new int[]{t.text(),t.muted()})
                assertTrue(s.id+" content",contrast(fg,bg)>=4.5);
            for(int bg:new int[]{t.ink(),t.middle(),t.end()})assertTrue(s.id+" header",contrast(s==ThemeMaterial.CLASSIC?0xffc0d0cd:0xffe3ecef,bg)>=4.5);
        }
    }
    private static double contrast(int a,int b){double x=Color.luminance(a),y=Color.luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
}
