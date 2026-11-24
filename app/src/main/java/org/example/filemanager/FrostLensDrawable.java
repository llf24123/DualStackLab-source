package org.example.filemanager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

/** Foreground optics over a separately drawn backdrop. The face never samples or blurs its children. */
final class FrostLensDrawable extends Drawable {
    private enum Part { HEADER, DOCK, BUTTON }

    private static final float[] FACE_STOPS = {0f, .16f, .57f, .91f, 1f};
    private static final float[] RIM_STOPS = {0f, .13f, .25f, .40f, .51f, .66f, .77f, .89f, 1f};
    // The lower rim has a little absorption; the upper rim reflects the environment.
    private static final int[] DAY_RIM = {0x898597a1, 0x77607280, 0x6176858e, 0xa8d9e3e7,
            0xd9ffffff, 0xecffffff, 0xbafcffff, 0x9ccddae0, 0x898597a1};
    private static final int[] NIGHT_RIM = {0x5f9ca9b0, 0x6b69757e, 0x7579848c, 0x89c4cfd6,
            0xafecf5f9, 0xb3ffffff, 0x97f9feff, 0x66becbd3, 0x5f9ca9b0};
    private static final int[] DAY_HEADER = {0xd5ffffff, 0xb9fafdfd, 0x98edf4f6, 0xa4e6edf0, 0xbafaffff};
    private static final int[] DAY_DOCK = {0xe0ffffff, 0xc1f9fcfc, 0x9ce9eff1, 0xade2eaee, 0xc5f6fbfd};
    private static final int[] DAY_BUTTON = {0xeaffffff, 0xd1ffffff, 0xb0f2f5f7, 0xbfe5ecee, 0xd9f8fbfd};
    private static final int[] NIGHT_HEADER = {0xd14c5357, 0xaa30373b, 0x9f252a2e, 0xab24282b, 0xb43a4045};
    private static final int[] NIGHT_DOCK = {0xd0555b5e, 0xba383d40, 0xad25292c, 0xb725282a, 0xbd41474a};
    private static final int[] NIGHT_BUTTON = {0xd262696d, 0xbc41484c, 0xb4313639, 0xc12c3033, 0xc74a5155};

    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint bevel = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF panel = new RectF(), outer = new RectF(), inner = new RectF();
    private final boolean dark;
    private final int[] faceColors;
    private final float edgePx, bevelPx;
    private float radius, outerRadius, innerRadius;
    private int alpha = 255;

    static FrostLensDrawable header(Context c, boolean dark) { return new FrostLensDrawable(c, dark, Part.HEADER); }
    static FrostLensDrawable dock(Context c, boolean dark) { return new FrostLensDrawable(c, dark, Part.DOCK); }
    static FrostLensDrawable button(Context c, boolean dark) { return new FrostLensDrawable(c, dark, Part.BUTTON); }

    private FrostLensDrawable(Context c, boolean dark, Part part) {
        this.dark = dark;
        faceColors = switch (part) {
            case HEADER -> dark ? NIGHT_HEADER : DAY_HEADER;
            case DOCK -> dark ? NIGHT_DOCK : DAY_DOCK;
            case BUTTON -> dark ? NIGHT_BUTTON : DAY_BUTTON;
        };
        float density = c.getResources().getDisplayMetrics().density;
        edgePx = Math.max(1f, density * (part == Part.DOCK ? 1.2f : 1f));
        bevelPx = Math.max(.75f, density * .8f);
        rim.setStyle(Paint.Style.STROKE); bevel.setStyle(Paint.Style.STROKE);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        panel.set(bounds);
        if (panel.isEmpty()) return;
        radius = Math.min(panel.width(), panel.height()) * .5f;
        float edge = Math.min(edgePx, radius * .4f), lip = Math.min(bevelPx, radius * .4f);
        outer.set(panel); outer.inset(edge * .5f, edge * .5f);
        inner.set(panel); inner.inset(edge + lip * .5f, edge + lip * .5f);
        outerRadius = radius - edge * .5f; innerRadius = radius - edge - lip * .5f;
        rim.setStrokeWidth(edge); bevel.setStrokeWidth(lip);
        face.setShader(new LinearGradient(0f, panel.top, 0f, panel.bottom, faceColors, FACE_STOPS, Shader.TileMode.CLAMP));
        rim.setShader(new SweepGradient(panel.centerX(), panel.centerY(), dark ? NIGHT_RIM : DAY_RIM, RIM_STOPS));
        bevel.setShader(new LinearGradient(0f, panel.top, 0f, panel.bottom,
                dark ? 0x38ffffff : 0xb5ffffff, dark ? 0x22000000 : 0x25545e65, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        if (panel.isEmpty() || alpha == 0) return;
        canvas.drawRoundRect(panel, radius, radius, face);
        canvas.drawRoundRect(outer, outerRadius, outerRadius, rim);
        canvas.drawRoundRect(inner, innerRadius, innerRadius, bevel);
    }

    @Override public void getOutline(Outline outline) {
        if (getBounds().isEmpty()) { outline.setEmpty(); return; }
        outline.setRoundRect(getBounds(), radius);
        outline.setAlpha(alpha / 255f);
    }

    @Override public void setAlpha(int value) {
        value = Math.max(0, Math.min(255, value));
        if (value == alpha) return;
        alpha = value; face.setAlpha(value); rim.setAlpha(value); bevel.setAlpha(value); invalidateSelf();
    }

    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) {
        face.setColorFilter(filter); rim.setColorFilter(filter); bevel.setColorFilter(filter); invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
