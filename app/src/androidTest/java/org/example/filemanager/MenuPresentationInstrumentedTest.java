package org.example.filemanager;

import android.app.Dialog;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MenuPresentationInstrumentedTest {
    Context context; UiDevice device; File work; Map<String, Map<String, ?>> prefs = new HashMap<>();
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for(String name:List.of("appearance","MainActivity","file-manager")) prefs.put(name,new HashMap<>(context.getSharedPreferences(name,0).getAll()));
        work=Files.createTempDirectory(context.getCacheDir().toPath(),"menus45-").toFile(); Files.write(new File(work,"菜单测试.txt").toPath(),"keep original".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        context.getSharedPreferences("MainActivity",0).edit().clear().putString("left",work.getPath()).putString("right",work.getPath()).commit();
        context.getSharedPreferences("file-manager",0).edit().putBoolean("root",false).putBoolean("animations",true).commit();
    }
    @After public void cleanup() throws Exception {
        for(var group:prefs.entrySet()){var edit=context.getSharedPreferences(group.getKey(),0).edit().clear();for(var entry:group.getValue().entrySet()){
            Object value=entry.getValue();String key=entry.getKey();if(value instanceof String v)edit.putString(key,v);else if(value instanceof Boolean v)edit.putBoolean(key,v);else if(value instanceof Integer v)edit.putInt(key,v);else if(value instanceof Long v)edit.putLong(key,v);else if(value instanceof Float v)edit.putFloat(key,v);
        }edit.commit();}
        try(var paths=Files.walk(work.toPath())){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    UiObject2 object(BySelector selector){UiObject2 value=device.wait(Until.findObject(selector),6000);assertNotNull(selector.toString(),value);return value;}
    void screenshot(String name){device.waitForIdle();SystemClock.sleep(250);assertTrue(device.takeScreenshot(new File(context.getExternalFilesDir(null),"0645-"+name+".png")));}
    View find(View root,String description){if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View match=find(group.getChildAt(i),description);if(match!=null)return match;}return null;}
    void checkTargets(View root){if(root.isClickable()&&root.isEnabled()&&root.getVisibility()==View.VISIBLE){assertTrue(root.getContentDescription()+" width",root.getWidth()>=Ui.dp(context,48));assertTrue(root.getContentDescription()+" height",root.getHeight()>=Ui.dp(context,48));}if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)checkTargets(group.getChildAt(i));}
    void theme(String mode){context.getSharedPreferences("appearance",0).edit().putString("mode",mode).commit();}
    @Test public void settingsLightDarkAndCategoryRemainNavigable(){
        for(String mode:List.of("light","dark")){theme(mode);try(var screen=ActivityScenario.launch(SettingsActivity.class)){
            object(By.desc("设置分类：外观与显示"));screenshot("settings-"+mode);
            screen.onActivity(a->find(a.getWindow().getDecorView(),"设置分类：外观与显示").performClick());
            object(By.text("主题颜色"));screenshot("appearance-"+mode);
            device.pressBack();object(By.desc("设置分类：外观与显示"));
        }}
    }
    @Test public void fileActionsHaveTouchTargetsAndCancellationClearsSelection(){
        for(String mode:List.of("light","dark")){theme(mode);try(var screen=ActivityScenario.launch(MainActivity.class)){
            object(By.text("菜单测试.txt")).longClick();object(By.desc("文件操作 复制"));screenshot("file-menu-"+mode);
            screen.onActivity(a->{Dialog menu=(Dialog)ManualAlignmentInstrumentedTest.field(a,"fileMenu");assertNotNull(menu);assertEquals(android.view.Gravity.CENTER,menu.getWindow().getAttributes().gravity);checkTargets(menu.getWindow().getDecorView());assertNotNull(find(menu.getWindow().getDecorView(),"关闭文件操作"));});
            object(By.desc("关闭文件操作")).click();screen.onActivity(a->{Object pane=ManualAlignmentInstrumentedTest.field(a,"active");assertTrue(((Set<?>)ManualAlignmentInstrumentedTest.field(pane,"selected")).isEmpty());});
        }}
    }
    @Test public void drawerAndMorePreserveNavigation(){
        for(String mode:List.of("light","dark")){theme(mode);try(var screen=ActivityScenario.launch(MainActivity.class)){
            object(By.desc("位置与工具")).click();object(By.text("内部存储"));screenshot("drawer-"+mode);
            device.pressBack();screen.onActivity(a->ManualAlignmentInstrumentedTest.invoke(a,"more"));object(By.desc("搜索"));screenshot("more-"+mode);
            object(By.desc("搜索")).click();assertTrue(device.wait(Until.hasObject(By.textContains("搜索")),5000));
        }}
    }
    @Test public void chosenDestinationUsesConflictPanel()throws Exception{
        theme("light");File destination=new File(work,"目标");assertTrue(destination.mkdir());Files.write(new File(destination,"菜单测试.txt").toPath(),"existing".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try(var screen=ActivityScenario.launch(MainActivity.class)){
            object(By.text("菜单测试.txt")).longClick();UiObject2 list=object(By.desc("文件操作列表"));
            for(int n=0;n<4&&!device.hasObject(By.text("复制到指定目录…"));n++)list.scroll(Direction.DOWN,.7f);
            object(By.text("复制到指定目录…")).click();object(By.clazz("android.widget.EditText")).setText(destination.getPath());object(By.text("复制")).click();
            object(By.textContains("另存"));assertEquals("existing",new String(Files.readAllBytes(new File(destination,"菜单测试.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8));device.pressBack();
        }
    }
}
