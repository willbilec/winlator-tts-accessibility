package com.winlator.gestures;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ListView;
import android.widget.Button;
import android.widget.Spinner;
import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.contentdialog.GestureManagementDialog;
import com.winlator.widget.GesturePadView;
import com.winlator.xserver.XKeycode;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Real Android dialogs/MotionEvents, plus a diagnostic Wine window, never gameplay automation. */
@RunWith(AndroidJUnit4.class)
public class GestureDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private static final class Memory implements GestureMapStore.Storage {
        String value = "{}";
        @Override public String read() { return value; }
        @Override public void write(String value) { this.value = value; }
    }
    private static <T> ArrayList<T> views(View root, Class<T> type) {
        ArrayList<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++)
            result.addAll(views(((ViewGroup)root).getChildAt(i), type));
        return result;
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private void idle() { instrumentation.waitForIdleSync(); SystemClock.sleep(100); }
    private static AccessibilityNodeInfo editable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = editable(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    @Test public void managementAndSearchAreAccessibleAndPersistTheChosenKey() throws Exception {
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        GestureMapStore store = new GestureMapStore(new Memory());
        GestureMapStore.Target target = new GestureMapStore.Target(1, "/gesture-test.exe", "Current game", "Test container");
        for (int i = 0; i < 12; i++) store.recordLaunch(new GestureMapStore.Target(1, "/game" + i + ".exe", "Game " + i, "Test container"));
        AlertDialog[] dialog = new AlertDialog[1];
        try {
            instrumentation.runOnMainSync(() -> dialog[0] = GestureManagementDialog.show(activity, store, target, () -> {}, () -> {}));
            idle();
            instrumentation.runOnMainSync(() -> {
                ArrayList<Spinner> spinners = views(dialog[0].getWindow().getDecorView(), Spinner.class);
                assertEquals(3, spinners.size());
                assertEquals(12, spinners.get(0).getCount()); // Global, current, last ten.
                assertEquals(1, spinners.get(0).getSelectedItemPosition());
                assertEquals("Global default gesture map", spinners.get(0).getItemAtPosition(0));
                ArrayList<Button> rows = views(dialog[0].getWindow().getDecorView(), Button.class);
                int gestures = 0;
                for (Button row : rows) if (row.getTag() instanceof Gesture) gestures++;
                assertEquals(24, gestures);
                spinners.get(2).setSelection(2); // Hold swipes for this game only.
                for (Button row : rows) if (row.getTag() == Gesture.ONE_TAP) row.performClick();
            });
            idle();
            // Retain the user's accessibility services during node inspection.
            UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            assertNotNull(root);
            assertFalse("Search field label must be exposed", root.findAccessibilityNodeInfosByText("Search keys").isEmpty());
            AccessibilityNodeInfo edit = editable(root);
            assertNotNull("Search field must be exposed as editable", edit);
            Bundle text = new Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Page Down");
            assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text));
            idle(); root = automation.getRootInActiveWindow();
            ArrayList<AccessibilityNodeInfo> matches = new ArrayList<>(root.findAccessibilityNodeInfosByText("Page Down"));
            AccessibilityNodeInfo choice = null;
            for (AccessibilityNodeInfo node : matches) if (!node.isEditable() && "Page Down".contentEquals(node.getText() == null ? "" : node.getText())) {
                choice = node; break;
            }
            assertNotNull("Filtered guest key must be exposed", choice);
            assertTrue(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            idle();
            assertEquals(XKeycode.KEY_NEXT, store.resolve(target, Gesture.ONE_TAP));
            assertTrue(store.swipeHolds(target)); assertFalse(store.swipeHolds(null));
        } finally {
            instrumentation.runOnMainSync(() -> { if (dialog[0] != null) dialog[0].dismiss(); activity.finish(); });
        }
    }

    @Test public void diagnosticGuestReceivesGestureKeysAndMenuCancelsPendingInput() throws Exception {
        File log = new File(instrumentation.getTargetContext().getFilesDir(), "rootfs/home/xuser-1/.wine/drive_c/NVDA-Bridge/gesture-input-probe.log");
        java.nio.file.Files.deleteIfExists(log.toPath());
        Intent intent = new Intent(instrumentation.getTargetContext(), XServerDisplayActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("container_id", 1)
                .putExtra("exec_path", "/data/data/com.winlator/files/rootfs/home/xuser-1/.wine/drive_c/NVDA-Bridge/gesture-input-probe.exe");
        XServerDisplayActivity activity = (XServerDisplayActivity)instrumentation.startActivitySync(intent);
        GestureMapStore store = (GestureMapStore)field(activity, "gestureMaps");
        GestureMapStore.Target target = (GestureMapStore.Target)field(activity, "gestureTarget");
        com.winlator.container.Container container = new com.winlator.container.ContainerManager(activity).getContainerById(1);
        assertEquals("Shortcut and private Android paths must share the game map", target.id(),
                GestureSettings.target(container, "C:\\NVDA-Bridge\\gesture-input-probe.exe", null).id());
        GestureMapStore.Mode original = store.getMode();
        Boolean originalSwipeHolds = store.hasSwipeHoldOverride(target) ? store.swipeHolds(target) : null;
        XKeycode originalHold = store.isOverride(target, Gesture.ONE_HOLD) ? store.resolve(target, Gesture.ONE_HOLD) : null;
        GesturePadView pad = (GesturePadView)field(activity, "gesturePad");
        Method update = XServerDisplayActivity.class.getDeclaredMethod("updateGestureMode"); update.setAccessible(true);
        try {
            long deadline = SystemClock.uptimeMillis() + 45000;
            while ((!log.exists() || !read(log).contains("ready")) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200);
            assertTrue("Diagnostic guest must create its visible window", log.exists() && read(log).contains("ready"));
            instrumentation.runOnMainSync(() -> {
                store.setMode(GestureMapStore.Mode.GESTURES);
                try { update.invoke(activity); } catch (Exception e) { throw new RuntimeException(e); }
            });
            idle();
            assertTrue(pad.isAccepting());
            swipe(pad, 100, 100, 100, 220);
            tap(pad, 150, 150);
            swipe(pad, 100, 100, 220, 100);
            swipe(pad, 100, 220, 100, 100);
            swipe(pad, 220, 100, 100, 100);
            SystemClock.sleep(250);
            String received = read(log);
            for (int key : new int[]{40, 13, 39, 38, 37}) {
                assertTrue("Guest down " + key, received.contains("message=100 vk=" + key + " "));
                assertTrue("Guest up " + key, received.contains("message=101 vk=" + key + " "));
            }
            instrumentation.runOnMainSync(() -> store.assign(target, Gesture.ONE_HOLD, XKeycode.KEY_RIGHT));
            touch(pad, MotionEvent.ACTION_DOWN, 150, 150);
            SystemClock.sleep(650);
            String duringHold = read(log);
            int downs = occurrences(duringHold, "message=100 vk=39 ");
            int ups = occurrences(duringHold, "message=101 vk=39 ");
            assertEquals("Long press is still held", ups + 1, downs);
            instrumentation.runOnMainSync(activity::onBackPressed);
            idle();
            assertFalse("Menu must suspend the gameplay surface", pad.isAccepting());
            String afterMenu = read(log);
            assertTrue("Reset may send extra releases, but must leave Right released",
                    afterMenu.lastIndexOf("message=101 vk=39 ") > afterMenu.lastIndexOf("message=100 vk=39 "));
            instrumentation.runOnMainSync(activity::onBackPressed);
            SystemClock.sleep(500); idle();
            assertTrue(pad.isAccepting());
            instrumentation.runOnMainSync(() -> store.setSwipeHolds(target, true));
            touch(pad, MotionEvent.ACTION_DOWN, 100, 100); SystemClock.sleep(70);
            touch(pad, MotionEvent.ACTION_MOVE, 240, 100); SystemClock.sleep(750);
            String swipeHeld = read(log);
            assertTrue("Swipe-and-hold must remain down until lift",
                    swipeHeld.lastIndexOf("message=100 vk=39 ") > swipeHeld.lastIndexOf("message=101 vk=39 "));
            touch(pad, MotionEvent.ACTION_UP, 240, 100); SystemClock.sleep(100);
            String swipeReleased = read(log);
            assertTrue("Finger lift must release swipe hold",
                    swipeReleased.lastIndexOf("message=101 vk=39 ") > swipeReleased.lastIndexOf("message=100 vk=39 "));
            instrumentation.runOnMainSync(() -> store.setSwipeHolds(target, false));
            touch(pad, MotionEvent.ACTION_DOWN, 240, 100); SystemClock.sleep(70);
            touch(pad, MotionEvent.ACTION_MOVE, 100, 100); SystemClock.sleep(200);
            String quick = read(log);
            assertTrue("Quick mode must release while finger is still down",
                    quick.lastIndexOf("message=101 vk=37 ") > quick.lastIndexOf("message=100 vk=37 "));
            touch(pad, MotionEvent.ACTION_UP, 100, 100);
            String state = XServerDisplayActivity.getGestureState();
            assertTrue(state, state.contains("deliveryMeanUs"));
            System.out.println("GESTURE_DEVICE_STATE " + state);
        } finally {
            instrumentation.runOnMainSync(() -> {
                pad.cancel(); store.assign(target, Gesture.ONE_HOLD, originalHold);
                store.setSwipeHolds(target, originalSwipeHolds); store.setMode(original);
                XServerDisplayActivity.requestSessionShutdown();
            });
        }
    }
    private void touch(GesturePadView pad, int action, float x, float y) {
        instrumentation.runOnMainSync(() -> {
            long time = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN); pad.onTouchEvent(event); event.recycle();
        });
    }
    private void tap(GesturePadView pad, float x, float y) {
        touch(pad, MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(20);
        touch(pad, MotionEvent.ACTION_UP, x, y); SystemClock.sleep(70);
    }
    private void swipe(GesturePadView pad, float x, float y, float endX, float endY) {
        touch(pad, MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(70);
        touch(pad, MotionEvent.ACTION_MOVE, endX, endY); SystemClock.sleep(20);
        touch(pad, MotionEvent.ACTION_UP, endX, endY); SystemClock.sleep(70);
    }
    private static String read(File file) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static int occurrences(String text, String needle) {
        int count = 0, at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) { count++; at += needle.length(); }
        return count;
    }
}
