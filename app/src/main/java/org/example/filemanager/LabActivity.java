package org.example.filemanager;

import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

public class LabActivity extends ComponentActivity {
    private boolean appliedDark;
    private String appliedMode;
    private IconStyle appliedIcons;
    private int appliedAccent;
    private int[] palette;
    @Override public void onCreate(Bundle saved) {
        Ui.configure(this);
        applyNativeTheme();appliedIcons=Ui.fileIcons;appliedAccent=Ui.accentIndex;
        appliedDark = Ui.dark; appliedMode=Ui.themeMode(this); palette = Ui.palette();
        super.onCreate(saved);
        RootShell.configure(FileManagerSettings.prefs(this).getString("su", "su"));
        RootAccess.setAutomatic(FileManagerSettings.prefs(this).getBoolean("root", true));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
    }

    protected void handleBack() { finish(); }
    private void applyNativeTheme() {
        int theme=Ui.material.activityTheme(Ui.dark);setTheme(theme);
        if(Ui.material!=ThemeMaterial.CLASSIC){
            // Restore the material defaults even when only the optional accent overlay changed.
            getTheme().applyStyle(theme,true);
            if(Ui.accentIndex>=0)getTheme().applyStyle(accentOverlay(Ui.dark,Ui.accentIndex),true);
        }
    }
    private static final int[] DAY={R.style.AccentJadeDay,R.style.AccentBlueDay,R.style.AccentPlumDay,R.style.AccentTeaDay,R.style.AccentGraphiteDay};
    private static final int[] NIGHT={R.style.AccentJadeNight,R.style.AccentBlueNight,R.style.AccentPlumNight,R.style.AccentTeaNight,R.style.AccentGraphiteNight};
    static int accentOverlay(boolean dark,int index){return (dark?NIGHT:DAY)[index];}
    @Override protected void onResume() {
        super.onResume(); applyAppearance();
    }
    final void applyAppearance() {
        Ui.configure(this);
        String mode=Ui.themeMode(this);
        boolean modeChanged=!mode.equals(appliedMode); appliedMode=mode;
        boolean iconsChanged=appliedIcons!=Ui.fileIcons,accentChanged=appliedAccent!=Ui.accentIndex;
        appliedIcons=Ui.fileIcons;appliedAccent=Ui.accentIndex;
        if (palette != null && (!java.util.Arrays.equals(palette, Ui.palette()) || accentChanged)) {
            applyNativeTheme();
            Ui.updateTheme(this, palette); appliedDark=Ui.dark; palette=Ui.palette(); onPaletteChanged();
        } else if(modeChanged||iconsChanged) onPaletteChanged();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (appliedDark != Ui.isDark(this)) {
            applyAppearance();
        }
    }
    protected void onPaletteChanged() { }
}
