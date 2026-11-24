package org.example.filemanager;

import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsInteractionInstrumentedTest {
    private View find(View view, String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup group)for(int n=0;n<group.getChildCount();n++){View found=find(group.getChildAt(n),description);if(found!=null)return found;}
        return null;
    }
    private View control(SettingsActivity a,String description){View v=find(a.getWindow().getDecorView(),description);assertNotNull(description,v);return v;}
    @Test public void searchRestoresAcrossRecreationAndBackClearsFilter(){
        try(var screen=ActivityScenario.launch(SettingsActivity.class)){
            screen.onActivity(a->{EditText input=(EditText)control(a,"搜索设置输入框");assertEquals(View.GONE,input.getVisibility());control(a,"搜索设置").performClick();input.setText("Tab 宽度");});
            screen.recreate();
            screen.onActivity(a->{EditText input=(EditText)control(a,"搜索设置输入框");assertEquals(View.VISIBLE,input.getVisibility());assertEquals("Tab 宽度",input.getText().toString());a.getOnBackPressedDispatcher().onBackPressed();assertEquals(View.GONE,input.getVisibility());assertEquals("",input.getText().toString());assertFalse(a.isFinishing());});
        }
    }
    @Test public void focusRefreshKeepsExistingControlsAndUpdatesExternalValues(){
        try(var screen=ActivityScenario.<SettingsActivity>launch(SettingsActivity.editorIntent(InstrumentationRegistry.getInstrumentation().getTargetContext()))){
            screen.onActivity(a->{EditText input=(EditText)control(a,"搜索设置输入框");control(a,"搜索设置").performClick();input.setText("自动换行不断词");View row=control(a,"自动换行不断词");a.onWindowFocusChanged(false);a.onWindowFocusChanged(true);assertSame(row,control(a,"自动换行不断词"));assertSame(input,control(a,"搜索设置输入框"));});
        }
    }
    @Test public void editorSwitchWritesEditorPreferencesAndSurvivesRefresh(){
        try(var screen=ActivityScenario.<SettingsActivity>launch(SettingsActivity.editorIntent(InstrumentationRegistry.getInstrumentation().getTargetContext()))){
            screen.onActivity(a->{var preferences=a.getSharedPreferences("editor-display",0);boolean original=preferences.getBoolean("word-boundaries",true);boolean existed=preferences.contains("word-boundaries");try{
                control(a,"搜索设置").performClick();((EditText)control(a,"搜索设置输入框")).setText("自动换行不断词");
                ViewGroup row=(ViewGroup)control(a,"自动换行不断词");assertTrue(row.getChildAt(1) instanceof Switch);row.performClick();assertEquals(!original,preferences.getBoolean("word-boundaries",true));a.onWindowFocusChanged(true);assertEquals(!original,((Switch)row.getChildAt(1)).isChecked());
            }finally{var edit=preferences.edit();if(existed)edit.putBoolean("word-boundaries",original);else edit.remove("word-boundaries");edit.commit();}});
        }
    }
}
