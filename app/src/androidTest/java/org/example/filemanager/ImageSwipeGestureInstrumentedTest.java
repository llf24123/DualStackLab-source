package org.example.filemanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImageSwipeGestureInstrumentedTest {
    @Test public void multiTouchNeverTurnsPageAndSingleFingerSwipeStillWorks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomImageView view = view(); AtomicInteger pages = new AtomicInteger(); view.setOnPageChangeListener(pages::addAndGet);
            long t = SystemClock.uptimeMillis();
            one(view, t, t, MotionEvent.ACTION_DOWN, 250, 400);
            two(view, t, t+50, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 250, 400, 550, 400);
            two(view, t, t+100, MotionEvent.ACTION_MOVE, 200, 400, 600, 400);
            two(view, t, t+150, MotionEvent.ACTION_MOVE, 100, 400, 700, 400);
            two(view, t, t+180, MotionEvent.ACTION_MOVE, 50, 400, 750, 400);
            two(view, t, t+200, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50, 400, 750, 400);
            one(view, t, t+250, MotionEvent.ACTION_UP, 50, 400);
            assertEquals("Pinch/remaining-finger movement must not turn page", 0, pages.get());
            assertTrue("Pinch zoom remains available", view.zoomFactor() > 1.05f);
            view.reset(); swipe(view, t+2000, 700, 400, 100, 400, 300);
            assertEquals(1, pages.get());
        });
    }
    @Test public void doubleTapAndZoomedDragNeverTurnPage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomImageView view = view(); AtomicInteger pages = new AtomicInteger(); view.setOnPageChangeListener(pages::addAndGet);
            long t = SystemClock.uptimeMillis();
            one(view, t, t, MotionEvent.ACTION_DOWN, 400, 400); one(view, t, t+50, MotionEvent.ACTION_UP, 400, 400);
            one(view, t+120, t+120, MotionEvent.ACTION_DOWN, 400, 400); one(view, t+120, t+170, MotionEvent.ACTION_UP, 400, 400);
            assertTrue(view.zoomFactor() > 1.05f); assertEquals(0, pages.get());
            swipe(view, t+2000, 700, 400, 100, 400, 300); assertEquals(0, pages.get());
            view.reset(); swipe(view, t+4000, 100, 400, 700, 400, 300); assertEquals(-1, pages.get());
        });
    }
    @Test public void tapsVerticalDragsAndLongHoldsDoNotTurnPage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZoomImageView view = view(); AtomicInteger pages = new AtomicInteger(); view.setOnPageChangeListener(pages::addAndGet);
            long t = SystemClock.uptimeMillis();
            swipe(view, t, 400, 400, 410, 400, 100);
            swipe(view, t+2000, 400, 700, 410, 100, 300);
            swipe(view, t+4000, 400, 400, 401, 400, 2000);
            assertEquals(0, pages.get());
        });
    }
    private static ZoomImageView view() {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZoomImageView v = new ZoomImageView(c);
        v.layout(0, 0, 800, 800); v.setImageBitmap(Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888));
        return v;
    }
    private static void swipe(ZoomImageView v, long t, float x1, float y1, float x2, float y2, long duration) {
        one(v,t,t,MotionEvent.ACTION_DOWN,x1,y1); one(v,t,t+duration/2,MotionEvent.ACTION_MOVE,(x1+x2)/2,(y1+y2)/2);
        one(v,t,t+duration,MotionEvent.ACTION_UP,x2,y2);
    }
    private static void one(ZoomImageView v, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down,time,action,x,y,0); v.onTouchEvent(event); event.recycle();
    }
    private static void two(ZoomImageView v, long down, long time, int action, float x1, float y1, float x2, float y2) {
        MotionEvent.PointerProperties[] p = new MotionEvent.PointerProperties[2]; MotionEvent.PointerCoords[] c = new MotionEvent.PointerCoords[2];
        for (int i=0;i<2;i++) { p[i]=new MotionEvent.PointerProperties(); p[i].id=i; p[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            c[i]=new MotionEvent.PointerCoords(); c[i].x=i==0?x1:x2; c[i].y=i==0?y1:y2; c[i].pressure=1; c[i].size=1; }
        MotionEvent event = MotionEvent.obtain(down,time,action,2,p,c,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        v.onTouchEvent(event); event.recycle();
    }
}
