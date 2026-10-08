package com.winlator.gestures;

import com.winlator.xserver.XKeycode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** In-memory resolution: persistence is only accessed on launch or settings changes. */
public final class GestureMapStore {
    public interface Storage { String read(); void write(String value); }
    public enum Mode { AUTOMATIC, GESTURES, TOUCHPAD }

    public static final class Target {
        public final int containerId;
        public final String path, name, containerName;
        public Target(int containerId, String path, String name, String containerName) {
            this.containerId = containerId;
            this.path = normalizePath(path);
            this.name = name;
            this.containerName = containerName;
        }
        public String id() { return containerId + ":" + path; }
        public String label() { return name + " — " + containerName + " (" + containerId + ")"; }
    }

    private static String normalizePath(String path) {
        String normalized = path.replace('\\', '/').replaceAll("/+", "/").toLowerCase(Locale.ROOT);
        // Android user zero exposes the same private directory through both aliases.
        return normalized.startsWith("/data/data/") ? "/data/user/0/" + normalized.substring(11) : normalized;
    }

    private static String normalizeId(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon + 1) + normalizePath(id.substring(colon + 1));
    }

    private final Storage storage;
    private final EnumMap<Gesture, XKeycode> defaults = new EnumMap<>(Gesture.class);
    private final HashMap<String, EnumMap<Gesture, XKeycode>> overrides = new HashMap<>();
    private final ArrayList<Target> recent = new ArrayList<>();
    private Mode mode = Mode.AUTOMATIC;
    private boolean globalSwipeHolds;
    private final HashMap<String, Boolean> gameSwipeHolds = new HashMap<>();

    public GestureMapStore(Storage storage) { this.storage = storage; reload(); }

    public void reload() {
        defaults.clear(); overrides.clear(); recent.clear(); mode = Mode.AUTOMATIC;
        globalSwipeHolds = false; gameSwipeHolds.clear();
        defaults.put(Gesture.ONE_UP, XKeycode.KEY_UP);
        defaults.put(Gesture.ONE_DOWN, XKeycode.KEY_DOWN);
        defaults.put(Gesture.ONE_LEFT, XKeycode.KEY_LEFT);
        defaults.put(Gesture.ONE_RIGHT, XKeycode.KEY_RIGHT);
        defaults.put(Gesture.ONE_TAP, XKeycode.KEY_ENTER);
        try {
            JSONObject root = new JSONObject(storage.read());
            if (root.optInt("version", 1) != 1) return;
            globalSwipeHolds = root.optBoolean("swipeHolds", false);
            JSONObject holdSettings = root.optJSONObject("gameSwipeHolds");
            if (holdSettings != null) {
                // Read aliases first so canonical assignments win on collisions.
                java.util.Iterator<String> ids = holdSettings.keys();
                while (ids.hasNext()) {
                    String id = ids.next();
                    if (holdSettings.opt(id) instanceof Boolean && !id.equals(normalizeId(id)))
                        gameSwipeHolds.put(normalizeId(id), holdSettings.getBoolean(id));
                }
                ids = holdSettings.keys();
                while (ids.hasNext()) {
                    String id = ids.next();
                    if (holdSettings.opt(id) instanceof Boolean && id.equals(normalizeId(id)))
                        gameSwipeHolds.put(id, holdSettings.getBoolean(id));
                }
            }
            try { mode = Mode.valueOf(root.optString("mode", "AUTOMATIC")); } catch (IllegalArgumentException ignored) {}
            readMap(root.optJSONObject("global"), defaults);
            JSONObject games = root.optJSONObject("games");
            if (games != null) {
                for (int pass = 0; pass < 2; pass++) {
                    java.util.Iterator<String> ids = games.keys();
                    while (ids.hasNext()) {
                        String id = ids.next(), normalized = normalizeId(id);
                        if ((pass == 1) != id.equals(normalized)) continue;
                        EnumMap<Gesture, XKeycode> map = overrides.computeIfAbsent(normalized, key -> new EnumMap<>(Gesture.class));
                        readMap(games.optJSONObject(id), map);
                    }
                }
            }
            JSONArray items = root.optJSONArray("recent");
            if (items != null) for (int i = 0; i < items.length() && recent.size() < 10; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || item.optInt("container") <= 0 || item.optString("path").isEmpty()) continue;
                Target target = new Target(item.getInt("container"), item.getString("path"),
                        item.optString("name", item.getString("path")), item.optString("containerName", "Container"));
                boolean duplicate = false;
                for (Target other : recent) if (other.id().equals(target.id())) duplicate = true;
                if (!duplicate) recent.add(target);
            }
        } catch (JSONException ignored) { /* Missing/corrupt settings retain safe defaults. */ }
    }

    private static void readMap(JSONObject json, EnumMap<Gesture, XKeycode> into) {
        if (json == null) return;
        for (Gesture gesture : Gesture.values()) {
            if (!json.has(gesture.name())) continue;
            try {
                XKeycode key = XKeycode.valueOf(json.optString(gesture.name()));
                if (isSelectable(key)) into.put(gesture, key);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public static boolean isSelectable(XKeycode key) { return key != XKeycode.KEY_MAX && !key.isCustomKey(); }
    public Mode getMode() { return mode; }
    public boolean swipeHolds(Target target) {
        return target != null && gameSwipeHolds.containsKey(target.id()) ? gameSwipeHolds.get(target.id()) : globalSwipeHolds;
    }
    public boolean hasSwipeHoldOverride(Target target) { return target != null && gameSwipeHolds.containsKey(target.id()); }
    public void setSwipeHolds(Target target, Boolean value) {
        if (target == null) globalSwipeHolds = Boolean.TRUE.equals(value);
        else if (value == null) gameSwipeHolds.remove(target.id()); else gameSwipeHolds.put(target.id(), value);
        save();
    }
    public void setMode(Mode value) { mode = value; save(); }
    public static boolean gesturesEnabled(Mode mode, boolean physicalKeyboard) {
        return mode == Mode.GESTURES || (mode == Mode.AUTOMATIC && !physicalKeyboard);
    }
    public XKeycode resolve(Target target, Gesture gesture) {
        EnumMap<Gesture, XKeycode> map = target == null ? null : overrides.get(target.id());
        return map != null && map.containsKey(gesture) ? map.get(gesture) : defaults.getOrDefault(gesture, XKeycode.KEY_NONE);
    }
    public boolean isOverride(Target target, Gesture gesture) {
        return target != null && overrides.containsKey(target.id()) && overrides.get(target.id()).containsKey(gesture);
    }
    /** null restores inheritance; KEY_NONE explicitly disables the gesture. */
    public void assign(Target target, Gesture gesture, XKeycode key) {
        if (key != null && !isSelectable(key)) throw new IllegalArgumentException("Unsupported key");
        if (target == null) defaults.put(gesture, key == null ? XKeycode.KEY_NONE : key);
        else {
            EnumMap<Gesture, XKeycode> map = overrides.computeIfAbsent(target.id(), id -> new EnumMap<>(Gesture.class));
            if (key == null) map.remove(gesture); else map.put(gesture, key);
            if (map.isEmpty()) overrides.remove(target.id());
        }
        save();
    }
    public void recordLaunch(Target target) {
        if (target == null || target.path.isEmpty()) return;
        recent.removeIf(item -> item.id().equals(target.id()));
        recent.add(0, target);
        while (recent.size() > 10) recent.remove(recent.size() - 1);
        save();
    }
    public List<Target> targets(Target current) {
        ArrayList<Target> result = new ArrayList<>();
        if (current != null) result.add(current);
        for (Target target : recent) if (current == null || !target.id().equals(current.id())) result.add(target);
        return result;
    }
    private static JSONObject jsonMap(EnumMap<Gesture, XKeycode> map) throws JSONException {
        JSONObject result = new JSONObject();
        for (Gesture gesture : map.keySet()) result.put(gesture.name(), map.get(gesture).name());
        return result;
    }
    private void save() {
        try {
            JSONObject root = new JSONObject().put("version", 1).put("mode", mode.name()).put("global", jsonMap(defaults));
            JSONObject games = new JSONObject();
            for (String id : overrides.keySet()) games.put(id, jsonMap(overrides.get(id)));
            root.put("games", games);
            root.put("swipeHolds", globalSwipeHolds);
            JSONObject holds = new JSONObject();
            for (String id : gameSwipeHolds.keySet()) holds.put(id, gameSwipeHolds.get(id));
            root.put("gameSwipeHolds", holds);
            JSONArray items = new JSONArray();
            for (Target target : recent) items.put(new JSONObject().put("container", target.containerId)
                    .put("path", target.path).put("name", target.name).put("containerName", target.containerName));
            root.put("recent", items);
            storage.write(root.toString());
        } catch (JSONException e) { throw new IllegalStateException("Unable to save gesture maps", e); }
    }
}
