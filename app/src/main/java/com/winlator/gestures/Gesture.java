package com.winlator.gestures;

/** Stable IDs are persisted, independently of translated labels. */
public enum Gesture {
    ONE_UP(1, Action.UP), ONE_DOWN(1, Action.DOWN), ONE_LEFT(1, Action.LEFT), ONE_RIGHT(1, Action.RIGHT),
    ONE_TAP(1, Action.TAP), ONE_DOUBLE_TAP(1, Action.DOUBLE_TAP), ONE_TRIPLE_TAP(1, Action.TRIPLE_TAP), ONE_HOLD(1, Action.HOLD),
    TWO_UP(2, Action.UP), TWO_DOWN(2, Action.DOWN), TWO_LEFT(2, Action.LEFT), TWO_RIGHT(2, Action.RIGHT),
    TWO_TAP(2, Action.TAP), TWO_DOUBLE_TAP(2, Action.DOUBLE_TAP), TWO_TRIPLE_TAP(2, Action.TRIPLE_TAP), TWO_HOLD(2, Action.HOLD),
    THREE_UP(3, Action.UP), THREE_DOWN(3, Action.DOWN), THREE_LEFT(3, Action.LEFT), THREE_RIGHT(3, Action.RIGHT),
    THREE_TAP(3, Action.TAP), THREE_DOUBLE_TAP(3, Action.DOUBLE_TAP), THREE_TRIPLE_TAP(3, Action.TRIPLE_TAP), THREE_HOLD(3, Action.HOLD);

    public enum Action { UP, DOWN, LEFT, RIGHT, TAP, DOUBLE_TAP, TRIPLE_TAP, HOLD }
    public final int fingers;
    public final Action action;

    Gesture(int fingers, Action action) { this.fingers = fingers; this.action = action; }

    public static Gesture of(int fingers, Action action) {
        for (Gesture gesture : values()) if (gesture.fingers == fingers && gesture.action == action) return gesture;
        return null;
    }

    public static Gesture tap(int fingers, int count) {
        return of(fingers, count == 3 ? Action.TRIPLE_TAP : count == 2 ? Action.DOUBLE_TAP : Action.TAP);
    }
}
