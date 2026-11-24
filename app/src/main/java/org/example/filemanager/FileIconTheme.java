package org.example.filemanager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;
import java.util.Locale;

/** File art belongs to the rendered entry; folder, archive and search rows share the same ink. */
final class FileIconTheme {
    enum Kind {
        FOLDER(R.drawable.ic_folder,0), FILE(R.drawable.ic_file,0xff4374b3),
        PACKAGE(R.drawable.ic_package,0xff247860), ARCHIVE(R.drawable.ic_archive,0xff86642e),
        IMAGE(R.drawable.ic_image,0xff526ba1), AUDIO(R.drawable.ic_music,0xff895582), VIDEO(R.drawable.ic_video,0xff695c9a);
        final int glyph, color;
        Kind(int glyph,int color){this.glyph=glyph;this.color=color;}
    }
    enum EntryPoint { BROWSER, ARCHIVE, SEARCH, PLACES }

    static void nameWeight(android.widget.TextView label,boolean directory){
        boolean bold=directory && Ui.dark && Ui.material==ThemeMaterial.FROST;
        var face=label.getTypeface();if((face!=null&&face.isBold())!=bold)
            label.setTypeface(bold?android.graphics.Typeface.DEFAULT_BOLD:android.graphics.Typeface.DEFAULT);
    }

    static Kind kind(String name,boolean directory,EntryPoint point){
        if(directory || (point==EntryPoint.PLACES && Ui.material==ThemeMaterial.CLASSIC))return Kind.FOLDER;
        String lower=name.toLowerCase(Locale.ROOT);
        if(Ui.material==ThemeMaterial.CLASSIC && point==EntryPoint.ARCHIVE)
            return name.endsWith(".dex")?Kind.PACKAGE:Kind.FILE;
        if(ApkInstall.isApkName(lower))return Kind.PACKAGE;
        int dot=lower.lastIndexOf('.');String ext=dot<0?"":lower.substring(dot+1);
        if(Ui.material==ThemeMaterial.CLASSIC && point==EntryPoint.SEARCH)
            return switch(ext){case "zip","rar","jar","7z","tar","gz","tgz","apks","xapk" -> Kind.ARCHIVE;default -> Kind.FILE;};
        if(ext.equals("apks") || (Ui.material!=ThemeMaterial.CLASSIC && ext.equals("dex")))return Kind.PACKAGE;
        MediaKind media=MediaKind.of(lower);
        if(media!=null)return switch(media){case IMAGE->Kind.IMAGE;case AUDIO->Kind.AUDIO;case VIDEO->Kind.VIDEO;};
        return switch(ext){
            case "zip","rar","jar","xapk","7z","tar","tgz","tbz2","tbz","txz","gz","bz2","xz","lz4","zst" -> Kind.ARCHIVE;
            default -> {
                boolean split=ext.length()>=3 && ext.length()<=5 && dot>=3 && lower.startsWith(".7z",dot-3);
                for(int i=0;split&&i<ext.length();i++)split=ext.charAt(i)>='0'&&ext.charAt(i)<='9';
                yield split?Kind.ARCHIVE:Kind.FILE;
            }
        };
    }

    private record Art(int glyph,int ink,int fill,int end,int border,boolean large) { }
    private static Art art(Kind kind,ThemeMaterial material,boolean dark,IconStyle chosen,int accent,boolean checked){
        int glyph=checked?R.drawable.ic_check:kind.glyph;
        if(material==ThemeMaterial.CLASSIC){
            int fill=switch(kind){case FOLDER->dark?Color.BLACK:0xff303030;case PACKAGE->accent;case ARCHIVE->Ui.GOLD;default->kind.color;};
            return new Art(glyph,Color.WHITE,fill,fill,0,false);
        }
        IconStyle style=chosen==IconStyle.FOLLOW?switch(material){
            case FROST->dark?IconStyle.TILE:IconStyle.OUTLINE;
            case TIDE,PAPER->IconStyle.DUOTONE;
            default->IconStyle.TILE;
        }:chosen;
        if(checked) return new Art(glyph,dark?0xff102022:Color.WHITE,accent,accent,0,false);
        ThemeMaterial.Tones t=material.tones(dark);
        boolean frost=material==ThemeMaterial.FROST, tide=material==ThemeMaterial.TIDE;
        int base = kind==Kind.FOLDER ? (tide?0xff226a85:frost?0xff363e47:0xff775328) : kind.color;
        if(!frost && kind!=Kind.FOLDER)base=Ui.blend(base,tide?0xff17617d:0xff895e32,.20f);
        int ink=dark?Ui.blend(base,Color.WHITE,.61f):base;
        if(style==IconStyle.OUTLINE){
            int line = frost?t.text():ink;
            return new Art(kind==Kind.FOLDER?R.drawable.ic_folder_outline:glyph,line,Color.TRANSPARENT,Color.TRANSPARENT,0,true);
        }
        if(style==IconStyle.TILE){
            int fill=frost && kind==Kind.FOLDER ? (dark?0xff48494b:0xff58616a)
                    : dark ? Ui.blend(base,t.surface(),.27f) : base;
            int end=tide?Ui.blend(fill,0xff3a526f,.55f):fill;
            return new Art(glyph,Color.WHITE,fill,end,0,false);
        }
        int fill=dark?Ui.blend(t.surface(),base,.33f):Ui.blend(t.surface(),base,.14f);
        int end=Ui.blend(t.panel(),tide?0xff627fba:material==ThemeMaterial.PAPER?0xffbd9259:0xff8797b0,dark?.20f:.16f);
        int border=Ui.blend(t.line(),base,dark?.28f:.18f);
        return new Art(kind==Kind.FOLDER?R.drawable.ic_folder_duotone:glyph,ink,fill,end,border,false);
    }

    /** One binding per recycled row. Repeated selection/display updates do not allocate a drawable. */
    static final class Binding {
        private final ImageView image;
        private final GradientDrawable tile;
        private GradientDrawable gradient;
        private final int padding, radius, stroke;
        private Kind lastKind;private ThemeMaterial lastMaterial;private IconStyle lastStyle;
        private int lastAccent, lastGlyph;private boolean lastDark,lastChecked;
        Binding(ImageView image,GradientDrawable tile,int paddingDp,int radiusDp){
            this.image=image;this.tile=tile;Context c=image.getContext();
            padding=Ui.dp(c,paddingDp);stroke=Ui.dp(c,1);radius=Ui.dp(c,radiusDp);
            tile.setCornerRadius(radius);image.setBackground(tile);
        }
        void bind(Kind kind,boolean checked){apply(kind,Ui.material,Ui.dark,Ui.fileIcons,Ui.ACCENT,checked);}
        private void apply(Kind kind,ThemeMaterial material,boolean dark,IconStyle style,int accent,boolean checked){
            if(lastKind==kind && lastMaterial==material && lastStyle==style && lastAccent==accent && lastDark==dark && lastChecked==checked)return;
            lastKind=kind;lastMaterial=material;lastStyle=style;lastAccent=accent;lastDark=dark;lastChecked=checked;
            Art art=art(kind,material,dark,style,accent,checked);
            // Keep solid and shaded drawables separate: a reused GradientDrawable may retain its Paint shader.
            GradientDrawable plate;
            if(art.fill==art.end){plate=tile;plate.setColor(art.fill);}
            else {
                if(gradient==null){gradient=new GradientDrawable();gradient.setOrientation(GradientDrawable.Orientation.TL_BR);gradient.setCornerRadius(radius);}
                plate=gradient;plate.setColors(new int[]{art.fill,art.end});
            }
            plate.setStroke(art.border==0?0:stroke,art.border);
            if(image.getBackground()!=plate)image.setBackground(plate);
            int pad=art.large?Ui.dp(image.getContext(),2):padding;
            if(image.getPaddingLeft()!=pad||image.getPaddingTop()!=pad||image.getPaddingRight()!=pad||image.getPaddingBottom()!=pad)image.setPadding(pad,pad,pad,pad);
            if(lastGlyph!=art.glyph){image.setImageResource(art.glyph);lastGlyph=art.glyph;}
            if(image.getImageTintList()==null||image.getImageTintList().getDefaultColor()!=art.ink)image.setImageTintList(ColorStateList.valueOf(art.ink));
        }
    }

    /** Draws a candidate material without touching live application preferences. */
    static void preview(Context c,ImageView v,ThemeMaterial material,boolean dark,IconStyle style,boolean directory){
        Binding renderer=new Binding(v,new GradientDrawable(),5,5);
        renderer.apply(directory?Kind.FOLDER:Kind.FILE,material,dark,style,material.accent(dark,Ui.accentIndex),false);
    }
    private FileIconTheme() { }
}
