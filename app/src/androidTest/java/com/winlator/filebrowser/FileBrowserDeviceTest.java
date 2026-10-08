package com.winlator.filebrowser;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.xserver.XKeycode;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Wine browser -> guest request -> configured session -> browser, using a diagnostic, not a game. */
@RunWith(AndroidJUnit4.class)
public class FileBrowserDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private String read(File file) {
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
        catch (Exception ignored) { return ""; }
    }
    private void await(String reason, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 90000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200);
        assertTrue(reason, condition.getAsBoolean());
    }
    private XServerDisplayActivity active() {
        try {
            Field field = XServerDisplayActivity.class.getDeclaredField("activeInstance"); field.setAccessible(true);
            return (XServerDisplayActivity)((WeakReference<?>)field.get(null)).get();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private void press(XServerDisplayActivity activity, XKeycode key) {
        instrumentation.runOnMainSync(() -> activity.getXServer().injectKeyPress(key));
        SystemClock.sleep(50);
        instrumentation.runOnMainSync(() -> activity.getXServer().injectKeyRelease(key));
        SystemClock.sleep(150);
    }
    @Test public void guestLaunchRestoresFolderWithAutoCloseDisabled() throws Exception {
        Container container = new ContainerManager(instrumentation.getTargetContext()).getContainerById(1);
        assertNotNull("Container 1 is required", container);
        File directory = new File(container.getRootDir(), ".wine/drive_c/WinlatorFileBrowser"); directory.mkdirs();
        File position = new File(directory, "position.ini"), probe = new File(directory, "browser-launch-probe.exe");
        File log = new File(directory, "browser.log"), probeLog = new File(directory, "launch-probe.log");
        byte[] originalPosition = position.exists() ? Files.readAllBytes(position.toPath()) : null;
        File desktop = new File(container.getUserDir(), "Desktop");
        java.util.Set<String> originalShortcuts = new java.util.HashSet<>();
        File[] existing = desktop.listFiles();
        if (existing != null) for (File file : existing) originalShortcuts.add(file.getName());
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(instrumentation.getTargetContext());
        boolean originalAutoClose = preferences.getBoolean("auto_close_container", true);
        try {
            preferences.edit().putBoolean("auto_close_container", false).commit();
            Files.write(position.toPath(), "[position]\nfolder=C:\\WinlatorFileBrowser\nselection=browser-launch-probe.exe\n".getBytes(StandardCharsets.UTF_8));
            try (InputStream input = instrumentation.getContext().getAssets().open("file-browser/browser-launch-probe.exe")) {
                Files.copy(input, probe.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.delete(); probeLog.delete();
            Intent intent = new Intent(instrumentation.getTargetContext(), XServerDisplayActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("container_id", 1).putExtra("browser_resume", true);
            XServerDisplayActivity initial = (XServerDisplayActivity)instrumentation.startActivitySync(intent);
            await("Fresh guest browser speaks and selects diagnostic", () -> read(log).contains("browser-launch-probe.exe file selected."));
            assertTrue(read(log).contains("speech status=0"));
            assertTrue(initial.getXServer().windowManager.hasWindowWithClassName("accessible-browser.exe"));
            press(initial, XKeycode.KEY_RIGHT);
            for (int i = 0; i < 8; i++) press(initial, XKeycode.KEY_DOWN);
            press(initial, XKeycode.KEY_ENTER);
            await("Guest action creates the Winlator shortcut", () -> read(log).contains("operation=7 error=0"));
            File created = new File(desktop, "browser-launch-probe.desktop");
            assertTrue(created.isFile());
            assertEquals("C:\\WinlatorFileBrowser\\browser-launch-probe.exe", new com.winlator.container.Shortcut(container, created).path);
            press(initial, XKeycode.KEY_ENTER);
            await("Guest request creates the game session", () -> active() != null && active() != initial &&
                    active().getIntent().getBooleanExtra("return_to_browser", false));
            XServerDisplayActivity diagnostic = active();
            assertTrue(diagnostic.getIntent().getStringExtra("exec_path").endsWith("browser-launch-probe.exe"));
            await("Diagnostic has working fresh guest speech", () -> read(probeLog).contains("ready speech=0"));
            assertTrue(XServerDisplayActivity.getGestureState().contains("browser-launch-probe.exe"));
            assertTrue("Native filesystem and controls also pass inside Wine", read(probeLog).contains("native filesystem/control self-test=0"));
            await("Diagnostic exits normally", () -> read(probeLog).contains("normal exit"));
            await("Normal exit restores browser even with auto close off", () -> active() != null && active() != diagnostic &&
                    active().getIntent().getBooleanExtra("browser_resume", false));
            await("Restored browser speaks its saved selection", () -> {
                XServerDisplayActivity current = active();
                return current != null && current.getXServer() != null &&
                        current.getXServer().windowManager.hasWindowWithClassName("accessible-browser.exe") &&
                        read(log).contains("browser-launch-probe.exe file selected.");
            });
            assertTrue(read(position).contains("folder=C:\\WinlatorFileBrowser"));
            assertTrue(read(position).contains("selection=browser-launch-probe.exe"));
            assertTrue(read(log).contains("speech status=0"));
        } finally {
            instrumentation.runOnMainSync(() -> { XServerDisplayActivity current = active();
                XServerDisplayActivity.requestSessionShutdown(); if (current != null) current.finish(); });
            preferences.edit().putBoolean("auto_close_container", originalAutoClose).commit();
            if (originalPosition == null) position.delete(); else Files.write(position.toPath(), originalPosition);
            probe.delete();
            File[] after = desktop.listFiles();
            if (after != null) for (File file : after)
                if (file.getName().startsWith("browser-launch-probe") && file.getName().endsWith(".desktop") &&
                        !originalShortcuts.contains(file.getName())) file.delete();
            // Remove this temporary diagnostic from the user's recent-game picker.
            SharedPreferences maps = instrumentation.getTargetContext().getSharedPreferences("gesture_maps", 0);
            org.json.JSONObject root = new org.json.JSONObject(maps.getString("maps", "{}"));
            org.json.JSONArray recent = root.optJSONArray("recent"), retained = new org.json.JSONArray();
            if (recent != null) {
                for (int i = 0; i < recent.length(); i++) {
                    org.json.JSONObject item = recent.getJSONObject(i);
                    if (!(item.optInt("container") == 1 && item.optString("path").toLowerCase(java.util.Locale.ROOT)
                            .endsWith("/winlatorfilebrowser/browser-launch-probe.exe"))) retained.put(item);
                }
                root.put("recent", retained); maps.edit().putString("maps", root.toString()).commit();
            }
        }
    }
}
