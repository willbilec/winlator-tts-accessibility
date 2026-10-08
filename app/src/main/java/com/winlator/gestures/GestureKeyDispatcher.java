package com.winlator.gestures;

import com.winlator.xserver.XKeycode;
import java.util.EnumMap;

/** Owns only gesture keys. Queued releases cannot escape cancellation or a new press. */
public final class GestureKeyDispatcher {
    public interface Sink { void down(XKeycode key); void up(XKeycode key); }
    private final GestureRecognizer.Scheduler scheduler;
    private final Sink sink;
    private final EnumMap<XKeycode, Runnable> pulses = new EnumMap<>(XKeycode.class);
    private XKeycode held = XKeycode.KEY_NONE;
    private long holdMinimumUntil;

    public GestureKeyDispatcher(GestureRecognizer.Scheduler scheduler, Sink sink) {
        this.scheduler = scheduler; this.sink = sink;
    }
    public void press(XKeycode key, boolean hold) {
        if (key == null || key == XKeycode.KEY_NONE || key == held) return;
        Runnable previous = pulses.remove(key);
        if (previous != null) { scheduler.remove(previous); sink.up(key); }
        sink.down(key);
        if (hold) { releaseHold(); held = key; holdMinimumUntil = scheduler.now() + 30; }
        else {
            Runnable release = () -> { pulses.remove(key); sink.up(key); };
            pulses.put(key, release);
            scheduler.at(release, scheduler.now() + 30);
        }
    }
    public void releaseHold() {
        if (held == XKeycode.KEY_NONE) return;
        XKeycode key = held;
        held = XKeycode.KEY_NONE;
        if (scheduler.now() < holdMinimumUntil) {
            Runnable release = () -> { pulses.remove(key); sink.up(key); };
            pulses.put(key, release);
            scheduler.at(release, holdMinimumUntil);
        } else sink.up(key);
    }
    public void cancel() {
        for (XKeycode key : pulses.keySet()) { scheduler.remove(pulses.get(key)); sink.up(key); }
        pulses.clear();
        // Cancellation releases immediately, even during a short swipe's minimum pulse.
        if (held != XKeycode.KEY_NONE) { sink.up(held); held = XKeycode.KEY_NONE; }
    }
}
