package org.example.filemanager;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the chosen geometry per material. FOLLOW means use the material's own day/night icons. */
enum IconStyle {
    FOLLOW("follow", "跟随主题"), OUTLINE("outline", "线框"), TILE("tile", "色块"), DUOTONE("duotone", "双色");

    final String id, title;
    IconStyle(String id, String title) { this.id=id; this.title=title; }

    static IconStyle fromId(String id) {
        for (IconStyle value : values()) if(value.id.equals(id)) return value;
        return null;
    }
    private static String key(ThemeMaterial material) { return "icons-"+material.id; }
    static IconStyle forMaterial(Context c, ThemeMaterial material) {
        if(material==ThemeMaterial.CLASSIC) return FOLLOW;
        IconStyle value=fromId(c.getSharedPreferences("appearance",0).getString(key(material),null));
        return value==null?FOLLOW:value;
    }
    static void write(SharedPreferences.Editor edit, ThemeMaterial material, IconStyle value) {
        if(material==ThemeMaterial.CLASSIC)return;
        if(value==FOLLOW)edit.remove(key(material));else edit.putString(key(material),value.id);
    }
}
