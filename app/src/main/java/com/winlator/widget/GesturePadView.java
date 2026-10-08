package com.winlator.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import com.winlator.gestures.GestureRecognizer;

/** Transparent gameplay surface; dialog focus and accessibility are managed separately. */
public final class GesturePadView extends View {
    private final GestureRecognizer recognizer;
    private final Runnable cancelKeys;
    private boolean accepting;

    public static GestureRecognizer.Scheduler scheduler() {
        Handler handler = new Handler(Looper.getMainLooper());
        return new GestureRecognizer.Scheduler() {
            @Override public long now() { return SystemClock.uptimeMillis(); }
            @Override public void at(Runnable task, long time) { handler.postAtTime(task, time); }
            @Override public void remove(Runnable task) { handler.removeCallbacks(task); }
        };
    }
    public GesturePadView(Context context, GestureRecognizer.Scheduler scheduler,
            GestureRecognizer.Listener listener, Runnable cancelKeys) {
        super(context);
        this.cancelKeys = cancelKeys;
        ViewConfiguration config = ViewConfiguration.get(context);
        recognizer = new GestureRecognizer(scheduler, listener, config.getScaledTouchSlop(),
                Math.max(config.getScaledTouchSlop() * 2, 24 * getResources().getDisplayMetrics().density),
                config.getScaledDoubleTapSlop(), ViewConfiguration.getDoubleTapTimeout(), ViewConfiguration.getLongPressTimeout());
        setClickable(true);
        // This prevents noisy focus announcements. It does NOT bypass a screen reader's touch explorer.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public void setAccepting(boolean value) {
        if (accepting != value) cancel();
        accepting = value;
    }
    public void cancel() { recognizer.cancel(); cancelKeys.run(); }
    public boolean isAccepting() { return accepting; }
    @Override protected void onDetachedFromWindow() { cancel(); super.onDetachedFromWindow(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) cancel();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false;
        if (!accepting) return true;
        int action = event.getActionMasked(), index = event.getActionIndex();
        long time = event.getEventTime();
        if (action == MotionEvent.ACTION_CANCEL) { cancel(); return true; }
        if (action == MotionEvent.ACTION_DOWN) {
            requestUnbufferedDispatch(event);
            getParent().requestDisallowInterceptTouchEvent(true);
            recognizer.down(event.getPointerId(index), event.getX(index), event.getY(index), time);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            recognizer.down(event.getPointerId(index), event.getX(index), event.getY(index), time);
            updatePointers(event);
        } else {
            // Preserve intermediate movements so a fast out-and-back swipe is not lost in batching.
            for (int h = 0; h < event.getHistorySize(); h++) {
                for (int p = 0; p < event.getPointerCount(); p++) recognizer.move(event.getPointerId(p),
                        event.getHistoricalX(p, h), event.getHistoricalY(p, h));
                recognizer.frame(event.getHistoricalEventTime(h));
            }
            updatePointers(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                recognizer.up(event.getPointerId(index), event.getX(index), event.getY(index), time);
            }
        }
        return true;
    }
    private void updatePointers(MotionEvent event) {
        for (int p = 0; p < event.getPointerCount(); p++) recognizer.move(event.getPointerId(p), event.getX(p), event.getY(p));
        recognizer.frame(event.getEventTime());
    }
}
