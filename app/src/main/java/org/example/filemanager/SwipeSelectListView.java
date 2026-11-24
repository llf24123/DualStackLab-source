package org.example.filemanager;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ListView;

final class SwipeSelectListView extends ListView {
    interface Selection {
        boolean selected(int position);
        void change(int first, int last, boolean checked);
        void gesture(boolean active);
        void pull(float distance);
        void refresh();
    }

    private Selection selection;
    private final int slop;
    private final float maxPull, refreshAt;
    private float downX, downY, pull;
    private int start = INVALID_POSITION;
    private boolean selecting, checked, vertical, pulling;
    private long downTime = -1;
    private ValueAnimator rebound;
    private boolean measuringChromeInsets;
    private int deferredRow = INVALID_POSITION, deferredOffset;

    SwipeSelectListView(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        maxPull = Ui.dp(context, 132);
        refreshAt = Ui.dp(context, 64);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    void setSelectionListener(Selection listener) { selection = listener; }

    void setChromeInsets(int top,int bottom) {
        measuringChromeInsets=true;
        setClipToPadding(top==0 && bottom==0);setPadding(0,top,0,bottom);
    }
    @Override public void setSelectionFromTop(int row,int offset) {
        // AbsListView resolves the offset against its *last measured* padding. The menu can
        // change that padding in the same frame as a fast directory listing or a theme switch.
        if(measuringChromeInsets){deferredRow=row;deferredOffset=offset;requestLayout();return;}
        super.setSelectionFromTop(row,offset);
    }
    @Override protected void onMeasure(int width,int height) {
        super.onMeasure(width,height);
        if(measuringChromeInsets){
            measuringChromeInsets=false;
            if(deferredRow!=INVALID_POSITION){int row=deferredRow;deferredRow=INVALID_POSITION;super.setSelectionFromTop(row,deferredOffset);}
        }
    }

    void stopForNavigation() {
        finishGesture(); cancelRebound();
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        super.onTouchEvent(down); super.onTouchEvent(cancel);
        down.recycle(); cancel.recycle();
        animate().cancel(); clearAnimation(); setAlpha(1f); setTranslationX(0f);
        setLayerType(LAYER_TYPE_NONE, null);
        pulling = false; offset(0f);
    }

    void stopForRefresh() {
        finishGesture(); cancelRebound();
        animate().cancel(); clearAnimation(); setAlpha(1f); setTranslationX(0f);
        pulling = false; offset(0f);
    }

    private float damped(float distance) {
        return maxPull * (1f - 1f / (Math.max(0f, distance) / maxPull + 1f));
    }

    private boolean atTop() {
        return !canScrollVertically(-1);
    }

    private void offset(float value) {
        pull = value;
        setTranslationY(value);
        if (selection != null) selection.pull(Math.max(0f, value));
    }

    private void cancelRebound() {
        if (rebound != null) { rebound.cancel(); rebound = null; }
    }

    private void releasePull(boolean confirmed) {
        float released = pull;
        boolean refresh = confirmed && released >= refreshAt;
        pulling = false;
        finishGesture();
        cancelRebound();
        if (refresh && selection != null) selection.refresh();
        rebound = ValueAnimator.ofFloat(released, 0f);
        rebound.setDuration(Ui.motionEnabled() ? Ui.MOTION_PANEL : 0);
        rebound.setInterpolator(Ui.EASING);
        rebound.addUpdateListener(value -> offset((float) value.getAnimatedValue()));
        rebound.start();
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void track(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && downTime != event.getDownTime()) {
            finishGesture(); cancelRebound();
            animate().cancel(); setTranslationX(0f); setAlpha(1f); offset(0f);
            downTime = event.getDownTime();
            downX = event.getRawX(); downY = event.getRawY();
            start = pointToPosition((int) event.getX(), (int) event.getY());
            vertical = false; pulling = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !selecting) {
            // Screen coordinates stay stable while the list itself is translated under the finger.
            float dx = Math.abs(event.getRawX() - downX), dy = event.getRawY() - downY;
            if (dx > slop && dx > Math.abs(dy)) cancelPendingInputEvents();
            // Any early vertical travel marks the stroke as a scroll, even if it later drifts sideways.
            if (!vertical && Math.abs(dy) > slop && Math.abs(dy) * 1.2f >= dx) vertical = true;
            if (pulling || vertical && dy > slop && atTop()) {
                if (!pulling) {
                    pulling = true;
                    MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.onTouchEvent(cancel); cancel.recycle();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                offset(damped(dy));
            } else if (!vertical && start > 0 && selection != null
                    && dx > Math.max(slop * 3, Ui.dp(getContext(), 40)) && dx > Math.abs(dy) * 2.2f) {
                // A deliberate, mostly horizontal stroke starts selection; a slanted scroll does not.
                selecting = true; checked = !selection.selected(start);
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.onTouchEvent(cancel); cancel.recycle();
                getParent().requestDisallowInterceptTouchEvent(true);
                selection.gesture(true);
                selection.change(start, start, checked);
            }
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        track(event);
        if (selecting || pulling) return true;
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        track(event);
        if (pulling) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP) releasePull(true);
            else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) releasePull(false);
            return true;
        }
        if (selecting) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL
                    || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) finishGesture();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            boolean sideways = event.getActionMasked() == MotionEvent.ACTION_UP && !vertical
                    && Math.abs(event.getRawX() - downX) > slop && Math.abs(event.getRawY() - downY) <= slop;
            finishGesture();
            if (sideways) {
                MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.onTouchEvent(cancel); cancel.recycle(); return true;
            }
        }
        return super.onTouchEvent(event);
    }

    void finishGesture() {
        if (selecting && selection != null) selection.gesture(false);
        selecting = false;
        start = INVALID_POSITION;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override protected void onDetachedFromWindow() {
        finishGesture(); cancelRebound();
        super.onDetachedFromWindow();
    }
}
