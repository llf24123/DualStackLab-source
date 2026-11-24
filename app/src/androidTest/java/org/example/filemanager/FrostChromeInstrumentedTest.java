package org.example.filemanager;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FrostChromeInstrumentedTest {
    private final Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private final SharedPreferences main=ctx.getSharedPreferences("MainActivity",0);
    private final SharedPreferences appearance=ctx.getSharedPreferences("appearance",0);
    private final Map<String,Object> saved=new HashMap<>();
    private ActivityScenario<MainActivity> screen;
    private String mode,material;
    private File root,last;

    @Before public void setUp() throws Exception {
        root=Files.createTempDirectory(ctx.getCacheDir().toPath(),"frost-chrome-").toFile();
        for(String key:new String[]{"left","right","home-left","home-right","filter-left","filter-right","sort-left","sort-right"})
            saved.put(key,main.getAll().get(key));
        mode=appearance.getString("mode",null);material=appearance.getString("material",null);
        SharedPreferences.Editor edit=main.edit();
        for(String side:new String[]{"left","right"})edit.putString(side,root.getPath()).putString("home-"+side,root.getPath())
                .remove("filter-"+side).remove("sort-"+side);
        edit.commit();
        for(int i=0;i<70;i++)assertTrue(new File(root,"item-"+i).mkdir());
        last=new File(root,"zz-final-folder");assertTrue(last.mkdir());
        appearance.edit().putString("mode","light").putString("material","frost").commit();
        screen=ActivityScenario.launch(MainActivity.class);
        node(By.desc("左窗口文件列表").hasDescendant(By.text("item-0")));
    }
    @After public void tearDown() throws Exception {
        if(screen!=null)screen.close();
        appearance.edit().putString("mode",mode).putString("material",material).commit();
        var edit=main.edit();
        for(var entry:saved.entrySet()){
            if(entry.getValue()==null)edit.remove(entry.getKey());
            else if(entry.getValue() instanceof String s)edit.putString(entry.getKey(),s);
            else if(entry.getValue() instanceof Integer n)edit.putInt(entry.getKey(),n);
        }
        edit.commit();if(root!=null)new FileStore(ctx).delete(FileStore.local(root));
    }

    @Test public void firstAndLastRowsClearTheGlassAndLastFolderOpens() {
        screen.onActivity(a->{
            ListView list=list(a);View first=list.getChildAt(0);assertNotNull(first);
            View path=(View)((View)ManualAlignmentInstrumentedTest.field(a,"title")).getParent();
            Rect top=bounds(path),item=bounds(first);
            assertEquals("Fresh directory starts with its parent row",0,list.getFirstVisiblePosition());
            assertTrue("Parent "+item+" must clear path "+top+"; measured inset "+list.getListPaddingTop(),item.top>=top.bottom);
            list.setSelection(list.getCount()-1);
        });
        UiObject2 pane=node(By.desc("左窗口文件列表").hasDescendant(By.text(last.getName())));
        Rect[] click={null};
        screen.onActivity(a->{
            ListView list=list(a);int position=list.getCount()-1;
            assertEquals(position,list.getLastVisiblePosition());View row=list.getChildAt(position-list.getFirstVisiblePosition());
            assertEquals(last.getName(),((FileStore.Node)list.getItemAtPosition(position)).name);
            Rect target=bounds(row),dock=bounds((View)ManualAlignmentInstrumentedTest.field(a,"actions"));
            assertTrue("The full last row must be above the floating dock: "+target+" / "+dock,target.bottom<=dock.top+1);
            click[0]=target;
        });
        assertTrue(pane.getVisibleBounds().contains(click[0].centerX(),click[0].centerY()));
        assertTrue(device.click(click[0].centerX(),click[0].centerY()));
        node(By.desc("双栈 Lab · "+last.getPath()));
    }

    @Test public void overflowIsAnchoredScrollableAndDismissesWithoutNavigating() {
        Rect anchor=node(By.desc("更多操作")).getVisibleBounds();node(By.desc("更多操作")).click();
        UiObject2 menu=node(By.desc("更多操作列表"));Rect popup=menu.getVisibleBounds();
        assertTrue("Menu should sit under its button: "+anchor+" / "+popup,popup.top>=anchor.centerY());
        assertTrue("Menu edge should align with the button",Math.abs(popup.right-anchor.right)<=Ui.dp(ctx,24));
        Rect[] window={null};screen.onActivity(a->{
            window[0]=bounds(a.getWindow().getDecorView());
            Dialog dialog=(Dialog)ManualAlignmentInstrumentedTest.field(a,"frostMore");assertTrue(dialog.isShowing());
            ViewGroup content=(ViewGroup)dialog.getWindow().findViewById(android.R.id.content);
            ArrayList<String> commands=new ArrayList<>();collectCommands(content,commands);
            Set<String> expected=new HashSet<>(Arrays.asList("刷新","搜索","全选","反选当前列表","过滤","排序方式","打开终端","隐藏文件","添加书签","设为首页","交换窗口","设置","退出","最近与书签","按条件选择","比较另一窗口","目录占用排行","重复文件查找"));
            assertEquals("Every local file action must be offered",expected,new HashSet<>(commands));
            assertEquals("Commands must not appear twice",expected.size(),commands.size());
        });
        assertTrue("Nonmodal-looking surface still needs an outside touch target",popup.left>window[0].left+Ui.dp(ctx,16));
        assertTrue(device.click((window[0].left+popup.left)/2,popup.centerY()));
        assertTrue(device.wait(Until.gone(By.desc("更多操作列表")),5000));
        node(By.desc("双栈 Lab · "+root.getPath()));
        node(By.desc("更多操作")).click();node(By.desc("更多操作列表"));device.pressBack();
        assertTrue(device.wait(Until.gone(By.desc("更多操作列表")),5000));
        node(By.desc("更多操作")).click();menu=node(By.desc("更多操作列表"));
        BySelector settings=By.desc("设置");
        for(int i=0;i<6 && menu.findObject(settings)==null;i++){menu.scroll(Direction.DOWN,.68f);menu=node(By.desc("更多操作列表"));}
        UiObject2 entry=menu.findObject(settings);assertNotNull("Settings must be reachable at the end",entry);entry.click();
        node(By.desc("设置分类：外观与显示"));device.pressBack();node(By.desc("更多操作"));
    }

    private UiObject2 node(BySelector selector){UiObject2 v=device.wait(Until.findObject(selector),6000);assertNotNull(selector.toString(),v);return v;}
    private static ListView list(MainActivity a){return (ListView)ManualAlignmentInstrumentedTest.field(ManualAlignmentInstrumentedTest.field(a,"left"),"list");}
    private static Rect bounds(View v){int[] at=new int[2];v.getLocationOnScreen(at);return new Rect(at[0],at[1],at[0]+v.getWidth(),at[1]+v.getHeight());}
    private static void collectCommands(View v,ArrayList<String> out){
        if(v.isClickable()&&v.isFocusable()&&v.getContentDescription()!=null&&!(v instanceof android.widget.ScrollView))out.add(v.getContentDescription().toString());
        if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)collectCommands(g.getChildAt(i),out);
    }
}
