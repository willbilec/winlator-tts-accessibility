package com.winlator.gestures;

import android.content.Context;
import android.content.SharedPreferences;
import com.winlator.container.Container;
import com.winlator.core.ExecutableInputSettings;

public final class GestureSettings {
    private GestureSettings() {}
    public static GestureMapStore open(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("gesture_maps", Context.MODE_PRIVATE);
        return new GestureMapStore(new GestureMapStore.Storage() {
            @Override public String read() { return preferences.getString("maps", "{}"); }
            @Override public void write(String value) { preferences.edit().putString("maps", value).apply(); }
        });
    }
    public static GestureMapStore.Target target(Container container, String executable, String name) {
        if (container == null || executable == null || executable.trim().isEmpty()) return null;
        String path = ExecutableInputSettings.normalizeExecutable(container, executable);
        if (path.isEmpty() || !path.endsWith(".exe")) return null;
        if (name == null || name.isEmpty()) name = path.substring(path.lastIndexOf('/') + 1);
        return new GestureMapStore.Target(container.id, path, name, container.getName());
    }
}
