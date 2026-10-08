package com.winlator.gestures;

import org.junit.Test;
import com.winlator.xserver.XKeycode;
import java.util.ArrayList;
import java.util.EnumSet;
import static org.junit.Assert.*;

public class GestureKeyDispatcherTest {
    @Test public void quickLiftOfSwipeHoldKeepsThirtyMillisecondMinimumWithoutRepressing() {
        GestureRecognizerTest.Clock clock = new GestureRecognizerTest.Clock(); Sink sink = new Sink();
        GestureKeyDispatcher keys = new GestureKeyDispatcher(clock, sink);
        keys.press(XKeycode.KEY_RIGHT, true); clock.advance(10); keys.releaseHold();
        assertTrue(sink.held.contains(XKeycode.KEY_RIGHT)); clock.advance(29);
        assertTrue(sink.held.contains(XKeycode.KEY_RIGHT)); clock.advance(30);
        assertEquals("[down KEY_RIGHT, up KEY_RIGHT]", sink.events.toString());
    }
    @Test public void cancellingShortHoldNeverLeavesADeferredRelease() {
        GestureRecognizerTest.Clock clock = new GestureRecognizerTest.Clock(); Sink sink = new Sink();
        GestureKeyDispatcher keys = new GestureKeyDispatcher(clock, sink);
        keys.press(XKeycode.KEY_RIGHT, true); keys.cancel();
        assertTrue(sink.held.isEmpty()); assertTrue(clock.jobs.isEmpty());
        keys.press(XKeycode.KEY_RIGHT, true); clock.advance(10); keys.releaseHold(); keys.cancel();
        assertTrue(sink.held.isEmpty()); assertTrue(clock.jobs.isEmpty());
    }
    private static final class Sink implements GestureKeyDispatcher.Sink {
        final EnumSet<XKeycode> held = EnumSet.noneOf(XKeycode.class);
        final ArrayList<String> events = new ArrayList<>();
        @Override public void down(XKeycode key) { held.add(key); events.add("down " + key); }
        @Override public void up(XKeycode key) { held.remove(key); events.add("up " + key); }
    }
    @Test public void pulseIsImmediateAndReleasesAtThirtyMilliseconds() {
        GestureRecognizerTest.Clock clock = new GestureRecognizerTest.Clock(); Sink sink = new Sink();
        GestureKeyDispatcher keys = new GestureKeyDispatcher(clock, sink);
        keys.press(XKeycode.KEY_ENTER, false); assertTrue(sink.held.contains(XKeycode.KEY_ENTER));
        clock.advance(29); assertTrue(sink.held.contains(XKeycode.KEY_ENTER));
        clock.advance(30); assertTrue(sink.held.isEmpty());
    }
    @Test public void repeatedPulseHasDistinctDownAndOldTimerCannotReleaseNewPress() {
        GestureRecognizerTest.Clock clock = new GestureRecognizerTest.Clock(); Sink sink = new Sink();
        GestureKeyDispatcher keys = new GestureKeyDispatcher(clock, sink);
        keys.press(XKeycode.KEY_RIGHT, false); clock.advance(10); keys.press(XKeycode.KEY_RIGHT, false);
        assertEquals("[down KEY_RIGHT, up KEY_RIGHT, down KEY_RIGHT]", sink.events.toString());
        clock.advance(30); assertTrue(sink.held.contains(XKeycode.KEY_RIGHT));
        clock.advance(40); assertTrue(sink.held.isEmpty());
    }
    @Test public void longHoldHasNoTimeoutAndPulseDoesNotShortenIt() {
        GestureRecognizerTest.Clock clock = new GestureRecognizerTest.Clock(); Sink sink = new Sink();
        GestureKeyDispatcher keys = new GestureKeyDispatcher(clock, sink);
        keys.press(XKeycode.KEY_RIGHT, true); keys.press(XKeycode.KEY_RIGHT, false); clock.advance(120000);
        assertTrue(sink.held.contains(XKeycode.KEY_RIGHT)); keys.releaseHold(); assertTrue(sink.held.isEmpty());
    }
    @Test public void cancellationReleasesAllKeysAndRemovesQueuedWork() {
        GestureRecognizerTest.Clock clock = new GestureRecognizerTest.Clock(); Sink sink = new Sink();
        GestureKeyDispatcher keys = new GestureKeyDispatcher(clock, sink);
        keys.press(XKeycode.KEY_CTRL_L, true); keys.press(XKeycode.KEY_ENTER, false);
        keys.cancel(); keys.cancel(); int count = sink.events.size(); clock.advance(1000);
        assertTrue(sink.held.isEmpty()); assertTrue(clock.jobs.isEmpty()); assertEquals(count, sink.events.size());
    }
}
