package org.example.filemanager;

import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** Preserve a tool's established classic surface; other materials share the chosen palette. */
final class ToolAppearance {
    static boolean classic() { return Ui.material == ThemeMaterial.CLASSIC; }

    static void card(View view, int classicFill, int classicRadius, int materialRadius) {
        GradientDrawable background = classic() ? Ui.background(classicFill, Ui.dp(view.getContext(),classicRadius))
                : Ui.material.card(view.getContext(),Ui.dark?Ui.PANEL:Ui.SURFACE,materialRadius);
        view.setBackground(background);
    }

    static int byteStripe() { return classic() ? (Ui.dark ? 0xff1e282e : 0xffedf4fb) : Ui.PANEL; }
    static int byteSelection() {
        return classic() ? (Ui.dark ? 0xff355669 : 0xffacd7f7) : Ui.blend(Ui.SURFACE,Ui.ACCENT,Ui.dark?.36f:.28f);
    }

    static int diffCell(TextDiff.Kind kind, boolean left) {
        boolean old=classic();
        if(kind==TextDiff.Kind.CHANGE)return old?(Ui.dark?0xff263d4b:0xffdcecff):Ui.TINT;
        if(left && kind==TextDiff.Kind.REMOVE)return old?(Ui.dark?0xff4c292e:0xffffdfe2)
                :Ui.blend(Ui.SURFACE,Ui.dark?0xffd88783:0xffbb4a49,Ui.dark?.26f:.17f);
        if(!left && kind==TextDiff.Kind.ADD)return old?(Ui.dark?0xff234332:0xffdaf0dd)
                :Ui.blend(Ui.SURFACE,Ui.dark?0xff87bb91:0xff40814e,Ui.dark?.26f:.17f);
        return Ui.SURFACE;
    }
    private ToolAppearance() { }
}
