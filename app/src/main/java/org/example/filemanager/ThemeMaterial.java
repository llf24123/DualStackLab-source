package org.example.filemanager;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** Application chrome. File icons and selection colours are deliberately separate. */
enum ThemeMaterial {
    CLASSIC("classic", "经典",
            new Tones(0xff223638,0xff223638,0xff223638,0xfffdfefd,0xfff0f4f2,0xff203233,0xff546761,0xffd9e3df),
            new Tones(0xff192728,0xff192728,0xff192728,0xff151d1e,0xff202c2d,0xffe4edeb,0xffadbfbb,0xff354545)),
    TIDE("tide", "潮汐渐变",
            new Tones(0xff0e5d61,0xff275777,0xff504578,0xfff8fcfc,0xffe9f0f4,0xff192f3b,0xff4a626d,0xffd1dde3),
            new Tones(0xff124447,0xff20394f,0xff41375f,0xff111c23,0xff1d2b34,0xffe3eef1,0xffa6bcc6,0xff32444e)),
    FROST("frost", "雾面玻璃",
            new Tones(0xff344958,0xff526775,0xff3d5062,0xfff9fafb,0xffecf0f2,0xff1d262e,0xff515e68,0xffd1d9dd),
            new Tones(0xff26313f,0xff415461,0xff293748,0xff08090a,0xff222426,0xfff1f2f3,0xffb7bdc3,0xff393e42)),
    PAPER("paper", "暖纸",
            new Tones(0xff493e35,0xff51453a,0xff574a3b,0xfffcf6e9,0xfff0e7d7,0xff352f29,0xff685c4f,0xffdfd4c2),
            new Tones(0xff302c26,0xff383028,0xff40362b,0xff201e1a,0xff2e2a24,0xfff2eadd,0xffc5b7a4,0xff494135));

    record Tones(int ink, int middle, int end, int surface, int panel, int text, int muted, int line) { }
    enum Surface { HEADER, FOOTER, PAGE, BODY }
    private record Binding(Surface role, GradientDrawable drawable) { }
    final String id, title;
    private final Tones day, night;
    ThemeMaterial(String id, String title, Tones day, Tones night) {
        this.id=id; this.title=title; this.day=day; this.night=night;
    }
    Tones tones(boolean dark) { return dark ? night : day; }
    private static final int[] DAY_ACCENTS={0xff167d68,0xff226ba3,0xff71459a,0xff855c28,0xff505e69};
    private static final int[] NIGHT_ACCENTS={0xff72cdb7,0xff8bc7f2,0xffcbb1ee,0xffe5bd88,0xffc0c6cb};
    int accent(boolean dark,int index) {
        if(index>=0 && index<DAY_ACCENTS.length)return (dark?NIGHT_ACCENTS:DAY_ACCENTS)[index];
        return switch(this){case CLASSIC->dark?NIGHT_ACCENTS[0]:DAY_ACCENTS[0];case TIDE->dark?0xff76ced8:0xff176b7c;
            case FROST->dark?0xff8bb7ff:0xff2166b6;case PAPER->dark?0xffdfbd89:0xff80582d;};
    }
    int activityTheme(boolean dark){return switch(this){
        case CLASSIC->dark?R.style.Theme_DualStack_Dark:R.style.Theme_DualStack;
        case TIDE->dark?R.style.LabTideNight:R.style.LabTideDay;
        case FROST->dark?R.style.LabFrostNight:R.style.LabFrostDay;
        case PAPER->dark?R.style.LabPaperNight:R.style.LabPaperDay;};}
    int alertTheme(boolean dark){return switch(this){
        case TIDE->dark?R.style.AlertTideNight:R.style.AlertTideDay;
        case FROST->dark?R.style.AlertFrostNight:R.style.AlertFrostDay;
        case PAPER->dark?R.style.AlertPaperNight:R.style.AlertPaperDay;
        default->throw new IllegalStateException("Classic uses the platform alert");};}
    static ThemeMaterial fromId(String id) {
        for (ThemeMaterial style : values()) if (style.id.equals(id)) return style;
        return null;
    }
    static ThemeMaterial current(Context c) {
        ThemeMaterial style=fromId(c.getSharedPreferences("appearance",0).getString("material",null));
        return style == null ? CLASSIC : style;
    }
    private static GradientDrawable gradient(GradientDrawable.Orientation angle, int... stops) {
        GradientDrawable d=new GradientDrawable(angle,stops); d.setDither(true); return d;
    }
    GradientDrawable fill(Surface role, boolean dark) {
        Tones t=tones(dark);
        if (role==Surface.HEADER) return this==CLASSIC ? Ui.background(t.ink,0)
                : gradient(GradientDrawable.Orientation.LEFT_RIGHT,t.ink,t.middle,t.end);
        int base=role==Surface.PAGE ? (dark?t.surface:t.panel) : t.surface;
        if (this==CLASSIC) return Ui.background(base,0);
        if(role==Surface.BODY) return gradient(GradientDrawable.Orientation.TL_BR,t.surface,Ui.blend(t.surface,t.panel,.47f),t.surface);
        float mix=role==Surface.PAGE ? .13f : .08f;
        if (dark) mix+=.06f;
        int left=Ui.blend(base,this==FROST?0xff8eabb9:t.ink,mix);
        int right=Ui.blend(base,this==FROST?0xffb4acc4:t.end,mix);
        return gradient(role==Surface.PAGE?GradientDrawable.Orientation.TL_BR:GradientDrawable.Orientation.LEFT_RIGHT,left,base,right);
    }
    GradientDrawable card(Context c, int base, int radius) {
        if (this==CLASSIC) return Ui.background(base,Ui.dp(c,radius));
        Tones t=tones(Ui.dark);
        GradientDrawable d = switch(this){
            case FROST -> gradient(GradientDrawable.Orientation.TL_BR,Ui.blend(base,Color.WHITE,Ui.dark?.09f:.50f),base,Ui.blend(base,t.line,.26f));
            case TIDE -> gradient(GradientDrawable.Orientation.TL_BR,Ui.blend(base,t.ink,Ui.dark?.19f:.075f),base,Ui.blend(base,t.end,Ui.dark?.14f:.06f));
            case PAPER -> gradient(GradientDrawable.Orientation.TOP_BOTTOM,Ui.blend(base,t.panel,.18f),base,Ui.blend(base,t.line,Ui.dark?.16f:.12f));
            default -> Ui.background(base,0);};
        d.setCornerRadius(Ui.dp(c,radius));
        d.setStroke(Ui.dp(c,1),this==FROST ? Ui.blend(base,Ui.dark?0xffc8dce3:Color.WHITE,Ui.dark?.30f:.90f) : t.line);
        return d;
    }
    static void paint(View view, Surface role) {
        GradientDrawable drawable=Ui.material.fill(role,Ui.dark);
        view.setBackground(drawable); view.setTag(R.id.material_surface,new Binding(role,drawable));
    }
    static boolean repaint(View view) {
        if (!(view.getTag(R.id.material_surface) instanceof Binding b)) return false;
        // Some tools intentionally override their toolbar or status colour after installation.
        if (view.getBackground()!=b.drawable) { view.setTag(R.id.material_surface,null); return false; }
        paint(view,b.role); return true;
    }
}
