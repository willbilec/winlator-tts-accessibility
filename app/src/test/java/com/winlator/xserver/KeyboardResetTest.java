package com.winlator.xserver;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class KeyboardResetTest {
    private static final class Guest implements Keyboard.OnKeyboardListener {
        final Set<Byte> held = new HashSet<>();
        int presses;
        @Override public void onKeyPress(byte key, int keysym) { held.add(key); presses++; }
        @Override public void onKeyRelease(byte key) { held.remove(key); }
    }

    @Test public void missingBluetoothKeyUpDoesNotSurviveReset() {
        Keyboard keyboard = new Keyboard(null);
        Guest guest = new Guest();
        keyboard.addOnKeyboardListener(guest);
        keyboard.setKeyPress(XKeycode.KEY_RIGHT.id, 0);
        assertTrue(guest.held.contains(XKeycode.KEY_RIGHT.id));
        // Keyboard disconnects before sending Right-up.
        keyboard.reset();
        assertTrue(guest.held.isEmpty());
        // A queued repeat from the previous hold must not restart it.
        keyboard.setKeyPress(XKeycode.KEY_RIGHT.id, 0, true);
        assertTrue(guest.held.isEmpty());
        keyboard.setKeyPress(XKeycode.KEY_ENTER.id, 0);
        assertTrue(guest.held.contains(XKeycode.KEY_ENTER.id));
        keyboard.setKeyRelease(XKeycode.KEY_ENTER.id);
        keyboard.setKeyPress(XKeycode.KEY_RIGHT.id, 0);
        assertTrue(guest.held.contains(XKeycode.KEY_RIGHT.id));
        assertEquals(3, guest.presses);
    }

    @Test public void resetAlsoClearsGuestKeysMissingFromLocalTracking() {
        Keyboard keyboard = new Keyboard(null);
        Guest guest = new Guest();
        keyboard.addOnKeyboardListener(guest);
        // Wine saw a down that Winlator no longer considers held.
        guest.held.add(XKeycode.KEY_RIGHT.id);
        keyboard.reset();
        assertTrue(guest.held.isEmpty());
        keyboard.reset();
        assertTrue(guest.held.isEmpty());
    }

    @Test public void resetClearsTransientModifiersAndPreservesLocks() {
        Keyboard keyboard = new Keyboard(null);
        keyboard.setKeyPress(XKeycode.KEY_CAPS_LOCK.id, 0);
        keyboard.setKeyRelease(XKeycode.KEY_CAPS_LOCK.id);
        keyboard.setKeyPress(XKeycode.KEY_NUM_LOCK.id, 0);
        keyboard.setKeyRelease(XKeycode.KEY_NUM_LOCK.id);
        keyboard.setKeyPress(XKeycode.KEY_SHIFT_L.id, 0);
        keyboard.setKeyPress(XKeycode.KEY_CTRL_R.id, 0);
        keyboard.setKeyPress(XKeycode.KEY_ALT_L.id, 0);
        keyboard.reset();
        assertFalse(keyboard.getModifiersMask().isSet(1 | 4 | 8));
        assertTrue(keyboard.getModifiersMask().isSet(2));
        assertTrue(keyboard.getModifiersMask().isSet(16));
    }
}
