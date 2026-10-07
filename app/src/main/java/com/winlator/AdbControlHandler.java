package com.winlator;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.WineUtils;
import com.winlator.core.ExecutableInputSettings;

final class AdbControlHandler {
    private static final String TAG = "WinlatorAdbControl";

    private AdbControlHandler() {}

    static void handle(Context context, Intent source, boolean allowActivityLaunch) {
        try {
            String action = source.getStringExtra("action");
            int containerId = source.getIntExtra("container_id", 0);
            Container container = new ContainerManager(context).getContainerById(containerId);
            if (container == null) throw new IllegalArgumentException("Unknown container id: " + containerId);

            if ("launch".equals(action)) {
                if (!allowActivityLaunch) throw new IllegalArgumentException("Use AdbControlActivity for program launches.");
                launch(context, source, container);
            }
            else if ("get".equals(action)) {
                String setting = requireExtra(source, "setting");
                if ("shortDpadTaps".equals(setting)) {
                    String executable = requireExtra(source, "executable");
                    String normalized = ExecutableInputSettings.normalizeExecutable(container, executable);
                    Log.i(TAG, "container=" + containerId + " shortDpadTaps[" + normalized + "]=" +
                            ExecutableInputSettings.getShortDpadTaps(container, executable));
                    return;
                }
                if ("bavisoftRawQueue".equals(setting)) {
                    String executable = requireExtra(source, "executable");
                    Log.i(TAG, "container=" + containerId + " bavisoftRawQueue[" + ExecutableInputSettings.normalizeExecutable(container, executable) + "]=" + ExecutableInputSettings.getBavisoftRawQueue(container, executable));
                    return;
                }
                Log.i(TAG, "container=" + containerId + " " + setting + "=" + getSetting(container, setting));
            }
            else if ("set".equals(action)) {
                String setting = requireExtra(source, "setting");
                String value = requireExtra(source, "value");
                if ("shortDpadTaps".equals(setting)) {
                    String executable = requireExtra(source, "executable");
                    boolean enabled;
                    if ("true".equalsIgnoreCase(value) || "1".equals(value) || "on".equalsIgnoreCase(value)) enabled = true;
                    else if ("false".equalsIgnoreCase(value) || "0".equals(value) || "off".equalsIgnoreCase(value)) enabled = false;
                    else throw new IllegalArgumentException("shortDpadTaps value must be true/false, on/off, or 1/0");
                    ExecutableInputSettings.setShortDpadTaps(container, executable, enabled);
                    Log.i(TAG, "Updated container=" + containerId + " shortDpadTaps[" +
                            ExecutableInputSettings.normalizeExecutable(container, executable) + "]=" + enabled);
                    return;
                }
                if ("bavisoftRawQueue".equals(setting)) {
                    String executable = requireExtra(source, "executable");
                    boolean enabled = "true".equalsIgnoreCase(value) || "1".equals(value) || "on".equalsIgnoreCase(value);
                    if (!enabled && !("false".equalsIgnoreCase(value) || "0".equals(value) || "off".equalsIgnoreCase(value))) throw new IllegalArgumentException("bavisoftRawQueue value must be true/false, on/off, or 1/0");
                    ExecutableInputSettings.setBavisoftRawQueue(container, executable, enabled);
                    Log.i(TAG, "Updated container=" + containerId + " bavisoftRawQueue");
                    return;
                }
                setSetting(container, setting, value);
                container.saveData();
                Log.i(TAG, "Updated container=" + containerId + " " + setting);
            }
            else {
                throw new IllegalArgumentException("action must be launch, get, or set");
            }
        }
        catch (Exception e) {
            Log.e(TAG, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    }

    private static void launch(Context context, Intent source, Container container) {
        String path = source.getStringExtra("path");
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        if (path != null && !path.trim().isEmpty()) {
            path = path.trim();
            if (path.matches("(?i)^[a-z]:[\\\\/].*")) {
                path = WineUtils.dosToUnixPath(path, container);
                if (path.isEmpty()) throw new IllegalArgumentException("The DOS path is not mapped in this container.");
            }
            else if (!path.startsWith("/")) {
                throw new IllegalArgumentException("path must be an absolute container path or mapped DOS path");
            }
            intent.putExtra("exec_path", path);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        Log.i(TAG, "Launching container=" + container.id + (path == null ? " desktop" : " path=" + path));
    }

    private static String requireExtra(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value.trim();
    }

    private static String getSetting(Container container, String setting) {
        switch (setting) {
            case "name": return container.getName();
            case "envVars": return container.getEnvVars();
            case "screenSize": return container.getScreenSize();
            case "graphicsDriver": return container.getGraphicsDriver();
            case "dxwrapper": return container.getDXWrapper();
            case "dxwrapperConfig": return container.getDXWrapperConfig();
            case "audioDriver": return container.getAudioDriver();
            case "audioDriverConfig": return container.getAudioDriverConfig();
            case "wincomponents": return container.getWinComponents();
            case "drives": return container.getDrives();
            case "hudMode": return String.valueOf(container.getHUDMode());
            case "startupSelection": return String.valueOf(container.getStartupSelection());
            case "cpuList": return valueOrDefault(container.getCPUList());
            case "cpuListWoW64": return valueOrDefault(container.getCPUListWoW64());
            case "box64Preset": return container.getBox64Preset();
            case "desktopTheme": return container.getDesktopTheme();
            default: throw new IllegalArgumentException("Unsupported container setting: " + setting);
        }
    }

    private static void setSetting(Container container, String setting, String value) {
        switch (setting) {
            case "name": container.setName(value); break;
            case "envVars": container.setEnvVars(value); break;
            case "screenSize": container.setScreenSize(value); break;
            case "graphicsDriver": container.setGraphicsDriver(value); break;
            case "dxwrapper": container.setDXWrapper(value); break;
            case "dxwrapperConfig": container.setDXWrapperConfig(value); break;
            case "audioDriver": container.setAudioDriver(value); break;
            case "audioDriverConfig": container.setAudioDriverConfig(value); break;
            case "wincomponents": container.setWinComponents(value); break;
            case "drives": container.setDrives(value); break;
            case "hudMode": container.setHUDMode(parseByteInRange(setting, value, 0, 2)); break;
            case "startupSelection": container.setStartupSelection(parseByteInRange(setting, value, 0, 2)); break;
            case "cpuList": container.setCPUList("default".equalsIgnoreCase(value) ? "" : value); break;
            case "cpuListWoW64": container.setCPUListWoW64("default".equalsIgnoreCase(value) ? "" : value); break;
            case "box64Preset": container.setBox64Preset(value); break;
            case "desktopTheme": container.setDesktopTheme(value); break;
            default: throw new IllegalArgumentException("Unsupported container setting: " + setting);
        }
    }

    private static byte parseByteInRange(String setting, String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return (byte)parsed;
        }
        catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException(setting + " must be a number from " + min + " to " + max);
    }

    private static String valueOrDefault(String value) {
        return value == null ? "default" : value;
    }
}
