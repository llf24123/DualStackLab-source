package org.example.filemanager;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsNavigationInstrumentedTest {
    private View find(View root,String description){
        if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup group)for(int n=0;n<group.getChildCount();n++){View found=find(group.getChildAt(n),description);if(found!=null)return found;}
        return null;
    }
    private View view(SettingsActivity activity,String description){View found=find(activity.getWindow().getDecorView(),description);assertNotNull(description,found);return found;}
    private void idle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();}

    private void searchFor(ActivityScenario<SettingsActivity> screen, String query) {
        View[] previousResult = {null};
        screen.onActivity(a -> {
            ScrollView scroll = (ScrollView) view(a, "设置内容");
            previousResult[0] = ((ViewGroup) scroll.getChildAt(0)).getChildAt(0);
            assertNotNull(previousResult[0]);
            ((EditText) view(a, "搜索设置输入框")).setText(query);
        });
        long deadline = SystemClock.uptimeMillis() + 5000;
        boolean[] updated = {false};
        while (SystemClock.uptimeMillis() < deadline) {
            screen.onActivity(a -> updated[0] = !previousResult[0].isAttachedToWindow());
            if (updated[0]) return;
            SystemClock.sleep(20);
        }
        fail("搜索结果未更新：" + query);
    }

    @Test public void categoryNavigationAndToolbarBackReturnToHome(){
        try(var screen=ActivityScenario.launch(SettingsActivity.class)){
            screen.onActivity(a->{
                assertNotNull(view(a,"设置分类：启动与权限"));
                assertNull(find(a.getWindow().getDecorView(),"设置分类：文本编辑器"));
                assertNull(find(a.getWindow().getDecorView(),"自动换行不断词"));
                view(a,"设置分类：启动与权限").performClick();
                assertNotNull(view(a,"系统目录授权，使用系统选择器授权目录，并保存到书签"));
                assertNull(find(a.getWindow().getDecorView(),"设置分类：终端"));
                view(a,"返回").performClick();
                assertNotNull(view(a,"设置分类：终端"));assertFalse(a.isFinishing());
            });
        }
    }

    @Test public void globalSearchFromCategoryReturnsToThatCategoryAfterRecreation(){
        try(var screen=ActivityScenario.launch(SettingsActivity.class)){
            screen.onActivity(a->{
                view(a,"设置分类：启动与权限").performClick();
                view(a,"搜索设置").performClick();
            });
            searchFor(screen, "文件名最大行数");
            screen.onActivity(a -> assertNotNull(view(a, "文件名最大行数，" + FileListDisplay.lines(a) + " 行")));
            idle();screen.recreate();idle();
            screen.onActivity(a->{
                assertEquals("文件名最大行数",((EditText)view(a,"搜索设置输入框")).getText().toString());
                a.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(View.GONE,view(a,"搜索设置输入框").getVisibility());
                assertNotNull(view(a,"请求 Root 权限，启动时自动获取 Root 权限"));
                assertNull(find(a.getWindow().getDecorView(),"文件名最大行数，"+FileListDisplay.lines(a)+" 行"));
                a.getOnBackPressedDispatcher().onBackPressed();assertNotNull(view(a,"设置分类：启动与权限"));
            });
        }
    }

    @Test public void editorCategoryScrollSurvivesSearchAndRecreation(){
        final int[] position={0};
        try(var screen=ActivityScenario.<SettingsActivity>launch(SettingsActivity.editorIntent(InstrumentationRegistry.getInstrumentation().getTargetContext()))){
            idle();
            screen.onActivity(a->{ScrollView scroll=(ScrollView)view(a,"设置内容");scroll.scrollTo(0,Ui.dp(a,520));position[0]=scroll.getScrollY();assertTrue(position[0]>0);view(a,"搜索设置").performClick();((EditText)view(a,"搜索设置输入框")).setText("不存在的选项xyz");});idle();
            screen.onActivity(a->a.getOnBackPressedDispatcher().onBackPressed());idle();
            screen.onActivity(a->assertEquals(position[0],((ScrollView)view(a,"设置内容")).getScrollY()));
            screen.recreate();idle();
            screen.onActivity(a->{assertNotNull(view(a,"自动换行不断词"));assertEquals(position[0],((ScrollView)view(a,"设置内容")).getScrollY());});
        }
    }

    @Test public void queryIntentStillOpensGlobalSettingsSearch(){
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(var screen=ActivityScenario.<SettingsActivity>launch(new Intent(context,SettingsActivity.class).putExtra("query","文件名最大行数"))){
            screen.onActivity(a->{assertEquals(View.VISIBLE,view(a,"搜索设置输入框").getVisibility());assertEquals("文件名最大行数",((EditText)view(a,"搜索设置输入框")).getText().toString());view(a,"返回").performClick();assertNotNull(view(a,"设置分类：文件管理"));assertFalse(a.isFinishing());});
        }
    }

    @Test public void mainSearchExcludesEditorPreferencesAndEditorSearchExcludesMainSettings(){
        String backup="编辑时生成备份文件，保存前在同目录生成 .bak；包内编辑备份整个压缩包，重名自动另存";
        try(var screen=ActivityScenario.launch(SettingsActivity.class)){
            screen.onActivity(a -> view(a, "搜索设置").performClick());
            searchFor(screen, "自动换行不断词");
            screen.onActivity(a -> assertNull(find(a.getWindow().getDecorView(), "自动换行不断词")));
            searchFor(screen, "编辑时生成备份文件");
            screen.onActivity(a -> assertNull(find(a.getWindow().getDecorView(), backup)));
        }
        try(var screen=ActivityScenario.<SettingsActivity>launch(SettingsActivity.editorIntent(InstrumentationRegistry.getInstrumentation().getTargetContext()))){
            screen.onActivity(a -> { view(a, "自动换行不断词"); view(a, "搜索设置").performClick(); });
            searchFor(screen, "编辑时生成备份文件");
            screen.onActivity(a -> view(a, backup));
            searchFor(screen, "请求 Root 权限");
            screen.onActivity(a -> {
                assertNull(find(a.getWindow().getDecorView(), "请求 Root 权限，启动时自动获取 Root 权限"));
                a.getOnBackPressedDispatcher().onBackPressed();
                view(a, "自动换行不断词"); assertFalse(a.isFinishing());
                a.getOnBackPressedDispatcher().onBackPressed(); assertTrue(a.isFinishing());
            });
        }
    }

    @Test public void realEditorMenuOpensEditorSettingsAndBackReturnsToDocument()throws Exception{
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        java.io.File file=java.io.File.createTempFile("editor-settings-entry-",".txt",context.getCacheDir());
        java.nio.file.Files.write(file.toPath(),"settings entry fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Intent intent=new Intent(context,EditorActivity.class);FileStore.local(file).put(intent);
        try(var screen=ActivityScenario.<EditorActivity>launch(intent)){
            long deadline=SystemClock.uptimeMillis()+10000;boolean[] loaded={false};
            while(!loaded[0]&&SystemClock.uptimeMillis()<deadline){screen.onActivity(a->loaded[0]=(boolean)ManualAlignmentInstrumentedTest.field(a,"loaded"));if(!loaded[0])SystemClock.sleep(20);}
            assertTrue("Editor did not finish reading the fixture",loaded[0]);
            try{
                UiObject2 menu=device.wait(Until.findObject(By.desc("编辑器菜单")),5000);assertNotNull(menu);menu.click();
                UiObject2 list=device.wait(Until.findObject(By.clazz("android.widget.ListView")),5000);assertNotNull(list);
                // Settings is last in the default order. Finish scrolling before looking for the
                // click target: hasObject also returns true for a partly clipped final row.
                for(int n=0;n<12;n++){boolean more=list.scroll(Direction.DOWN,.8f);device.waitForIdle();if(!more)break;}
                UiObject2 settings=list.findObject(By.text("设置"));
                // Respect custom menu ordering too: scroll upward if settings was moved earlier.
                for(int n=0;n<12&&settings==null;n++){list.scroll(Direction.UP,.5f);device.waitForIdle();settings=list.findObject(By.text("设置"));}
                assertNotNull("Editor menu must expose settings",settings);
                android.graphics.Rect bounds=settings.getVisibleBounds(),viewport=list.getVisibleBounds();
                if(bounds.height()<Ui.dp(context,48)||bounds.top<viewport.top+Ui.dp(context,4)||bounds.bottom>viewport.bottom-Ui.dp(context,4)){
                    list.scroll(bounds.centerY()<viewport.centerY()?Direction.UP:Direction.DOWN,.25f);
                }
                device.waitForIdle();SystemClock.sleep(Ui.MOTION_PANEL+120);device.waitForIdle();
                list=device.wait(Until.findObject(By.clazz("android.widget.ListView")),3000);assertNotNull(list);
                settings=list.findObject(By.text("设置"));assertNotNull(settings);
                bounds=settings.getVisibleBounds();viewport=list.getVisibleBounds();
                assertTrue("Settings row must be fully visible before clicking: "+bounds+" in "+viewport,bounds.height()>=Ui.dp(context,48)&&bounds.top>=viewport.top&&bounds.bottom<=viewport.bottom);
                assertTrue("Settings menu row must be enabled",settings.isEnabled());
                device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null),"0645-editor-entry-before.png"));
                device.dumpWindowHierarchy(new java.io.File(context.getExternalFilesDir(null),"0645-editor-entry-before.xml"));
                settings.click();device.waitForIdle();
                assertTrue("Editor menu did not open editor settings",device.wait(Until.hasObject(By.text("文本编辑器设置")),5000));
            }catch(AssertionError|RuntimeException failure){
                device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null),"0645-editor-entry-failed.png"));
                try{device.dumpWindowHierarchy(new java.io.File(context.getExternalFilesDir(null),"0645-editor-entry-failed.xml"));}catch(java.io.IOException dumpFailure){failure.addSuppressed(dumpFailure);}
                throw failure;
            }
            assertTrue(device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null),"0645-editor-settings.png")));
            UiObject2 searchButton=device.wait(Until.findObject(By.desc("搜索设置")),3000);assertNotNull(searchButton);searchButton.click();
            UiObject2 search=device.wait(Until.findObject(By.desc("搜索设置输入框")),3000);assertNotNull(search);search.setText("自动换行不断词");
            assertTrue(device.wait(Until.hasObject(By.desc("自动换行不断词")),3000));
            search.setText("请求 Root 权限");assertTrue(device.wait(Until.hasObject(By.text("没有匹配的设置")),3000));
            device.findObject(By.desc("返回")).click();
            assertTrue(device.wait(Until.hasObject(By.text("文本编辑器设置")),3000));
            device.findObject(By.desc("返回")).click();
            assertTrue("Back must return to the open document",device.wait(Until.hasObject(By.desc("编辑器菜单")),5000));
            screen.onActivity(a->{assertFalse(a.isFinishing());assertEquals(file.getPath(),((FileStore.Node)ManualAlignmentInstrumentedTest.field(a,"source")).path());});
            assertEquals("settings entry fixture",new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        }finally{java.nio.file.Files.deleteIfExists(file.toPath());}
    }
}
