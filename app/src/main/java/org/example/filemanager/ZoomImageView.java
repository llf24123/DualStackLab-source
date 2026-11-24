package org.example.filemanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** Image preview with pinch zoom (up to 8x of the fitted size), panning and double-tap toggle. */
final class ZoomImageView extends ImageView {
    static final float MAX_ZOOM = 8f;
    private final Matrix matrix = new Matrix();
    private final RectF bounds = new RectF();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    private float fit = 1f;
    private IntConsumer pageChange;
    private IntPredicate canChangePage;
    private Runnable singleTap;
    private Runnable onZoom;
    private float downX, downY;
    private boolean pageCandidate;
    private boolean horizontalDrag;
    private boolean touching;
    private final int touchSlop, minimumFlingVelocity, maximumFlingVelocity;
    private VelocityTracker velocity;
    void setOnPageChangeListener(IntConsumer listener) { pageChange = listener; }
    void setCanChangePageListener(IntPredicate listener) { canChangePage = listener; }
    void setOnSingleTapListener(Runnable listener) { singleTap = listener; }
    void setOnZoomListener(Runnable listener) { onZoom = listener; }

    ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minimumFlingVelocity = Math.max(configuration.getScaledMinimumFlingVelocity(), Ui.dp(context, 600));
        maximumFlingVelocity = configuration.getScaledMaximumFlingVelocity();
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY()); return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent event, float dx, float dy) {
                if (event.getPointerCount() > 1 || pageCandidate) return true;
                matrix.postTranslate(-dx, -dy); clamp(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                pageCandidate = false;
                if (zoomFactor() > 1.05f) reset(); else zoom(2.5f, event.getX(), event.getY());
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (singleTap != null) singleTap.run();
                return true;
            }
        });
    }
    /** Current scale relative to the fitted size; 1 means the whole image is visible. */
    float zoomFactor() { matrix.getValues(values); return values[Matrix.MSCALE_X] / fit; }
    void zoom(float factor, float x, float y) {
        float now = zoomFactor(), target = Math.max(1f, Math.min(MAX_ZOOM, now * factor));
        matrix.postScale(target / now, target / now, x, y); clamp();
        if (target > 1.05f && onZoom != null) onZoom.run();
    }
    void replaceWithDetail(Bitmap bitmap) {
        Drawable old = getDrawable();
        if (old == null) { setImageBitmap(bitmap); return; }
        Matrix previous = new Matrix(matrix);
        float oldWidth = old.getIntrinsicWidth(), oldHeight = old.getIntrinsicHeight(), oldFit = fit;
        setImageBitmap(bitmap);
        Drawable current = getDrawable();
        previous.preScale(oldWidth / current.getIntrinsicWidth(), oldHeight / current.getIntrinsicHeight());
        matrix.set(previous); fit = oldFit * oldWidth / current.getIntrinsicWidth(); clamp();
    }
    void reset() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        float width = drawable.getIntrinsicWidth(), height = drawable.getIntrinsicHeight();
        fit = Math.min(getWidth() / width, getHeight() / height);
        matrix.setScale(fit, fit);
        matrix.postTranslate((getWidth() - width * fit) / 2f, (getHeight() - height * fit) / 2f);
        setImageMatrix(matrix);
    }
    private void clamp() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        bounds.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        matrix.mapRect(bounds);
        matrix.postTranslate(offset(bounds.left, bounds.right, getWidth()), offset(bounds.top, bounds.bottom, getHeight()));
        setImageMatrix(matrix);
    }
    /** Centres an axis smaller than the view, otherwise keeps the image covering the view edge to edge. */
    private static float offset(float start, float end, float size) {
        if (end - start <= size) return (size - (end - start)) / 2f - start;
        if (start > 0) return -start;
        return end < size ? size - end : 0;
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        Drawable drawable = getDrawable();
        if (drawable == null || oldw <= 0 || oldh <= 0 || w <= 0 || h <= 0) { reset(); return; }
        // Keep the same image point at the viewport centre and the same zoom relative to fit.
        float zoom = zoomFactor();
        Matrix inverse = new Matrix();
        if (!matrix.invert(inverse)) { reset(); return; }
        float[] centre = {oldw / 2f, oldh / 2f}; inverse.mapPoints(centre);
        fit = Math.min((float) w / drawable.getIntrinsicWidth(), (float) h / drawable.getIntrinsicHeight());
        float scale = fit * Math.max(1f, Math.min(MAX_ZOOM, zoom));
        matrix.setScale(scale, scale);
        matrix.postTranslate(w / 2f - centre[0] * scale, h / 2f - centre[1] * scale);
        clamp();
    }
    @Override public void setImageDrawable(Drawable drawable) {
        Drawable previous = getDrawable();
        if (previous instanceof android.graphics.drawable.Animatable animation)
            animation.stop();
        super.setImageDrawable(drawable);
        if (drawable == null) { matrix.reset(); fit = 1f; setImageMatrix(matrix); }
        else reset();
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // handleTouch calls performClick for taps.
    @Override public boolean onTouchEvent(MotionEvent event) {
        // Android delivers coordinates relative to this view's CURRENT translation. Use the
        // untranslated position for the entire gesture so following the finger cannot erase distance.
        if (getTranslationX() == 0f && getTranslationY() == 0f) return handleTouch(event);
        MotionEvent stable = MotionEvent.obtain(event);
        stable.offsetLocation(getTranslationX(), getTranslationY());
        try { return handleTouch(stable); }
        finally { stable.recycle(); }
    }
    private boolean handleTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            downX = event.getX(); downY = event.getY();
            releaseVelocity(); velocity = VelocityTracker.obtain();
            pageCandidate = pageChange != null && zoomFactor() <= 1.05f;
            horizontalDrag = false; animate().cancel();
            setTranslationX(0f); setAlpha(1f);
        }
        if (velocity != null) velocity.addMovement(event);
        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_CANCEL) pageCandidate = false;
        scale.onTouchEvent(event); gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE && pageCandidate && zoomFactor() <= 1.05f) {
            float dx = event.getX() - downX, dy = event.getY() - downY;
            if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.15f)
                horizontalDrag = true;
            if (horizontalDrag) {
                if (canChangePage != null && !canChangePage.test(dx < 0 ? 1 : -1)) dx *= .22f;
                setTranslationX(dx);
            }
        } else if (action != MotionEvent.ACTION_CANCEL && !pageCandidate && getTranslationX() != 0) setTranslationX(0);
        if (action == MotionEvent.ACTION_UP) {
            touching = false;
            float dx = event.getX() - downX, dy = event.getY() - downY;
            float vx = 0, vy = 0;
            if (velocity != null) {
                velocity.computeCurrentVelocity(1000, maximumFlingVelocity);
                vx = velocity.getXVelocity(); vy = velocity.getYVelocity();
            }
            float threshold = Math.max(Ui.dp(getContext(), 32), touchSlop * 2);
            boolean flick = Math.abs(dx) >= Math.max(Ui.dp(getContext(), 16), touchSlop * 2)
                    && Math.abs(vx) >= minimumFlingVelocity && Math.abs(vx) > Math.abs(vy) * 1.15f
                    && Math.signum(vx) == Math.signum(dx);
            boolean changed = pageCandidate && zoomFactor() <= 1.05f && (Math.abs(dx) >= threshold || flick)
                    && Math.abs(dx) > Math.abs(dy) * 1.15f
                    && pageChange != null && (canChangePage == null || canChangePage.test(dx < 0 ? 1 : -1));
            if (changed) pageChange.accept(dx < 0 ? 1 : -1);
            if (!changed) settle();
            pageCandidate = false;
            if (Math.abs(dx) <= touchSlop && Math.abs(dy) <= touchSlop) performClick();
        } else if (action == MotionEvent.ACTION_CANCEL) { touching = false; pageCandidate = false; settle(); }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            releaseVelocity();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
    private void releaseVelocity() {
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }
    @Override protected void onDetachedFromWindow() {
        animate().cancel();
        releaseVelocity(); pageCandidate = false; touching = false;
        super.onDetachedFromWindow();
    }
    @Override public boolean performClick() { return super.performClick(); }
    private void settle() {
        animate().translationX(0).alpha(1f).setDuration(Ui.motionEnabled() ? Ui.MOTION_SHORT : 0).setInterpolator(Ui.EASING).start();
    }
    void waitForPage(int direction) {
        animate().cancel();
        if (direction == 0 || getDrawable() == null || !Ui.motionEnabled()) { settle(); return; }
        float distance = Math.max(Math.abs(getTranslationX()), Ui.dp(getContext(), 16));
        animate().translationX(-direction * distance).alpha(.92f).setDuration(Ui.MOTION_SHORT).setInterpolator(Ui.EASING).start();
    }
    void presentPage(int direction, boolean first) {
        animate().cancel();
        if (touching) { setAlpha(1f); return; }
        if (Ui.motionEnabled()) {
            setTranslationX(direction * Ui.dp(getContext(), 28));
            setAlpha(first ? .82f : .94f);
            settle();
        } else { setTranslationX(0f); setAlpha(1f); }
    }
}
