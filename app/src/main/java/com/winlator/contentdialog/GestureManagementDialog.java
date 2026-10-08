package com.winlator.contentdialog;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.winlator.R;
import com.winlator.gestures.Gesture;
import com.winlator.gestures.GestureMapStore;
import com.winlator.xserver.XKeycode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GestureManagementDialog {
    private GestureManagementDialog() {}

    private static TextView label(Context context, LinearLayout layout, int text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setPadding(0, 8, 0, 4);
        layout.addView(label);
        return label;
    }
    private static LinearLayout layout(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(16 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, 0, padding, 0);
        return layout;
    }
    private static void size(AlertDialog dialog, Context context) {
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                (int)(context.getResources().getDisplayMetrics().heightPixels * 0.85f));
        dialog.getWindow().getDecorView().addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = (int)(context.getResources().getDisplayMetrics().heightPixels * 0.85f);
            if (dialog.getWindow().getAttributes().height != height)
                dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, height);
        });
    }

    public static AlertDialog show(Context context, GestureMapStore store, GestureMapStore.Target current,
            Runnable changed, Runnable closed) {
        LinearLayout content = layout(context);
        TextView gameLabel = label(context, content, R.string.gesture_game);
        Spinner game = new Spinner(context);
        game.setId(View.generateViewId()); gameLabel.setLabelFor(game.getId());
        List<GestureMapStore.Target> targets = store.targets(current);
        ArrayList<String> labels = new ArrayList<>();
        labels.add(context.getString(R.string.gesture_global));
        for (GestureMapStore.Target target : targets) labels.add(target.label());
        ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, labels);
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        game.setAdapter(targetAdapter);
        content.addView(game);

        TextView modeLabel = label(context, content, R.string.gesture_control_mode);
        Spinner mode = new Spinner(context);
        mode.setId(View.generateViewId()); modeLabel.setLabelFor(mode.getId());
        ArrayAdapter<CharSequence> modeAdapter = ArrayAdapter.createFromResource(context, R.array.gesture_modes, android.R.layout.simple_spinner_item);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(modeAdapter); mode.setSelection(store.getMode().ordinal());
        content.addView(mode);

        TextView behaviorLabel = label(context, content, R.string.gesture_swipe_behavior);
        Spinner behavior = new Spinner(context);
        behavior.setId(View.generateViewId()); behaviorLabel.setLabelFor(behavior.getId());
        content.addView(behavior);

        AccessibilityManager accessibility = (AccessibilityManager)context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (accessibility != null && accessibility.isTouchExplorationEnabled())
            label(context, content, R.string.gesture_screen_reader_help);
        label(context, content, R.string.gesture_pad_help);
        // Scroll the whole management form: three selectors and reader guidance must not squeeze
        // gesture rows out of a short landscape screen or at large accessibility font sizes.
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows);
        Button[] buttons = new Button[Gesture.values().length];
        for (int i = 0; i < buttons.length; i++) {
            Button button = new Button(context);
            button.setAllCaps(false);
            button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            button.setTag(Gesture.values()[i]);
            buttons[i] = button;
            rows.addView(button, new LinearLayout.LayoutParams(-1, -2));
        }
        boolean[] refreshing = {false};
        Runnable refresh = () -> {
            refreshing[0] = true;
            GestureMapStore.Target target = game.getSelectedItemPosition() > 0 ? targets.get(game.getSelectedItemPosition() - 1) : null;
            ArrayList<String> behaviors = new ArrayList<>();
            if (target != null) behaviors.add(context.getString(R.string.gesture_swipe_inherit,
                    context.getString(store.swipeHolds(null) ? R.string.gesture_swipe_hold : R.string.gesture_swipe_quick)));
            behaviors.add(context.getString(R.string.gesture_swipe_quick));
            behaviors.add(context.getString(R.string.gesture_swipe_hold));
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, behaviors);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            behavior.setAdapter(adapter);
            behavior.setSelection(target == null ? (store.swipeHolds(null) ? 1 : 0) :
                    store.hasSwipeHoldOverride(target) ? (store.swipeHolds(target) ? 2 : 1) : 0);
            for (Gesture gesture : Gesture.values()) {
                String binding = keyLabel(context, store.resolve(target, gesture));
                if (target != null && !store.isOverride(target, gesture)) binding = context.getString(R.string.gesture_inherited_binding, binding);
                buttons[gesture.ordinal()].setText(gestureLabel(context, gesture) + " — " + binding);
            }
            refreshing[0] = false;
        };
        game.setSelection(current == null ? 0 : 1);
        game.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refresh.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                GestureMapStore.Mode selected = GestureMapStore.Mode.values()[position];
                if (selected != store.getMode()) { store.setMode(selected); changed.run(); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        behavior.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (refreshing[0] || position != behavior.getSelectedItemPosition()) return;
                GestureMapStore.Target target = game.getSelectedItemPosition() > 0 ? targets.get(game.getSelectedItemPosition() - 1) : null;
                Boolean value;
                if (target == null) value = position == 1;
                else if (position == 0) value = null;
                else value = position == 2;
                boolean differs = value == null ? store.hasSwipeHoldOverride(target) :
                        (target != null && !store.hasSwipeHoldOverride(target)) || store.swipeHolds(target) != value;
                if (differs) { store.setSwipeHolds(target, value); changed.run(); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        for (Gesture gesture : Gesture.values()) buttons[gesture.ordinal()].setOnClickListener(view -> {
            GestureMapStore.Target target = game.getSelectedItemPosition() > 0 ? targets.get(game.getSelectedItemPosition() - 1) : null;
            showKeys(context, store, target, gesture, () -> {
                refresh.run(); buttons[gesture.ordinal()].requestFocus(); changed.run();
            });
        });
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.gesture_management)
                .setView(scroll).setPositiveButton(R.string.gesture_close, null).create();
        dialog.setOnDismissListener(ignored -> closed.run());
        dialog.show(); size(dialog, context); refresh.run();
        return dialog;
    }

    private static final class KeyChoice {
        final XKeycode key;
        final String label;
        KeyChoice(XKeycode key, String label) { this.key = key; this.label = label; }
        @Override public String toString() { return label; }
    }
    private static void showKeys(Context context, GestureMapStore store, GestureMapStore.Target target,
            Gesture gesture, Runnable changed) {
        LinearLayout content = layout(context);
        TextView searchLabel = label(context, content, R.string.gesture_search_keys);
        EditText search = new EditText(context);
        search.setId(View.generateViewId()); searchLabel.setLabelFor(search.getId());
        search.setSingleLine(true); search.setHint(R.string.gesture_search_keys);
        content.addView(search);
        ListView rows = new ListView(context);
        content.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
        ArrayList<KeyChoice> choices = new ArrayList<>();
        if (target != null) choices.add(new KeyChoice(null, context.getString(R.string.gesture_use_global_binding,
                keyLabel(context, store.resolve(null, gesture)))));
        choices.add(new KeyChoice(XKeycode.KEY_NONE, context.getString(R.string.gesture_unassigned)));
        for (XKeycode key : XKeycode.values()) if (key != XKeycode.KEY_NONE && GestureMapStore.isSelectable(key))
            choices.add(new KeyChoice(key, keyLabel(context, key)));
        ArrayAdapter<KeyChoice> adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, new ArrayList<>(choices));
        rows.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                adapter.clear();
                for (KeyChoice choice : choices) if (choice.label.toLowerCase(Locale.ROOT).contains(query)
                        || (choice.key != null && choice.key.name().toLowerCase(Locale.ROOT).contains(query))) adapter.add(choice);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(gestureLabel(context, gesture))
                .setView(content).setNegativeButton(android.R.string.cancel, null).create();
        rows.setOnItemClickListener((parent, view, position, id) -> {
            store.assign(target, gesture, adapter.getItem(position).key);
            changed.run(); dialog.dismiss();
        });
        dialog.show(); size(dialog, context);
    }

    private static String gestureLabel(Context context, Gesture gesture) {
        int[] actions = {R.string.gesture_swipe_up, R.string.gesture_swipe_down, R.string.gesture_swipe_left,
                R.string.gesture_swipe_right, R.string.gesture_tap, R.string.gesture_double_tap,
                R.string.gesture_triple_tap, R.string.gesture_hold};
        return context.getResources().getQuantityString(R.plurals.gesture_fingers, gesture.fingers, gesture.fingers)
                + ": " + context.getString(actions[gesture.action.ordinal()]);
    }
    public static String keyLabel(Context context, XKeycode key) {
        switch (key) {
            case KEY_NONE: return context.getString(R.string.gesture_unassigned);
            case KEY_ESC: return "Escape";
            case KEY_BKSP: return "Backspace";
            case KEY_DEL: return "Delete";
            case KEY_PRIOR: return "Page Up";
            case KEY_NEXT: return "Page Down";
            case KEY_UP: return "Up arrow";
            case KEY_DOWN: return "Down arrow";
            case KEY_LEFT: return "Left arrow";
            case KEY_RIGHT: return "Right arrow";
            case KEY_CTRL_L: return "Left Control";
            case KEY_CTRL_R: return "Right Control";
            case KEY_SHIFT_L: return "Left Shift";
            case KEY_SHIFT_R: return "Right Shift";
            case KEY_ALT_L: return "Left Alt";
            case KEY_ALT_R: return "Right Alt";
            case KEY_PRTSCN: return "Print Screen";
            default:
                String value = key.name().substring(4).replace("KP_", "NUMPAD ").replace('_', ' ').toLowerCase(Locale.ROOT);
                return Character.toUpperCase(value.charAt(0)) + value.substring(1);
        }
    }
}
