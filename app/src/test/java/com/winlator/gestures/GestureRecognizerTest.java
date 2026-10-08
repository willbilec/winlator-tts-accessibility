package com.winlator.gestures;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import static org.junit.Assert.*;

public class GestureRecognizerTest {
    static final class Clock implements GestureRecognizer.Scheduler {
        static final class Job {
            final Runnable run; final long time;
            Job(Runnable run, long time) { this.run = run; this.time = time; }
        }
        long time;
        final ArrayList<Job> jobs = new ArrayList<>();
        @Override public long now() { return time; }
        @Override public void at(Runnable job, long when) { jobs.add(new Job(job, when)); }
        @Override public void remove(Runnable job) { jobs.removeIf(item -> item.run == job); }
        void advance(long when) {
            while (true) {
                jobs.sort(Comparator.comparingLong(item -> item.time));
                if (jobs.isEmpty() || jobs.get(0).time > when) break;
                Job item = jobs.remove(0); time = item.time; item.run.run();
            }
            time = when;
        }
    }
    static final class Fixture implements GestureRecognizer.Listener {
        final Clock clock = new Clock();
        final EnumSet<Gesture> mapped = EnumSet.of(Gesture.ONE_TAP, Gesture.ONE_DOWN, Gesture.ONE_UP, Gesture.ONE_LEFT, Gesture.ONE_RIGHT);
        final ArrayList<String> events = new ArrayList<>();
        boolean holdSwipes = true;
        final GestureRecognizer r = new GestureRecognizer(clock, this, 8, 24, 80, 300, 500);
        @Override public boolean mapped(Gesture gesture) { return mapped.contains(gesture); }
        @Override public boolean holdSwipes() { return holdSwipes; }
        @Override public void press(Gesture gesture, boolean hold) {
            if (mapped(gesture)) events.add(gesture.name() + (hold ? ":hold" : ""));
        }
        @Override public void releaseHold() { events.add("release"); }
        @Override public void openMenu() { events.add("menu"); }
        void down(int id, float x, float y, long t) { clock.advance(t); r.down(id, x, y, t); }
        void up(int id, float x, float y, long t) { clock.advance(t); r.up(id, x, y, t); }
        void tap(long t) { down(0, 100, 100, t); up(0, 100, 100, t + 20); }
    }
    @Test public void defaultTapIsImmediateAndRepeatedTapsStaySeparate() {
        Fixture f = new Fixture(); f.tap(0); f.tap(80);
        assertEquals("[ONE_TAP, ONE_TAP]", f.events.toString());
    }
    @Test public void doubleTapProducesOnlyDoubleWithNoSinglePress() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.ONE_DOUBLE_TAP);
        f.tap(0); assertTrue(f.events.isEmpty()); f.tap(100);
        assertEquals("[ONE_DOUBLE_TAP]", f.events.toString()); f.clock.advance(1000);
        assertEquals(1, f.events.size());
    }
    @Test public void tripleTapWinsOverMappedSingleAndDouble() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.ONE_DOUBLE_TAP); f.mapped.add(Gesture.ONE_TRIPLE_TAP);
        f.tap(0); f.tap(100); assertTrue(f.events.isEmpty()); f.tap(200);
        assertEquals("[ONE_TRIPLE_TAP]", f.events.toString());
    }
    @Test public void shorterTapWaitsOnlyForMappedLongerTap() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.ONE_DOUBLE_TAP); f.tap(0);
        f.clock.advance(319); assertTrue(f.events.isEmpty()); f.clock.advance(320);
        assertEquals("[ONE_TAP]", f.events.toString());
    }
    @Test public void secondTapBeginningBeforeDeadlineCanFinishAfterIt() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.ONE_DOUBLE_TAP); f.tap(0);
        f.down(0, 100, 100, 310); f.up(0, 100, 100, 350);
        assertEquals("[ONE_DOUBLE_TAP]", f.events.toString());
    }
    @Test public void differentFingerCountsNeverBecomeOneDoubleTap() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.ONE_DOUBLE_TAP); f.mapped.add(Gesture.TWO_TAP);
        f.tap(0); f.down(0, 100, 100, 100); f.down(7, 130, 100, 105);
        f.up(7, 130, 100, 120); f.up(0, 100, 100, 125);
        assertEquals("[ONE_TAP, TWO_TAP]", f.events.toString());
    }
    @Test public void spatiallySeparateTapsDoNotBecomeDouble() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.ONE_DOUBLE_TAP); f.tap(0);
        f.down(0, 300, 100, 100); f.up(0, 300, 100, 120); f.clock.advance(420);
        assertEquals("[ONE_TAP, ONE_TAP]", f.events.toString());
    }
    @Test public void fastSwipeCommitsAtThresholdWithoutWaitingForLift() {
        Fixture f = new Fixture(); f.down(4, 100, 100, 0);
        f.clock.advance(70); f.r.move(4, 100, 135); f.r.frame(70);
        assertEquals("[ONE_DOWN:hold]", f.events.toString());
        f.r.move(4, 100, 200); f.r.frame(80); f.up(4, 100, 200, 90);
        assertEquals("[ONE_DOWN:hold, release]", f.events.toString());
    }
    @Test public void quickSwipeModePulsesOnceEvenWhenFingerRemainsDown() {
        Fixture f = new Fixture(); f.holdSwipes = false; f.down(0, 0, 0, 0);
        f.clock.advance(70); f.r.move(0, 100, 0); f.r.frame(70); f.clock.advance(5000);
        assertEquals("[ONE_RIGHT]", f.events.toString()); f.up(0, 100, 0, 6000);
        assertEquals("[ONE_RIGHT]", f.events.toString());
    }
    @Test public void swipeHoldPersistsWithoutMovementAndReleasesOnLift() {
        Fixture f = new Fixture(); f.down(0, 0, 0, 0);
        f.clock.advance(70); f.r.move(0, 100, 0); f.r.frame(70); f.clock.advance(5000);
        assertEquals("[ONE_RIGHT:hold]", f.events.toString()); f.up(0, 100, 0, 6000);
        assertEquals("[ONE_RIGHT:hold, release]", f.events.toString());
    }
    @Test public void initialChordWindowProtectsMultiFingerSwipe() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.TWO_DOWN);
        f.down(9, 100, 100, 0); f.r.move(9, 100, 135); f.r.frame(20);
        assertTrue(f.events.isEmpty()); f.down(3, 140, 100, 30);
        f.r.move(3, 140, 135); f.r.frame(40); f.clock.advance(60);
        assertEquals("[TWO_DOWN:hold]", f.events.toString());
    }
    @Test public void veryFastSwipeFinishesAtLiftWithoutWaitingForChordTimer() {
        Fixture f = new Fixture(); f.down(0, 0, 0, 0); f.up(0, 0, 50, 35);
        assertEquals("[ONE_DOWN:hold, release]", f.events.toString()); f.clock.advance(1000);
        assertEquals(2, f.events.size());
    }
    @Test public void threeFingerSwipeRequiresConsistentDirections() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.THREE_RIGHT);
        for (int i = 0; i < 3; i++) f.down(i, 100, 100 + i * 40, i * 5);
        f.clock.advance(70);
        f.r.move(0, 140, 100); f.r.move(1, 140, 140); f.r.move(2, 60, 180); f.r.frame(70);
        assertTrue(f.events.isEmpty());
        f.r.move(2, 140, 180); f.r.frame(80);
        assertEquals("[THREE_RIGHT:hold]", f.events.toString());
    }
    @Test public void diagonalAndSmallMovementsDoNotProduceSwipeOrTap() {
        Fixture f = new Fixture(); f.down(0, 0, 0, 0); f.up(0, 30, 30, 100);
        f.clock.advance(1000); assertTrue(f.events.isEmpty());
        f.down(0, 0, 0, 1100); f.up(0, 12, 0, 1200); assertTrue(f.events.isEmpty());
    }
    @Test public void holdReleasesOnFirstFingerLiftAndDoesNotTap() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.TWO_HOLD);
        f.down(0, 100, 100, 0); f.down(5, 150, 100, 10); f.clock.advance(510);
        assertEquals("[TWO_HOLD:hold]", f.events.toString());
        f.up(5, 150, 100, 800); f.up(0, 100, 100, 810);
        assertEquals("[TWO_HOLD:hold, release]", f.events.toString());
    }
    @Test public void movementPreventsLongPress() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.ONE_HOLD);
        f.down(0, 0, 0, 0); f.r.move(0, 12, 0); f.r.frame(100); f.clock.advance(1000);
        assertTrue(f.events.isEmpty());
    }
    @Test public void fingerAddedAfterLiftInvalidatesChord() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.TWO_TAP);
        f.down(0, 0, 0, 0); f.down(1, 30, 0, 5); f.up(1, 30, 0, 15);
        f.down(2, 40, 0, 20); f.up(0, 0, 0, 30); f.up(2, 40, 0, 35);
        assertTrue(f.events.isEmpty());
    }
    @Test public void fourFingerTapOpensMenuWithoutSendingEnter() {
        Fixture f = new Fixture();
        for (int i = 0; i < 4; i++) f.down(i, i * 30, 0, i * 5);
        for (int i = 0; i < 4; i++) f.up(i, i * 30, 0, 50 + i * 5);
        assertEquals("[menu]", f.events.toString());
    }
    @Test public void fifthFingerInvalidatesMenuTap() {
        Fixture f = new Fixture();
        for (int i = 0; i < 5; i++) f.down(i, i * 30, 0, i * 5);
        for (int i = 0; i < 5; i++) f.up(i, i * 30, 0, 50 + i * 5);
        assertTrue(f.events.isEmpty());
    }
    @Test public void cancellationDropsPendingTapAndHoldTimers() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.ONE_DOUBLE_TAP); f.mapped.add(Gesture.ONE_HOLD);
        f.tap(0); f.r.cancel(); f.clock.advance(1000); assertTrue(f.events.isEmpty());
        f.down(0, 0, 0, 1100); f.r.cancel(); f.clock.advance(2000); assertTrue(f.events.isEmpty());
    }
    @Test public void cancellingAnActiveHoldReleasesExactlyOnce() {
        Fixture f = new Fixture(); f.mapped.add(Gesture.ONE_HOLD);
        f.down(0, 0, 0, 0); f.clock.advance(500); f.r.cancel(); f.r.cancel();
        assertEquals("[ONE_HOLD:hold, release]", f.events.toString());
    }
}
