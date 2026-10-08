package com.winlator.gestures;

/** Touch recognition without Android dependencies, disk access, or event-path logging. */
public final class GestureRecognizer {
    public interface Scheduler {
        long now();
        void at(Runnable task, long uptime);
        void remove(Runnable task);
    }
    public interface Listener {
        boolean mapped(Gesture gesture);
        default boolean holdSwipes() { return false; }
        void press(Gesture gesture, boolean hold);
        void releaseHold();
        void openMenu();
    }
    private static final long CHORD_SETTLE_MS = 60;
    private static final class Finger {
        int id = -1;
        float startX, startY, x, y;
    }
    private final Finger[] fingers = {new Finger(), new Finger(), new Finger(), new Finger()};
    private final Scheduler scheduler;
    private final Listener listener;
    private final float slop, swipeDistance, doubleTapSlop;
    private final long doubleTapTimeout, longPressTimeout;
    private int active, count, pendingCount, pendingFingers;
    private long start, lastTap;
    private float startX, startY, pendingX, pendingY;
    private boolean moved, invalid, committed, lifted, holding;
    private final Runnable settle;
    private final Runnable tapTimeout = this::flushTaps;
    private final Runnable holdTimeout = this::startHold;

    public GestureRecognizer(Scheduler scheduler, Listener listener, float slop, float swipeDistance,
            float doubleTapSlop, long doubleTapTimeout, long longPressTimeout) {
        this.scheduler = scheduler;
        this.settle = () -> checkSwipe(scheduler.now());
        this.listener = listener;
        this.slop = slop;
        this.swipeDistance = swipeDistance;
        this.doubleTapSlop = doubleTapSlop;
        this.doubleTapTimeout = doubleTapTimeout;
        this.longPressTimeout = longPressTimeout;
    }

    public void down(int id, float x, float y, long time) {
        if (active == 0) {
            if (pendingCount > 0 && (time - lastTap > doubleTapTimeout ||
                    distance(x - pendingX, y - pendingY) > doubleTapSlop)) flushTaps();
            scheduler.remove(tapTimeout);
            start = time; startX = x; startY = y;
            count = 0; moved = invalid = committed = lifted = holding = false;
            scheduler.at(settle, time + CHORD_SETTLE_MS);
        }
        if (active >= fingers.length || lifted || committed || find(id) != null) {
            invalidate();
            return;
        }
        for (Finger finger : fingers) if (finger.id < 0) {
            finger.id = id;
            finger.startX = finger.x = x;
            finger.startY = finger.y = y;
            break;
        }
        active++;
        count = Math.max(count, active);
        scheduler.remove(holdTimeout);
        if (!moved && !invalid && count <= 3) scheduler.at(holdTimeout, time + longPressTimeout);
    }

    public void move(int id, float x, float y) {
        Finger finger = find(id);
        if (finger == null) { invalidate(); return; }
        finger.x = x; finger.y = y;
        if (distance(x - finger.startX, y - finger.startY) > slop) {
            moved = true;
            scheduler.remove(holdTimeout);
        }
    }

    /** Called after updating every pointer in a MotionEvent (including history). */
    public void frame(long time) { checkSwipe(time); }

    public void up(int id, float x, float y, long time) {
        move(id, x, y);
        // Finger lift finalizes the chord, so even a swipe shorter than the enrollment window is valid.
        checkSwipe(Math.max(time, start + CHORD_SETTLE_MS));
        Finger finger = find(id);
        if (finger == null) return;
        lifted = true;
        scheduler.remove(holdTimeout);
        scheduler.remove(settle);
        if (holding) { listener.releaseHold(); holding = false; }
        finger.id = -1;
        active--;
        if (active != 0) return;
        if (!invalid && !committed && !moved && time - start < longPressTimeout) {
            if (count == 4) { flushTaps(); listener.openMenu(); }
            else if (count >= 1 && count <= 3) finishTap(time);
        } else if (!committed) flushTaps();
    }

    private Finger find(int id) {
        for (Finger finger : fingers) if (finger.id == id) return finger;
        return null;
    }
    private static float distance(float dx, float dy) { return (float)Math.hypot(dx, dy); }

    private void checkSwipe(long time) {
        if (invalid || committed || lifted || active == 0 || count > 3 || time - start < CHORD_SETTLE_MS) return;
        Gesture.Action direction = null;
        for (Finger finger : fingers) {
            if (finger.id < 0) continue;
            float dx = finger.x - finger.startX, dy = finger.y - finger.startY;
            Gesture.Action candidate;
            if (Math.abs(dx) >= swipeDistance && Math.abs(dx) > Math.abs(dy) * 1.5f)
                candidate = dx > 0 ? Gesture.Action.RIGHT : Gesture.Action.LEFT;
            else if (Math.abs(dy) >= swipeDistance && Math.abs(dy) > Math.abs(dx) * 1.5f)
                candidate = dy > 0 ? Gesture.Action.DOWN : Gesture.Action.UP;
            else return;
            if (direction != null && direction != candidate) return;
            direction = candidate;
        }
        flushTaps();
        committed = true;
        holding = listener.holdSwipes();
        scheduler.remove(holdTimeout);
        listener.press(Gesture.of(count, direction), holding);
    }

    private void startHold() {
        if (active == 0 || invalid || moved || committed || lifted || count > 3) return;
        Gesture gesture = Gesture.of(count, Gesture.Action.HOLD);
        if (!listener.mapped(gesture)) return;
        flushTaps();
        holding = committed = true;
        listener.press(gesture, true);
    }

    private void finishTap(long time) {
        if (pendingCount > 0 && pendingFingers != count) flushTaps();
        if (pendingCount == 0) { pendingFingers = count; pendingX = startX; pendingY = startY; }
        pendingCount++;
        lastTap = time;
        boolean longer = false;
        for (int next = pendingCount + 1; next <= 3; next++) {
            if (listener.mapped(Gesture.tap(count, next))) longer = true;
        }
        if (longer) scheduler.at(tapTimeout, time + doubleTapTimeout);
        else flushTaps();
    }

    private void flushTaps() {
        scheduler.remove(tapTimeout);
        if (pendingCount == 0) return;
        Gesture gesture = Gesture.tap(pendingFingers, pendingCount);
        pendingCount = 0;
        listener.press(gesture, false);
    }
    private void invalidate() {
        invalid = true;
        scheduler.remove(holdTimeout);
        if (holding) { listener.releaseHold(); holding = false; }
    }
    public void cancel() {
        scheduler.remove(settle); scheduler.remove(holdTimeout); scheduler.remove(tapTimeout);
        if (holding) listener.releaseHold();
        for (Finger finger : fingers) finger.id = -1;
        active = count = pendingCount = 0;
        holding = false; invalid = true;
    }
}
