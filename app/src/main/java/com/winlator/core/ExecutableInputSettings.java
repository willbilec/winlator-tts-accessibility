package com.winlator.core;

import com.winlator.container.Container;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Container-persisted input compatibility settings keyed by executable path. */
public final class ExecutableInputSettings {
    private static final String EXTRA_NAME = "executableInputSettings";
    private static final String SHORT_DPAD_TAPS = "shortDpadTaps";
    private static final String BAVISOFT_RAW_QUEUE = "bavisoftRawQueue";
    private static final String BREED_AUDIO_KEEPALIVE = "breedAudioKeepAlive";

    private ExecutableInputSettings() {}

    public static String normalizeExecutable(Container container, String executable) {
        if (executable == null) return "";
        String path = executable.trim();
        if (path.matches("(?i)^[a-z]:[\\\\/].*")) path = WineUtils.dosToUnixPath(path, container);
        path = path.replace('\\', '/').replaceAll("/+", "/");
        return path.toLowerCase(Locale.ROOT);
    }

    public static boolean getShortDpadTaps(Container container, String executable) {
        String key = normalizeExecutable(container, executable);
        if (key.isEmpty()) return false;
        try {
            JSONObject settings = new JSONObject(container.getExtra(EXTRA_NAME, "{}"));
            JSONObject executableSettings = settings.optJSONObject(key);
            return executableSettings != null && executableSettings.optBoolean(SHORT_DPAD_TAPS, false);
        }
        catch (JSONException e) {
            return false;
        }
    }

    public static void setShortDpadTaps(Container container, String executable, boolean enabled) {
        String key = normalizeExecutable(container, executable);
        if (key.isEmpty()) throw new IllegalArgumentException("An executable path is required");
        try {
            JSONObject settings = new JSONObject(container.getExtra(EXTRA_NAME, "{}"));
            JSONObject executableSettings = settings.optJSONObject(key);
            if (executableSettings == null) executableSettings = new JSONObject();

            if (enabled) executableSettings.put(SHORT_DPAD_TAPS, true);
            else executableSettings.remove(SHORT_DPAD_TAPS);

            if (executableSettings.length() == 0) settings.remove(key);
            else settings.put(key, executableSettings);
            container.putExtra(EXTRA_NAME, settings.length() == 0 ? null : settings.toString());
            container.saveData();
        }
        catch (JSONException e) {
            throw new IllegalArgumentException("Unable to save executable input settings", e);
        }
    }

    public static boolean getBreedAudioKeepAlive(Container container, String executable) {
        if (!BreedMemorialAudioCompatibility.appliesTo(executable)) return false;
        try {
            JSONObject item = new JSONObject(container.getExtra(EXTRA_NAME, "{}")).optJSONObject(normalizeExecutable(container, executable));
            return item == null || item.optBoolean(BREED_AUDIO_KEEPALIVE, true);
        } catch (JSONException exception) { return true; }
    }

    public static void setBreedAudioKeepAlive(Container container, String executable, boolean enabled) {
        String key = normalizeExecutable(container, executable);
        if (!BreedMemorialAudioCompatibility.appliesTo(executable)) return;
        try {
            JSONObject settings = new JSONObject(container.getExtra(EXTRA_NAME, "{}"));
            JSONObject item = settings.optJSONObject(key);
            if (item == null) item = new JSONObject();
            if (enabled) item.remove(BREED_AUDIO_KEEPALIVE); else item.put(BREED_AUDIO_KEEPALIVE, false);
            if (item.length() == 0) settings.remove(key); else settings.put(key, item);
            container.putExtra(EXTRA_NAME, settings.length() == 0 ? null : settings.toString());
            container.saveData();
        } catch (JSONException exception) { throw new IllegalArgumentException("Unable to save Breed audio setting", exception); }
    }

    public static boolean getBavisoftRawQueue(Container container, String executable) {
        String key = normalizeExecutable(container, executable);
        if (key.isEmpty()) return false;
        try {
            JSONObject item = new JSONObject(container.getExtra(EXTRA_NAME, "{}")).optJSONObject(key);
            return item != null && item.optBoolean(BAVISOFT_RAW_QUEUE, BavisoftInputCompatibility.appliesTo(executable));
        } catch (JSONException e) { return BavisoftInputCompatibility.appliesTo(executable); }
    }

    public static void setBavisoftRawQueue(Container container, String executable, boolean enabled) {
        String key = normalizeExecutable(container, executable);
        if (key.isEmpty()) throw new IllegalArgumentException("An executable path is required");
        try {
            JSONObject settings = new JSONObject(container.getExtra(EXTRA_NAME, "{}"));
            JSONObject item = settings.optJSONObject(key);
            if (item == null) item = new JSONObject();
            if (enabled == BavisoftInputCompatibility.appliesTo(executable)) item.remove(BAVISOFT_RAW_QUEUE);
            else item.put(BAVISOFT_RAW_QUEUE, enabled);
            if (item.length() == 0) settings.remove(key); else settings.put(key, item);
            container.putExtra(EXTRA_NAME, settings.length() == 0 ? null : settings.toString());
            container.saveData();
        } catch (JSONException e) { throw new IllegalArgumentException("Unable to save executable input settings", e); }
    }
}
