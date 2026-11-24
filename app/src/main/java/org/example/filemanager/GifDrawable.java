package org.example.filemanager;

import android.graphics.*;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** GIF playback on Android 8 where ImageDecoder is not available. */
final class GifDrawable extends Drawable implements Animatable, Runnable {
    private final Movie movie;
    private boolean running;
    private long started;
    GifDrawable(Movie movie) { this.movie = movie; }
    @Override public int getIntrinsicWidth() { return movie.width(); }
    @Override public int getIntrinsicHeight() { return movie.height(); }
    @Override public void draw(Canvas canvas) {
        int duration = movie.duration(); if (duration <= 0) duration = 1000;
        movie.setTime((int) ((SystemClock.uptimeMillis() - started) % duration));
        Rect bounds = getBounds();
        int save = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / (float) movie.width(), bounds.height() / (float) movie.height());
        movie.draw(canvas, 0, 0); canvas.restoreToCount(save);
        if (running) scheduleSelf(this, SystemClock.uptimeMillis() + 32);
    }
    @Override public void start() { if (!running) { running = true; started = SystemClock.uptimeMillis(); invalidateSelf(); } }
    @Override public void stop() { running = false; unscheduleSelf(this); }
    @Override public boolean isRunning() { return running; }
    @Override public void run() { invalidateSelf(); }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
