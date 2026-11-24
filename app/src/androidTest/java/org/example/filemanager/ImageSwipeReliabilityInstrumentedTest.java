package org.example.filemanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImageSwipeReliabilityInstrumentedTest {
    @Test public void translatedImageTracksFingerAndAcceptsShortDrag() { onMain(() -> {
        Fixture f = new Fixture(); long t = SystemClock.uptimeMillis();
        f.event(t,t,MotionEvent.ACTION_DOWN,200,200);
        f.event(t,t+100,MotionEvent.ACTION_MOVE,180,200);
        assertEquals(-f.dp(20), f.view.getTranslationX(), 1f);
        f.event(t,t+200,MotionEvent.ACTION_MOVE,160,200);
        assertEquals("The translated child must not subtract its own movement", -f.dp(40), f.view.getTranslationX(), 1f);
        f.event(t,t+300,MotionEvent.ACTION_UP,160,200);
        assertEquals(1, f.pages.get());
    }); }
    @Test public void slowHorizontalDragStillTurnsPage() { onMain(() -> {
        Fixture f = new Fixture(); f.swipe(200,200,100,200,1600);
        assertEquals(1, f.pages.get());
    }); }
    @Test public void naturalDiagonalSwipeTurnsPage() { onMain(() -> {
        Fixture f = new Fixture(); f.swipe(200,200,130,250,300);
        assertEquals(1, f.pages.get());
    }); }
    @Test public void shortFastFlickWorksInBothDirections() { onMain(() -> {
        Fixture left = new Fixture(); left.swipe(200,200,176,200,30); assertEquals(1,left.pages.get());
        Fixture right = new Fixture(); right.swipe(200,200,224,200,30); assertEquals(-1,right.pages.get());
    }); }
    @Test public void jitterVerticalMotionCancellationAndBoundariesDoNotTurnPage() { onMain(() -> {
        Fixture tap = new Fixture(); tap.swipe(200,200,204,202,100); assertEquals(0,tap.pages.get());
        Fixture hold = new Fixture(); hold.swipe(200,200,201,200,1800); assertEquals(0,hold.pages.get());
        Fixture vertical = new Fixture(); vertical.swipe(200,200,215,300,300); assertEquals(0,vertical.pages.get());
        Fixture cancel = new Fixture(); long t = SystemClock.uptimeMillis();
        cancel.event(t,t,MotionEvent.ACTION_DOWN,200,200); cancel.event(t,t+100,MotionEvent.ACTION_MOVE,100,200);
        cancel.event(t,t+150,MotionEvent.ACTION_CANCEL,100,200); assertEquals(0,cancel.pages.get());
        Fixture edge = new Fixture(); edge.view.setCanChangePageListener(delta -> false);
        edge.swipe(200,200,100,200,300); assertEquals(0,edge.pages.get());
    }); }
    private static void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static class Fixture {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final FrameLayout host = new FrameLayout(context);
        final ZoomImageView view = new ZoomImageView(context);
        final AtomicInteger pages = new AtomicInteger();
        Fixture() {
            host.addView(view,new FrameLayout.LayoutParams(-1,-1));
            host.measure(View.MeasureSpec.makeMeasureSpec(dp(400),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dp(400),View.MeasureSpec.EXACTLY));
            host.layout(0,0,dp(400),dp(400));
            view.setImageBitmap(Bitmap.createBitmap(100,60,Bitmap.Config.ARGB_8888));
            view.setOnPageChangeListener(pages::addAndGet);
        }
        int dp(int value) { return Ui.dp(context,value); }
        void event(long down,long time,int action,int x,int y) {
            MotionEvent event=MotionEvent.obtain(down,time,action,dp(x),dp(y),0);
            try { assertTrue(host.dispatchTouchEvent(event)); } finally { event.recycle(); }
        }
        void swipe(int x1,int y1,int x2,int y2,int duration) {
            long t=SystemClock.uptimeMillis(); event(t,t,MotionEvent.ACTION_DOWN,x1,y1);
            for(int i=1;i<=5;i++) event(t,t+duration*i/6,MotionEvent.ACTION_MOVE,x1+(x2-x1)*i/6,y1+(y2-y1)*i/6);
            event(t,t+duration,MotionEvent.ACTION_UP,x2,y2);
        }
    }
}
