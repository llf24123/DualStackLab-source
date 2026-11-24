package org.example.filemanager;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class IconStyleSettingsInstrumentedTest {
    private final Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static View match(View v,String label){
        if(v.getVisibility()!=View.VISIBLE)return null;
        if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()) || v instanceof TextView t&&label.contentEquals(t.getText()))return v;
        if(v instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View hit=match(group.getChildAt(i),label);if(hit!=null)return hit;}
        return null;
    }
    private static View required(View root,String name){View v=match(root,name);assertNotNull(name,v);return v;}
    private static AlertDialog picker(SettingsActivity a){
        a.getFragmentManager().executePendingTransactions();ThemePicker p=(ThemePicker)a.getFragmentManager().findFragmentByTag("appearance-picker");assertNotNull(p);return (AlertDialog)p.getDialog();
    }
    private static View in(AlertDialog d,String name){return required(d.getWindow().getDecorView(),name);}

    @Test public void settingsSearchOpensIconDraftAndCancelPreservesStoredStyles(){
        SharedPreferences prefs=ctx.getSharedPreferences("appearance",0);String material=prefs.getString("material",null),mode=prefs.getString("mode",null);
        IconStyle frost=IconStyle.forMaterial(ctx,ThemeMaterial.FROST),tide=IconStyle.forMaterial(ctx,ThemeMaterial.TIDE),paper=IconStyle.forMaterial(ctx,ThemeMaterial.PAPER);
        var edit=prefs.edit().putString("material","frost").putString("mode","light");
        for(ThemeMaterial m:ThemeMaterial.values())IconStyle.write(edit,m,IconStyle.FOLLOW);edit.commit();
        Intent intent=new Intent(ctx,SettingsActivity.class).putExtra("query","轮廓");
        try(ActivityScenario<SettingsActivity> scenario=ActivityScenario.launch(intent)){
            final String row="文件图标样式，雾面玻璃 · 跟随主题";
            scenario.onActivity(a->{required(a.getWindow().getDecorView(),row).performClick();AlertDialog d=picker(a);
                in(d,"图标样式：线框").performClick();in(d,"材质：暖纸").performClick();in(d,"图标样式：双色").performClick();
                in(d,"材质：经典").performClick();assertNull(match(d.getWindow().getDecorView(),"图标样式：双色"));in(d,"经典 · 固定使用原版图标");
                in(d,"材质：雾面玻璃").performClick();assertTrue(in(d,"图标样式：线框").isSelected());});
            scenario.recreate();
            scenario.onActivity(a->{AlertDialog d=picker(a);assertTrue(in(d,"图标样式：线框").isSelected());in(d,"材质：暖纸").performClick();
                assertTrue(in(d,"图标样式：双色").isSelected());d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();});
            assertEquals(IconStyle.FOLLOW,IconStyle.forMaterial(ctx,ThemeMaterial.FROST));assertEquals(IconStyle.FOLLOW,IconStyle.forMaterial(ctx,ThemeMaterial.PAPER));
            scenario.onActivity(a->{required(a.getWindow().getDecorView(),row).performClick();AlertDialog d=picker(a);
                in(d,"图标样式：色块").performClick();in(d,"材质：潮汐渐变").performClick();in(d,"图标样式：双色").performClick();
                in(d,"材质：雾面玻璃").performClick();d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
            scenario.onActivity(a->{assertEquals(IconStyle.TILE,IconStyle.forMaterial(ctx,ThemeMaterial.FROST));assertEquals(IconStyle.DUOTONE,IconStyle.forMaterial(ctx,ThemeMaterial.TIDE));
                assertEquals(IconStyle.FOLLOW,IconStyle.forMaterial(ctx,ThemeMaterial.PAPER));assertEquals(IconStyle.TILE,Ui.fileIcons);
                required(a.getWindow().getDecorView(),"文件图标样式，雾面玻璃 · 色块");});
        }finally{var restore=prefs.edit().putString("material",material).putString("mode",mode);IconStyle.write(restore,ThemeMaterial.FROST,frost);
            IconStyle.write(restore,ThemeMaterial.TIDE,tide);IconStyle.write(restore,ThemeMaterial.PAPER,paper);restore.commit();}
    }
}
