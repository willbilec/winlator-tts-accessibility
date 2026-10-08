package com.winlator.gestures;

import org.junit.Test;
import com.winlator.xserver.XKeycode;
import java.util.List;
import static org.junit.Assert.*;

public class GestureMapStoreTest {
    @Test public void androidAliasesMigrateAndMergeWithoutLosingAssignments() throws Exception {
        Memory memory = new Memory();
        String alias = "1:/data/data/com.winlator/game.exe", canonical = "1:/data/user/0/com.winlator/game.exe";
        memory.value = "{\"games\":{\"" + alias + "\":{\"ONE_TAP\":\"KEY_SPACE\",\"ONE_HOLD\":\"KEY_RIGHT\"},\""
                + canonical + "\":{\"ONE_TAP\":\"KEY_TAB\"}},\"gameSwipeHolds\":{\"" + alias + "\":true},"
                + "\"recent\":[{\"container\":1,\"path\":\"/data/data/com.winlator/game.exe\"},"
                + "{\"container\":1,\"path\":\"/data/user/0/com.winlator/game.exe\"}]}";
        GestureMapStore store = new GestureMapStore(memory);
        GestureMapStore.Target target = game(1, "/data/user/0/com.winlator/game.exe");
        assertEquals(target.id(), game(1, "/data/data/com.winlator/game.exe").id());
        assertEquals(XKeycode.KEY_TAB, store.resolve(target, Gesture.ONE_TAP));
        assertEquals(XKeycode.KEY_RIGHT, store.resolve(target, Gesture.ONE_HOLD));
        assertTrue(store.swipeHolds(target)); assertEquals(1, store.targets(null).size());
        store.setSwipeHolds(target, false);
        org.json.JSONObject saved = new org.json.JSONObject(memory.value);
        assertFalse(saved.getJSONObject("games").has(alias));
        assertFalse(saved.getJSONObject("gameSwipeHolds").has(alias));
        assertEquals(target.path, saved.getJSONArray("recent").getJSONObject(0).getString("path"));
        assertFalse(new GestureMapStore(memory).swipeHolds(target));
        assertNotEquals(target.id(), game(1, "/data/user/10/com.winlator/game.exe").id());
    }
    private static final class Memory implements GestureMapStore.Storage {
        String value = "{}";
        @Override public String read() { return value; }
        @Override public void write(String value) { this.value = value; }
    }
    private static GestureMapStore.Target game(int container, String path) {
        return new GestureMapStore.Target(container, path, path, "Container " + container);
    }
    @Test public void factoryDefaultsAreOnlyArrowsAndEnter() {
        GestureMapStore store = new GestureMapStore(new Memory());
        assertEquals(XKeycode.KEY_ENTER, store.resolve(null, Gesture.ONE_TAP));
        assertEquals(XKeycode.KEY_DOWN, store.resolve(null, Gesture.ONE_DOWN));
        int mapped = 0;
        for (Gesture gesture : Gesture.values()) if (store.resolve(null, gesture) != XKeycode.KEY_NONE) mapped++;
        assertEquals(5, mapped);
    }
    @Test public void globalChangesPropagateExceptExplicitOverridesAndUnassigned() {
        Memory memory = new Memory(); GestureMapStore store = new GestureMapStore(memory);
        GestureMapStore.Target a = game(1, "/games/a.exe"), b = game(1, "/games/b.exe");
        store.assign(a, Gesture.ONE_TAP, XKeycode.KEY_SPACE);
        store.assign(b, Gesture.ONE_TAP, XKeycode.KEY_NONE);
        store.assign(null, Gesture.ONE_TAP, XKeycode.KEY_TAB);
        store = new GestureMapStore(memory);
        assertEquals(XKeycode.KEY_SPACE, store.resolve(a, Gesture.ONE_TAP));
        assertEquals(XKeycode.KEY_NONE, store.resolve(b, Gesture.ONE_TAP));
        assertEquals(XKeycode.KEY_TAB, store.resolve(game(2, "/games/a.exe"), Gesture.ONE_TAP));
        store.assign(a, Gesture.ONE_TAP, null);
        assertEquals(XKeycode.KEY_TAB, new GestureMapStore(memory).resolve(a, Gesture.ONE_TAP));
    }
    @Test public void normalizedPathsShareOnlyWithinTheirContainer() {
        GestureMapStore store = new GestureMapStore(new Memory());
        store.assign(game(1, "C:\\Games\\Test.exe"), Gesture.ONE_TAP, XKeycode.KEY_F1);
        assertEquals(XKeycode.KEY_F1, store.resolve(game(1, "c:/games//test.EXE"), Gesture.ONE_TAP));
        assertEquals(XKeycode.KEY_ENTER, store.resolve(game(2, "c:/games/test.exe"), Gesture.ONE_TAP));
    }
    @Test public void recentGamesAreDeduplicatedAndLimitedAndCurrentIsFirst() {
        Memory memory = new Memory(); GestureMapStore store = new GestureMapStore(memory);
        for (int i = 0; i < 12; i++) store.recordLaunch(game(1, "/" + i + ".exe"));
        store.recordLaunch(game(1, "/5.exe")); store.recordLaunch(null);
        List<GestureMapStore.Target> recent = new GestureMapStore(memory).targets(null);
        assertEquals(10, recent.size()); assertEquals("/5.exe", recent.get(0).path);
        List<GestureMapStore.Target> current = store.targets(game(2, "/current.exe"));
        assertEquals(11, current.size()); assertEquals("/current.exe", current.get(0).path);
        assertEquals(10, store.targets(game(1, "/5.exe")).size());
    }
    @Test public void automaticModeCountsOnlyPhysicalKeyboardsAndPersistsOverrides() {
        assertTrue(GestureMapStore.gesturesEnabled(GestureMapStore.Mode.AUTOMATIC, false));
        assertFalse(GestureMapStore.gesturesEnabled(GestureMapStore.Mode.AUTOMATIC, true));
        assertTrue(GestureMapStore.gesturesEnabled(GestureMapStore.Mode.GESTURES, true));
        assertFalse(GestureMapStore.gesturesEnabled(GestureMapStore.Mode.TOUCHPAD, false));
        Memory memory = new Memory(); GestureMapStore store = new GestureMapStore(memory);
        store.setMode(GestureMapStore.Mode.TOUCHPAD);
        assertEquals(GestureMapStore.Mode.TOUCHPAD, new GestureMapStore(memory).getMode());
    }
    @Test public void malformedAndUnknownSettingsRetainSafeDefaults() {
        Memory memory = new Memory(); memory.value = "invalid";
        GestureMapStore store = new GestureMapStore(memory);
        assertEquals(XKeycode.KEY_ENTER, store.resolve(null, Gesture.ONE_TAP));
        memory.value = "{\"global\":{\"ONE_TAP\":\"INVALID\",\"ONE_DOWN\":\"KEY_CUSTOM_1\"},\"mode\":\"INVALID\"}";
        store.reload(); assertEquals(XKeycode.KEY_ENTER, store.resolve(null, Gesture.ONE_TAP));
        assertEquals(XKeycode.KEY_DOWN, store.resolve(null, Gesture.ONE_DOWN));
        assertEquals(GestureMapStore.Mode.AUTOMATIC, store.getMode());
    }
    @Test public void swipeHoldsDefaultToQuickAndInheritExceptPerGameOverrides() {
        Memory memory = new Memory(); GestureMapStore store = new GestureMapStore(memory);
        GestureMapStore.Target a = game(1, "/a.exe"), b = game(1, "/b.exe");
        assertFalse(store.swipeHolds(a));
        store.setSwipeHolds(a, true);
        assertTrue(store.swipeHolds(a)); assertFalse(store.swipeHolds(b));
        store.setSwipeHolds(null, true); store.setSwipeHolds(b, false);
        store = new GestureMapStore(memory);
        assertTrue(store.swipeHolds(a)); assertFalse(store.swipeHolds(b));
        assertTrue(store.swipeHolds(game(2, "/b.exe")));
        store.setSwipeHolds(b, null); assertTrue(store.swipeHolds(b));
        store.setSwipeHolds(null, false); assertFalse(store.swipeHolds(b)); assertTrue(store.swipeHolds(a));
    }
}
