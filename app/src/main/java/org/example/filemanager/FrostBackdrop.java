package org.example.filemanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

/** Backdrop for lenses drawn in the same window, using only their strip of the sibling file pane. */
@android.annotation.SuppressLint("ViewConstructor") // Always constructed with the particular source pane.
final class FrostBackdrop extends View {
    private static final int FRAME_MS = 34, MIN_STEP = 8, MAX_AXIS = 512, MAX_PIXELS = 32768;
    private static final View[] NO_LENSES = {};
    private final View content;
    private final boolean top;
    private final float density;
    private final int[] selfAt = new int[2], sourceAt = new int[2], lensAt = new int[2];
    private final Paint layer = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF();
    private final Path mask = new Path();
    private final Runnable requestFrame = () -> { frameQueued = false; invalidate(); };
    private View[] lenses = NO_LENSES;
    private Bitmap bitmap;
    private Canvas sampler;
    private int[] pixels, scratch;
    private boolean allow = true, dirty = true, valid, unsupported, frameQueued;
    private long lastCapture;

    FrostBackdrop(Context c, View content, boolean top) {
        super(c);
        this.content = content; this.top = top; density = getResources().getDisplayMetrics().density;
        setFocusable(false); setClickable(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void track(View... views) {
        lenses = views.clone(); refresh(); invalidate();
    }

    void setBlurAllowed(boolean value) {
        if (value == allow) return;
        allow = value; unsupported = false; lastCapture = 0;
        removeCallbacks(requestFrame); frameQueued = false;
        if (!allow) { release(); invalidate(); } else refresh();
    }

    void refresh() {
        dirty = true;
        if (!isAttachedToWindow() || !isShown() || frameQueued) return;
        if (!allow || unsupported) { if (solid.getColor()!=Ui.SURFACE) invalidate(); return; }
        long wait = valid ? Math.max(0,lastCapture + FRAME_MS - SystemClock.uptimeMillis()) : 0;
        frameQueued = true; postOnAnimationDelayed(requestFrame,wait);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w,h,oldw,oldh); destination.set(0,0,w,h); valid = false; dirty = true; lastCapture = 0;
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(requestFrame); frameQueued = false; release(); unsupported = false; dirty = true; lastCapture = 0;
        super.onDetachedFromWindow();
    }

    private void release() {
        // A display list can still hold the last bitmap; do not recycle it while RenderThread uses it.
        sampler = null; bitmap = null; pixels = scratch = null; valid = false;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (destination.isEmpty()) return;
        getLocationInWindow(selfAt);
        if (allow && !unsupported && dirty && lenses.length > 0) {
            long now = SystemClock.uptimeMillis();
            long remaining = lastCapture + FRAME_MS - now;
            if (remaining > 0 && valid) refresh();
            else {
                if (frameQueued) { removeCallbacks(requestFrame); frameQueued = false; }
                lastCapture = now; dirty = false;
                if (!content.isShown() || content.getWidth() <= 0 || content.getHeight() <= 0) valid = false;
                else {
                    try { capture(); valid = true; }
                    catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
                        // A vendor application badge can contain a hardware-only bitmap. Avoid retrying
                        // every frame against a Canvas that cannot draw it.
                        release(); unsupported = true;
                        Log.w("FrostBackdrop", "Software capture unavailable for file pane", e);
                    }
                }
            }
        }
        boolean sampled = allow && valid;
        solid.setColor(Ui.SURFACE); mask.rewind();
        for (View lens : lenses) {
            if (lens == null || !lens.isShown() || lens.getWidth() <= 0 || lens.getHeight() <= 0) continue;
            lens.getLocationInWindow(lensAt);
            float x = lensAt[0] - selfAt[0], y = lensAt[1] - selfAt[1];
            float r = Math.min(lens.getWidth(),lens.getHeight()) * .5f;
            if (sampled) mask.addRoundRect(x,y,x+lens.getWidth(),y+lens.getHeight(),r,r,Path.Direction.CW);
            else canvas.drawRoundRect(x,y,x+lens.getWidth(),y+lens.getHeight(),r,r,solid);
        }
        if (sampled && !mask.isEmpty()) {
            int save=canvas.save(); canvas.clipPath(mask);
            canvas.drawBitmap(bitmap,null,destination,layer); canvas.restoreToCount(save);
        }
    }

    private void capture() {
        int sw = getWidth(), sh = getHeight();
        int step = Math.max(MIN_STEP,(Math.max(sw,sh)+MAX_AXIS-1)/MAX_AXIS);
        int w, h;
        while (true) {
            w = Math.max(1,(sw+step-1)/step); h = Math.max(1,(sh+step-1)/step);
            if ((long)w*h <= MAX_PIXELS) break;
            step++;
        }
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
            bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            sampler = new Canvas(bitmap);
            pixels = new int[w*h]; scratch = new int[w*h];
        }
        content.getLocationInWindow(sourceAt);
        int sourceX = selfAt[0]-sourceAt[0], sourceY = selfAt[1]-sourceAt[1];
        // In a non-overlapping layout use the nearest edge of the real pane instead of traversing
        // empty screen space. In an overlay layout the exact visible source coordinates are used.
        if (sourceY+sh <= 0 || sourceY >= content.getHeight()) sourceY = top ? 0 : Math.max(0,content.getHeight()-sh);
        sampler.drawColor(Ui.SURFACE);
        int saved = sampler.save();
        try {
            sampler.scale((float)w/sw,(float)h/sh);
            sampler.translate(-sourceX,-sourceY);
            sampler.clipRect(sourceX,sourceY,sourceX+sw,sourceY+sh);
            content.draw(sampler);
        } finally { sampler.restoreToCount(saved); }
        bitmap.getPixels(pixels,0,w,0,0,w,h);
        int radius = Math.max(1,Math.min(5,Math.round(density*5.5f/step)));
        box(pixels,scratch,w,h,1,w,radius); box(scratch,pixels,h,w,w,1,radius);
        bitmap.setPixels(pixels,0,w,0,0,w,h);
    }

    /** Sliding mean along one axis. The sample always has an opaque surface beneath its content. */
    private static void box(int[] in, int[] out, int length, int lines, int stride, int lineStride, int radius) {
        int count = 2*radius+1;
        for (int line=0; line<lines; line++) {
            int start=line*lineStride, red=0, green=0, blue=0;
            for (int d=-radius; d<=radius; d++) {
                int p=in[start+Math.min(length-1,Math.max(0,d))*stride];
                red+=(p>>16)&255; green+=(p>>8)&255; blue+=p&255;
            }
            for (int x=0; x<length; x++) {
                out[start+x*stride]=0xff000000 | ((red/count)<<16) | ((green/count)<<8) | (blue/count);
                int a=in[start+Math.max(0,x-radius)*stride], b=in[start+Math.min(length-1,x+radius+1)*stride];
                red+=((b>>16)&255)-((a>>16)&255); green+=((b>>8)&255)-((a>>8)&255); blue+=(b&255)-(a&255);
            }
        }
    }
}
