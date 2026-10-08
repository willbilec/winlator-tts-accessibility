package com.winlator;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ActiveWindowsDialog;
import com.winlator.contentdialog.AudioDriverConfigDialog;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVKConfigDialog;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.contentdialog.ScreenEffectDialog;
import com.winlator.contentdialog.TurnipConfigDialog;
import com.winlator.contentdialog.VKD3DConfigDialog;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contentdialog.WineD3DConfigDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.ExecutableInputSettings;
import com.winlator.core.FileUtils;
import com.winlator.core.AudioGameDependencyManager;
import com.winlator.core.AccessibleFileBrowser;
import com.winlator.winhandler.FileBrowserBridge;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.ProcessHelper;
import com.winlator.core.StringUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.Win32AppWorkarounds;
import com.winlator.core.WineInfo;
import com.winlator.core.WineInstaller;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineStartMenuCreator;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;
import com.winlator.renderer.GLRenderer;
import com.winlator.services.ForegroundService;
import com.winlator.widget.FrameRating;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.MagnifierView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.winhandler.AndroidSpeechBridge;
import com.winlator.winhandler.WinHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.Atom;
import com.winlator.xserver.Property;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.concurrent.Executors;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private static final long QUICK_DPAD_HOLD_THRESHOLD_MS = 200;
    private static final long QUICK_DPAD_PULSE_MS = 5;
    private static WeakReference<XServerDisplayActivity> activeInstance = new WeakReference<>(null);
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private RootFS rootFS;
    private GuestProgramLauncherComponent guestProgramLauncherComponent;
    private FrameRating frameRating;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] dxwrapperConfig;
    private KeyValueSet[] graphicsDriverConfig = {new KeyValueSet(), new KeyValueSet()};
    private KeyValueSet audioDriverConfig;
    private String wincomponents;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private EnvVars overrideEnvVars;
    private ClipboardManager clipboardManager;
    private SharedPreferences preferences;
    private final WinHandler winHandler = new WinHandler(this);
    private float globalCursorSpeed = 1.0f;
    private boolean capturePointerOnExternalMouse = true;
    private InputManager keyboardInputManager;
    private boolean keyboardListenerRegistered;
    private boolean shortDpadTapFilterEnabled;
    private boolean bavisoftRawQueueEnabled;
    private String currentExecutablePath = "";
    private com.winlator.gestures.GestureMapStore gestureMaps;
    private com.winlator.gestures.GestureMapStore.Target gestureTarget;
    private com.winlator.widget.GesturePadView gesturePad;
    private com.winlator.gestures.GestureKeyDispatcher gestureKeys;
    private boolean gestureResumed, gestureMenuVisible, gestureDialogOpen;
    private long gestureRecognizedAt, gestureDeliveryCount, gestureDeliveryNanos, gestureDeliveryMaxNanos;
    private String gestureLastKey = "";
    private final Handler shortDpadTapHandler = new Handler(Looper.getMainLooper());
    private final HashMap<Integer, PendingDpadTap> pendingDpadTaps = new HashMap<>();
    private final HashSet<Integer> filteredHeldDpadKeys = new HashSet<>();
    private final HashSet<Integer> physicalKeyboardDevices = new HashSet<>();

    private static final class PendingDpadTap {
        final KeyEvent downEvent;
        boolean pulseReleased;
        boolean longHoldStarted;
        Runnable dispatchDown;
        Runnable releasePulse;

        PendingDpadTap(KeyEvent downEvent) {
            this.downEvent = downEvent;
        }
    }
    private final InputManager.InputDeviceListener keyboardDeviceListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) {
            if (ExternalController.isPhysicalKeyboard(android.view.InputDevice.getDevice(id))) {
                physicalKeyboardDevices.add(id);
                resetKeyboardState("keyboard connected");
            }
        }
        @Override public void onInputDeviceRemoved(int id) {
            if (physicalKeyboardDevices.remove(id)) resetKeyboardState("keyboard disconnected");
        }
        @Override public void onInputDeviceChanged(int id) {
            boolean wasKeyboard = physicalKeyboardDevices.remove(id);
            boolean isKeyboard = ExternalController.isPhysicalKeyboard(android.view.InputDevice.getDevice(id));
            if (isKeyboard) physicalKeyboardDevices.add(id);
            if (wasKeyboard || isKeyboard) resetKeyboardState("keyboard configuration changed");
        }
    };
    private final com.winlator.winhandler.NativeSpeechControl nativeSpeechControl =
            new com.winlator.winhandler.NativeSpeechControl();
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    private com.winlator.core.GuestSessionLog guestSessionLog;
    private boolean inputDiagnosticsEnabled;
    public int frameRatingWindowId = -1;
    private Win32AppWorkarounds win32AppWorkarounds;
    private String screenEffectProfile;
    private String autoExitWindowClass;
    private FileBrowserBridge fileBrowserBridge;
    private Intent nextBrowserSession;
    private boolean browserTransition;
    private boolean sessionStopped;
    private AndroidSpeechBridge androidSpeechBridge;
    private PreloaderDialog preloaderDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        activeInstance = new WeakReference<>(this);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);
        ForegroundService.startSession(this);

        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useAndroidClipboardOnWine = preferences.getBoolean("use_android_clipboard_on_wine", false);
        clipboardManager = useAndroidClipboardOnWine ? (ClipboardManager)getSystemService(CLIPBOARD_SERVICE) : null;

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
                gestureMenuVisible = slideOffset > 0;
                updateGestureMode();
            }
            @Override public void onDrawerOpened(@NonNull View drawerView) {
                resetKeyboardState("game menu opened");
            }
            @Override public void onDrawerClosed(@NonNull View drawerView) {
                gestureMenuVisible = false;
                updateGestureMode();
                if (!gestureDialogOpen && xServerView != null) xServerView.requestFocus();
            }
        });

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        guestSessionLog = new com.winlator.core.GuestSessionLog(getFilesDir());
        ProcessHelper.addDebugCallback(guestSessionLog);
        inputDiagnosticsEnabled = preferences.getBoolean("enable_wine_debug", false);
        boolean enableLogs = inputDiagnosticsEnabled || preferences.getInt("box64_logs", 0) >= 1;
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.menu_item_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);

        rootFS = RootFS.find(this);

        if (!isGenerateWineprefix()) {
            ContainerManager containerManager = new ContainerManager(this);
            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            containerManager.activateContainer(container);

            boolean wineprefixNeedsUpdate = container.getExtra("wineprefixNeedsUpdate").equals("t");
            if (wineprefixNeedsUpdate) {
                preloaderDialog.show(R.string.updating_system_files);
                WineUtils.updateWineprefix(this, (status) -> {
                    if (status == 0) {
                        container.putExtra("wineprefixNeedsUpdate", null);
                        container.putExtra("wincomponents", null);
                        container.saveData();
                        AppUtils.restartActivity(this);
                    }
                    else finish();
                });
                return;
            }

            win32AppWorkarounds = new Win32AppWorkarounds(this);

            String wineVersion = container.getWineVersion();
            wineInfo = WineInfo.fromIdentifier(this, wineVersion);

            if (wineInfo != WineInfo.MAIN_WINE_INFO) rootFS.setWinePath(wineInfo.path);

            String shortcutPath = getIntent().getStringExtra("shortcut_path");
            if (shortcutPath != null && !shortcutPath.isEmpty()) shortcut = new Shortcut(container, new File(shortcutPath));
            currentExecutablePath = shortcut != null && !shortcut.isLinkPath() ? shortcut.path : getIntent().getStringExtra("exec_path");
            currentExecutablePath = ExecutableInputSettings.normalizeExecutable(container, currentExecutablePath);
            shortDpadTapFilterEnabled = ExecutableInputSettings.getShortDpadTaps(container, currentExecutablePath);
            bavisoftRawQueueEnabled = ExecutableInputSettings.getBavisoftRawQueue(container, currentExecutablePath);

            String graphicsDriver = container.getGraphicsDriver();
            audioDriver = container.getAudioDriver();
            String dxwrapper = container.getDXWrapper();
            wincomponents = container.getWinComponents();
            String dxwrapperConfig = container.getDXWrapperConfig();
            String graphicsDriverConfig = container.getGraphicsDriverConfig();
            audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
            screenInfo = new ScreenInfo(container.getScreenSize());

            if (shortcut != null) {
                graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
                audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
                dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
                wincomponents = shortcut.getExtra("wincomponents", container.getWinComponents());
                dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
                graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
                audioDriverConfig = new KeyValueSet(shortcut.getExtra("audioDriverConfig", container.getAudioDriverConfig()));
                screenInfo = new ScreenInfo(shortcut.getExtra("screenSize", container.getScreenSize()));

                String dinputMapperType = shortcut.getExtra("dinputMapperType");
                if (!dinputMapperType.isEmpty()) winHandler.gamepadHandler.setDInputMapperType(Byte.parseByte(dinputMapperType));

                win32AppWorkarounds.applyStartupWorkarounds(!shortcut.wmClass.isEmpty() ? shortcut.wmClass : shortcut.path);
            }
            else {
                Intent intent = getIntent();
                if (intent.hasExtra("exec_path")) win32AppWorkarounds.applyStartupWorkarounds(FileUtils.getName(intent.getStringExtra("exec_path")));
            }

            this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
            this.graphicsDriverConfig = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
            this.dxwrapper = DXWrappers.parseIdentifier(dxwrapper);
            this.dxwrapperConfig = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
        }

        preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(this, screenInfo);
        xServer.setWinHandler(winHandler);
        if (inputDiagnosticsEnabled) xServer.inputDeviceManager.setKeyTrace(line -> {
            if (guestSessionLog != null) guestSessionLog.call(line);
        });
        xServer.windowManager.setFocusTrace(line -> {
            if (guestSessionLog != null) guestSessionLog.call(line);
        });
        final boolean[] flags = {false, shortcut != null || getIntent().hasExtra("exec_path")};
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onDestroyWindow(Window window) {
                if (!preferences.getBoolean("auto_close_container", true) && !getIntent().getBooleanExtra("return_to_browser", false)) return;
                String destroyedClass = window.getClassName();
                if (autoExitWindowClass == null || !autoExitWindowClass.equalsIgnoreCase(destroyedClass)) return;
                if (guestSessionLog != null) guestSessionLog.call(
                        "X11 target window destroyed class=" + destroyedClass + " id=" + window.id);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    boolean guestProcessAlive = guestProgramLauncherComponent != null &&
                            guestProgramLauncherComponent.hasLiveTrackedProcesses();
                    if ((preferences.getBoolean("auto_close_container", true) || getIntent().getBooleanExtra("return_to_browser", false)) && !sessionStopped && xServer != null &&
                            !guestProcessAlive &&
                            !xServer.windowManager.hasWindowWithClassName(autoExitWindowClass)) {
                        if (guestSessionLog != null) guestSessionLog.call(
                                "X11 target window stayed closed; stopping container session");
                        onGuestProgramEnded(0);
                    }
                    else if (guestProcessAlive && guestSessionLog != null) guestSessionLog.call(
                            "X11 target window closed while a tracked guest process remains; keeping container session");
                }, 2000);
            }

            @Override
            public void onUpdateWindowContent(Window window) {
                if (window.id == frameRatingWindowId) frameRating.update();
            }

            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    flags[0] = true;
                }

                if (flags[1] && window.attributes.isViewable() && window.isDesktopWindow()) {
                    window.attributes.setViewable(false);
                    // Wine can parent installer/game windows under its desktop.
                    // Hiding Explorer must not disable those application's input targets.
                    window.attributes.setEnabled(false);
                    if (guestSessionLog != null) guestSessionLog.call(
                            "X11 hide desktop=" + window.id + " preserving child input");
                }

                if (win32AppWorkarounds != null) win32AppWorkarounds.applyWindowWorkarounds(window);
                changeFrameRatingVisibility(window, true);
            }

            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, false);
            }
        });

        setupUI();

        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
            }
            setupXEnvironment();
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateGestureMode();
        setKeyboardCaptureEnabled(hasFocus);
        if (!hasFocus && xServer != null) {
            clearShortDpadTapState();
            try (com.winlator.xserver.XLock lock = xServer.lock(
                    XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                xServer.keyboard.releasePressedKeys();
            }
        }

        if (hasFocus) {
            if (capturePointerOnExternalMouse) touchpadView.requestPointerCapture();

            if (winHandler != null && clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData primaryClip = clipboardManager.getPrimaryClip();
                if (primaryClip != null && primaryClip.getItemCount() > 0) {
                    winHandler.setClipboardData(primaryClip.getItemAt(0).getText().toString());
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        gestureResumed = true;
        if (gestureMaps != null) gestureMaps.reload();
        setKeyboardCaptureEnabled(true);
        registerKeyboardListener();
        updateGestureMode();
        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
        ForegroundService.onResumeSession(this);
    }

    @Override
    public void onPause() {
        gestureResumed = false;
        updateGestureMode();
        setKeyboardCaptureEnabled(false);
        unregisterKeyboardListener();
        clearShortDpadTapState();
        if (xServer != null) {
            try (com.winlator.xserver.XLock lock = xServer.lock(
                    XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                xServer.keyboard.releasePressedKeys();
            }
        }
        ForegroundService.onPauseSession(this);
        super.onPause();
        if (environment != null && !isInPictureInPictureMode()) {
            environment.onPause();
            xServerView.onPause();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        ForegroundService.setPipMode(isInPictureInPictureMode);
        updateGestureMode();
    }

    @Override
    protected void onDestroy() {
        if (gesturePad != null) gesturePad.cancel();
        clearShortDpadTapState();
        stopSessionComponents();
        if (guestSessionLog != null) {
            ProcessHelper.removeDebugCallback(guestSessionLog);
            guestSessionLog.close();
        }
        if (activeInstance.get() == this) activeInstance.clear();
        ForegroundService.stopSession(this);
        super.onDestroy();
        // The old guest, audio and listeners are gone before creating the next session.
        if (nextBrowserSession != null) startActivity(nextBrowserSession);
    }

    private void setKeyboardCaptureEnabled(boolean enabled) {
        if (Build.VERSION.SDK_INT < 36) return;
        try {
            android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
            // Android 16.1 added this API; reflection keeps the existing
            // compile SDK compatible with Android 16.0 and older devices.
            android.view.WindowManager.LayoutParams.class
                .getMethod("setKeyboardCaptureEnabled", boolean.class)
                .invoke(params, enabled);
            getWindow().setAttributes(params);
        }
        catch (ReflectiveOperationException e) {
            // The method is absent on Android 16.0 devices.
        }
    }

    private void registerKeyboardListener() {
        if (keyboardInputManager == null) keyboardInputManager = (InputManager)getSystemService(INPUT_SERVICE);
        if (keyboardInputManager == null || keyboardListenerRegistered) return;
        physicalKeyboardDevices.clear();
        for (int id : android.view.InputDevice.getDeviceIds()) {
            if (ExternalController.isPhysicalKeyboard(android.view.InputDevice.getDevice(id))) physicalKeyboardDevices.add(id);
        }
        keyboardInputManager.registerInputDeviceListener(keyboardDeviceListener,
                new android.os.Handler(android.os.Looper.getMainLooper()));
        keyboardListenerRegistered = true;
        updateGestureMode();
    }

    private void unregisterKeyboardListener() {
        if (keyboardInputManager != null && keyboardListenerRegistered) {
            keyboardInputManager.unregisterInputDeviceListener(keyboardDeviceListener);
        }
        keyboardListenerRegistered = false;
        physicalKeyboardDevices.clear();
    }

    private void resetKeyboardState(String reason) {
        if (gesturePad != null) gesturePad.cancel();
        updateGestureMode();
        clearShortDpadTapState();
        if (xServer == null || sessionStopped) return;
        try (com.winlator.xserver.XLock lock = xServer.lock(
                XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            xServer.keyboard.reset();
        }
        android.util.Log.i("WinlatorInput", "Keyboard reset: " + reason);
        if (guestSessionLog != null) guestSessionLog.call("Keyboard reset: " + reason);
    }

    private void restartKeyboardInput() {
        resetKeyboardState("menu request");
        unregisterKeyboardListener();
        registerKeyboardListener();
        setKeyboardCaptureEnabled(false);
        if (touchpadView != null) touchpadView.clearFocus();
        setKeyboardCaptureEnabled(getWindow().getDecorView().hasWindowFocus());
        drawerLayout.closeDrawers();
        AppUtils.showToast(this, R.string.keyboard_reset_done);
    }

    public static void requestSessionShutdown() {
        XServerDisplayActivity activity = activeInstance.get();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            activity.stopSessionComponents();
            activity.finishAndRemoveTask();
        });
    }

    private synchronized void stopSessionComponents() {
        if (sessionStopped) return;
        sessionStopped = true;
        if (fileBrowserBridge != null) {
            fileBrowserBridge.close();
            fileBrowserBridge = null;
        }
        if (gesturePad != null) gesturePad.cancel();
        unregisterKeyboardListener();
        nativeSpeechControl.close();
        if (androidSpeechBridge != null) {
            androidSpeechBridge.close();
            androidSpeechBridge = null;
        }
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();
        ProcessHelper.killOwnedNativeProcesses();
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.menu_item_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_reset_keyboard:
                restartKeyboardInput();
                break;
            case R.id.menu_item_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_gesture_management:
                gestureDialogOpen = true;
                if (gesturePad != null) gesturePad.cancel();
                updateGestureMode();
                com.winlator.contentdialog.GestureManagementDialog.show(this, gestureMaps, gestureTarget,
                        () -> { gesturePad.cancel(); updateGestureMode(); }, () -> {
                            gestureDialogOpen = false;
                            updateGestureMode();
                            if (xServerView != null) xServerView.requestFocus();
                        });
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_toggle_fullscreen:
                renderer.toggleFullscreen();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_task_manager:
                (new TaskManagerDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_active_windows:
                (new ActiveWindowsDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_screen_effect:
                (new ScreenEffectDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_pip_mode:
                PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                    .setAspectRatio(screenInfo.aspectRatio())
                    .build();
                enterPictureInPictureMode(pipParams);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.menu_item_exit:
                exit();
                break;
        }
        return true;
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    private void exit() {
        nextBrowserSession = null;
        stopSessionComponents();

        Intent intent = getIntent();
        if (intent.hasExtra("exec_path")) {
            AppUtils.RestartApplicationOptions options = new AppUtils.RestartApplicationOptions();
            options.containerId = container.id;
            options.startPath = FileUtils.getDirname(intent.getStringExtra("exec_path"));
            AppUtils.restartApplication(this, options);
        }
        else AppUtils.restartApplication(this);
        ForegroundService.stopSession(this);
    }

    private void replaceBrowserSession(Intent next) {
        if (sessionStopped || browserTransition || isFinishing()) return;
        browserTransition = true;
        nextBrowserSession = next;
        stopSessionComponents();
        finish();
    }

    private void onGuestProgramEnded(int status) {
        runOnUiThread(() -> {
            if (sessionStopped || browserTransition || isFinishing()) return;
            if (getIntent().getBooleanExtra("return_to_browser", false)) {
                Intent next = new Intent(this, XServerDisplayActivity.class);
                next.putExtra("container_id", container.id);
                next.putExtra("browser_resume", true);
                next.putExtra("browser_launch_status", status);
                replaceBrowserSession(next);
            } else if (isFileBrowserSession() || preferences.getBoolean("auto_close_container", true)) exit();
            else if (guestSessionLog != null) guestSessionLog.call("Guest ended; automatic close disabled");
        });
    }

    private boolean isFileBrowserSession() {
        return !isGenerateWineprefix() && shortcut == null && !getIntent().hasExtra("exec_path");
    }

    private String prepareFileBrowser() {
        try {
            if (fileBrowserBridge == null) fileBrowserBridge = new FileBrowserBridge((operation, path) -> {
                File selected = AccessibleFileBrowser.mappedFile(container, path);
                if ("shortcut".equals(operation)) {
                    String lower = path.toLowerCase(java.util.Locale.ROOT);
                    if (!lower.endsWith(".exe") && !lower.endsWith(".lnk"))
                        throw new java.io.IOException("Choose an executable or Windows shortcut");
                    return new FileBrowserBridge.Reply(0, AccessibleFileBrowser.createShortcut(container, path), null);
                }
                String lower = path.toLowerCase(java.util.Locale.ROOT);
                if (!(lower.endsWith(".exe") || lower.endsWith(".msi") || lower.endsWith(".lnk") ||
                        lower.endsWith(".bat") || lower.endsWith(".cmd")))
                    throw new java.io.IOException("This file is not a program or installer");
                Intent next = new Intent(this, XServerDisplayActivity.class);
                next.putExtra("container_id", container.id);
                next.putExtra("exec_path", selected.getPath());
                next.putExtra("return_to_browser", true);
                return new FileBrowserBridge.Reply(0, "Launch accepted", () ->
                        new Handler(Looper.getMainLooper()).postDelayed(() -> replaceBrowserSession(next), 400));
            });
            AccessibleFileBrowser.provision(this, new File(rootFS.getRootDir().getPath() + RootFS.WINEPREFIX),
                    fileBrowserBridge.configuration() + "favoritesPath=" + WineUtils.unixToDOSPath(
                            new File(container.getUserDir(), "Favorites").getPath(), container) + "\n" +
                            "launchStatus=" + getIntent().getIntExtra("browser_launch_status", 0) + "\n");
            return "/dir " + StringUtils.escapeDOSPath(AccessibleFileBrowser.DIRECTORY) +
                    " \"accessible-browser.exe\"" + (getIntent().getBooleanExtra("browser_resume", false) ? " --resume" : "");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not prepare speaking file browser", e);
        }
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String rfsVersion = String.valueOf(rootFS.getVersion());
        boolean containerDataChanged = false;

        boolean wineprefixWasUpdated = WineUtils.isWineprefixWasUpdated(container);
        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("rfsVersion").equals(rfsVersion) || wineprefixWasUpdated) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("rfsVersion", rfsVersion);
            containerDataChanged = true;
        }

        if (verifyUserRegistry()) containerDataChanged = true;
        if (extractDXWrapperFiles()) containerDataChanged = true;

        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container, true);

        String startupSelection = String.valueOf(container.getStartupSelection());
        if (!startupSelection.equals(container.getExtra("startupSelection")) || wineprefixWasUpdated) {
            WineUtils.changeServicesStatus(container, container.getStartupSelection());
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        boolean openAndroidBrowserFromWine = preferences.getBoolean("open_android_browser_from_wine", true);
        String openAndroidBrowserFromWineStr = openAndroidBrowserFromWine ? "t" : "f";
        if (!openAndroidBrowserFromWineStr.equals(container.getExtra("openAndroidBrowserFromWine")) || wineprefixWasUpdated) {
            WineUtils.changeBrowsersRegistryKey(container, openAndroidBrowserFromWine);
            container.putExtra("openAndroidBrowserFromWine", openAndroidBrowserFromWineStr);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();
    }

    private void setupXEnvironment() {
        String rootPath = rootFS.getRootDir().getPath();
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", rootPath+RootFS.WINEPREFIX);
        envVars.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all,err+all");

        FileUtils.clear(rootFS.getTmpDir());

        guestProgramLauncherComponent = new GuestProgramLauncherComponent();

        if (container != null) {
            if (container.getHUDMode() == FrameRating.Mode.FULL.ordinal()) envVars.put("X11_WND_GPU_INFO", "1");

            String desktopName = shortcut != null || getIntent().hasExtra("exec_path") ? "nogui" : "shell";
            String wineStartCommand;
            try {
                wineStartCommand = getWineStartCommand();
            } catch (IllegalStateException exception) {
                android.util.Log.e("WinlatorFileBrowser", "Browser setup failed", exception);
                runOnUiThread(() -> {
                    preloaderDialog.closeOnUiThread();
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("File browser setup failed")
                            .setMessage(exception.getMessage())
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> exit()).show();
                });
                return;
            }
            // Keep built-in SAPI in Super Liam and native SAPI in its RPC helper.
            boolean directLaunchDiagnostic = wineStartCommand.toLowerCase(java.util.Locale.ROOT)
                    .contains("super liam.exe");
            String guestExecutable = "wine explorer /desktop="+desktopName+","+xServer.screenInfo+
                    " C:\\WinlatorNativeSapi\\payload\\nvda-launch.exe "+
                    (directLaunchDiagnostic ? "--builtin-game-sapi " : "")+
                    wineStartCommand;
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);
            String launchedExecutable = null;
            if (shortcut != null && !shortcut.isLinkPath()) launchedExecutable = FileUtils.getName(shortcut.path);
            else if (getIntent().hasExtra("exec_path")) {
                String path = getIntent().getStringExtra("exec_path");
                if (path != null && !path.toLowerCase(java.util.Locale.ROOT).endsWith(".lnk"))
                    launchedExecutable = FileUtils.getName(path);
            }
            if ((launchedExecutable == null || launchedExecutable.isEmpty()) && shortcut != null &&
                    shortcut.wmClass != null && shortcut.wmClass.toLowerCase(java.util.Locale.ROOT).endsWith(".exe"))
                launchedExecutable = shortcut.wmClass;
            if (launchedExecutable == null || launchedExecutable.isEmpty()) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)\"([^\"]+\\.exe)\"")
                        .matcher(wineStartCommand);
                while (matcher.find()) launchedExecutable = FileUtils.getName(matcher.group(1));
            }
            if (isFileBrowserSession()) launchedExecutable = "accessible-browser.exe";
            autoExitWindowClass = launchedExecutable;
            if (getIntent().getBooleanExtra("return_to_browser", false) && launchedExecutable != null) {
                String lower = launchedExecutable.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".msi")) launchedExecutable = "msiexec.exe";
                else if (lower.endsWith(".bat") || lower.endsWith(".cmd")) launchedExecutable = "cmd.exe";
            }
            guestProgramLauncherComponent.setAutoExitTargetExecutable(launchedExecutable);
            guestProgramLauncherComponent.setMonitorProgramExit(isFileBrowserSession() || getIntent().getBooleanExtra("return_to_browser", false));
            guestSessionLog.call("Guest command: " + guestExecutable);
            if (directLaunchDiagnostic) guestSessionLog.call("Super Liam uses built-in SAPI in the game and the normal prelaunch native NVDA helper.");

            {
                File bridgeDir = new File(rootFS.getRootDir().getPath()+RootFS.WINEPREFIX+
                        "/drive_c/NVDA-Bridge");
                bridgeDir.mkdirs();
                File speechSettings = new File(rootFS.getRootDir().getPath()+RootFS.WINEPREFIX+
                        "/drive_c/WinlatorNativeSapi/speech-settings.ini");
                speechSettings.getParentFile().mkdirs();
                int regularRate = Math.max(-10, Math.min(10, preferences.getInt("sapi_tts_rate", 0)));
                int nvdaRate = Math.max(-10, Math.min(10, preferences.getInt("nvda_tts_rate", 0)));
                FileUtils.writeString(speechSettings, "[speech]\nregularRate="+regularRate+
                        "\nnvdaRate="+nvdaRate+"\n");
                guestProgramLauncherComponent.setPreLaunchGuestExecutable(
                        "wine C:\\WinlatorNativeSapi\\payload\\configure-native-sapi.exe --bootstrap",
                        new File(bridgeDir, "nvda-rpc-ready"));
                guestProgramLauncherComponent.setPreLaunchFailureCallback((message) -> runOnUiThread(() -> {
                    preloaderDialog.closeOnUiThread();
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Speech setup failed")
                            .setMessage(message)
                            .setCancelable(false)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                if (getIntent().getBooleanExtra("return_to_browser", false)) onGuestProgramEnded(1);
                                else exit();
                            })
                            .show();
                }));
            }

            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
            if (directLaunchDiagnostic) {
                StringBuilder overrides = new StringBuilder();
                if (envVars.has("WINEDLLOVERRIDES")) {
                    for (String entry : envVars.get("WINEDLLOVERRIDES").split(";")) {
                        int separator = entry.indexOf('=');
                        if (separator < 0) continue;
                        StringBuilder names = new StringBuilder();
                        for (String name : entry.substring(0, separator).split(",")) {
                            if (name.trim().equalsIgnoreCase("sapi") || name.trim().equalsIgnoreCase("sapi.dll")) continue;
                            if (names.length() > 0) names.append(',');
                            names.append(name);
                        }
                        if (names.length() == 0) continue;
                        if (overrides.length() > 0) overrides.append(';');
                        overrides.append(names).append(entry.substring(separator));
                    }
                }
                // Wine's per-application override selects built-in SAPI for the
                // game. An environment override would also affect the RPC worker.
                envVars.put("WINEDLLOVERRIDES", overrides.toString());
            }
            if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");

            guestProgramLauncherComponent.setBox64Preset(shortcut != null ? shortcut.getExtra("box64Preset", container.getBox64Preset()) : container.getBox64Preset());
        }

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals(AudioDrivers.ALSA)) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath+UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", ALSAClient.USE_SHARED_MEMORY ? "true" : "false");

            ALSAClient.Options options = ALSAClient.Options.fromKeyValueSet(audioDriverConfig);
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.ALSA_SERVER_PATH), options));
        }
        else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath+UnixSocketConfig.PULSE_SERVER_PATH);

            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", AudioDriverConfigDialog.DEFAULT_PERFORMANCE_MODE));
            }
            else envVars.put("PULSE_LATENCY_MSEC", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS);
            environment.addComponent(pulseAudioComponent);
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
            VortekRendererComponent vortekRendererComponent = new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options);
            environment.addComponent(vortekRendererComponent);
        }
        if (graphicsDriver[1].equals(GraphicsDrivers.VIRGL)) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
        }

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            onGuestProgramEnded(status);
        });
        environment.addComponent(guestProgramLauncherComponent);

        if (isGenerateWineprefix()) {
            wineInfo = getIntent().getParcelableExtra("wine_info");
            if (wineInfo != null) WineInstaller.generateWineprefix(wineInfo, environment);
        }
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars = null;
        }
        environment.startEnvironmentComponents();

        winHandler.start();
        envVars.clear();
        graphicsDriver = null;
        dxwrapperConfig = null;
        graphicsDriverConfig = null;
        audioDriver = null;
        audioDriverConfig = null;
        wincomponents = null;
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));
        renderer.setForceWindowsFullscreen(shortcut != null && shortcut.getExtra("forceFullscreen", "0").equals("1"));

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);
        // Keep Android's keyboard focus on the guest display so Tab is
        // dispatched to Wine instead of traversing Android overlay views.
        xServerView.setFocusable(true);
        xServerView.setFocusableInTouchMode(true);
        xServerView.setOnKeyListener((view, keyCode, event) -> xServer.keyboard.onKeyEvent(event));
        xServerView.requestFocus();

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView = new TouchpadView(this, xServer, capturePointerOnExternalMouse);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        setupGesturePad();

        if (container != null && container.getHUDMode() != FrameRating.Mode.DISABLED.ordinal()) {
            frameRating = new FrameRating(this);
            frameRating.setMode(FrameRating.Mode.values()[container.getHUDMode()]);
            frameRating.setVisibility(View.GONE);
            rootView.addView(frameRating);
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
    }

    private void setupGesturePad() {
        gestureMaps = com.winlator.gestures.GestureSettings.open(this);
        gestureTarget = com.winlator.gestures.GestureSettings.target(container, currentExecutablePath,
                shortcut == null ? null : shortcut.name);
        gestureMaps.recordLaunch(gestureTarget);
        com.winlator.gestures.GestureRecognizer.Scheduler scheduler = com.winlator.widget.GesturePadView.scheduler();
        gestureKeys = new com.winlator.gestures.GestureKeyDispatcher(scheduler,
                new com.winlator.gestures.GestureKeyDispatcher.Sink() {
                    @Override public void down(com.winlator.xserver.XKeycode key) {
                        if (sessionStopped) return;
                        if ((key == com.winlator.xserver.XKeycode.KEY_CTRL_L || key == com.winlator.xserver.XKeycode.KEY_CTRL_R)
                                && preferences.getBoolean("nvda_control_interrupt", true)) nativeSpeechControl.cancel();
                        try (com.winlator.xserver.XLock lock = xServer.lock(
                                XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            xServer.keyboard.setGestureKeyPress(key.id);
                        }
                        long elapsed = SystemClock.elapsedRealtimeNanos() - gestureRecognizedAt;
                        gestureDeliveryCount++;
                        gestureDeliveryNanos += elapsed;
                        gestureDeliveryMaxNanos = Math.max(gestureDeliveryMaxNanos, elapsed);
                        gestureLastKey = key.name();
                    }
                    @Override public void up(com.winlator.xserver.XKeycode key) {
                        try (com.winlator.xserver.XLock lock = xServer.lock(
                                XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                            xServer.keyboard.setGestureKeyRelease(key.id);
                        }
                    }
                });
        gesturePad = new com.winlator.widget.GesturePadView(this, scheduler,
                new com.winlator.gestures.GestureRecognizer.Listener() {
                    @Override public boolean mapped(com.winlator.gestures.Gesture gesture) {
                        return gestureMaps.resolve(gestureTarget, gesture) != com.winlator.xserver.XKeycode.KEY_NONE;
                    }
                    @Override public boolean holdSwipes() { return gestureMaps.swipeHolds(gestureTarget); }
                    @Override public void press(com.winlator.gestures.Gesture gesture, boolean hold) {
                        gestureRecognizedAt = SystemClock.elapsedRealtimeNanos();
                        gestureKeys.press(gestureMaps.resolve(gestureTarget, gesture), hold);
                    }
                    @Override public void releaseHold() { gestureKeys.releaseHold(); }
                    @Override public void openMenu() { drawerLayout.openDrawer(GravityCompat.START); }
                }, gestureKeys::cancel);
        ((FrameLayout)findViewById(R.id.FLXServerDisplay)).addView(gesturePad,
                new FrameLayout.LayoutParams(-1, -1));
        updateGestureMode();
    }

    private void updateGestureMode() {
        if (gesturePad == null) return;
        boolean gestures = !isGenerateWineprefix() && com.winlator.gestures.GestureMapStore.gesturesEnabled(
                gestureMaps.getMode(), !physicalKeyboardDevices.isEmpty());
        boolean accepting = gestures && gestureResumed && !sessionStopped && !gestureMenuVisible && !gestureDialogOpen
                && getWindow().getDecorView().hasWindowFocus() && !isInPictureInPictureMode();
        gesturePad.setAccepting(accepting);
        gesturePad.setVisibility(gestures ? View.VISIBLE : View.GONE);
        int orientation = gestures ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        if (getRequestedOrientation() != orientation) setRequestedOrientation(orientation);
    }

    /** Read-only ADB diagnostics; timing excludes recognition waits and the guest game's processing. */
    public static String getGestureState() {
        XServerDisplayActivity activity = activeInstance.get();
        if (activity == null || activity.gesturePad == null || activity.sessionStopped) return "no active gesture session";
        try {
            org.json.JSONObject result = new org.json.JSONObject();
            result.put("mode", activity.gestureMaps.getMode().name());
            result.put("swipeHolds", activity.gestureMaps.swipeHolds(activity.gestureTarget));
            result.put("physicalKeyboards", activity.physicalKeyboardDevices.size());
            result.put("accepting", activity.gesturePad.isAccepting());
            result.put("target", activity.gestureTarget == null ? "global" : activity.gestureTarget.id());
            result.put("width", activity.gesturePad.getWidth()).put("height", activity.gesturePad.getHeight());
            result.put("delivered", activity.gestureDeliveryCount).put("lastKey", activity.gestureLastKey);
            result.put("deliveryMeanUs", activity.gestureDeliveryCount == 0 ? 0 : activity.gestureDeliveryNanos / activity.gestureDeliveryCount / 1000.0);
            result.put("deliveryMaxUs", activity.gestureDeliveryMaxNanos / 1000.0);
            android.view.accessibility.AccessibilityManager manager = (android.view.accessibility.AccessibilityManager)
                    activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
            result.put("touchExploration", manager != null && manager.isTouchExplorationEnabled());
            org.json.JSONObject map = new org.json.JSONObject();
            for (com.winlator.gestures.Gesture gesture : com.winlator.gestures.Gesture.values())
                map.put(gesture.name(), activity.gestureMaps.resolve(activity.gestureTarget, gesture).name());
            return result.put("map", map).toString();
        } catch (org.json.JSONException e) { return e.getMessage(); }
    }

    @Override public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        if (gesturePad != null) gesturePad.cancel();
        updateGestureMode();
    }

    public static void refreshGestureSettings() {
        XServerDisplayActivity activity = activeInstance.get();
        if (activity == null || activity.gestureMaps == null || activity.sessionStopped) return;
        activity.runOnUiThread(() -> {
            activity.gesturePad.cancel(); activity.gestureMaps.reload(); activity.updateGestureMode();
        });
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile()) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        final CheckBox cbShortDpadTaps = dialog.findViewById(R.id.CBShortDpadTaps);
        cbShortDpadTaps.setChecked(shortDpadTapFilterEnabled);
        cbShortDpadTaps.setEnabled(!currentExecutablePath.isEmpty());
        android.widget.TextView executableScope = dialog.findViewById(R.id.TVExecutableScope);
        executableScope.setText(currentExecutablePath.isEmpty() ? getString(R.string.input_controls_executable_unknown) :
                getString(R.string.input_controls_executable_scope, currentExecutablePath));
        final CheckBox cbBavisoftRawQueue = dialog.findViewById(R.id.CBBavisoftRawQueue);
        final CheckBox cbBreedAudioKeepAlive = dialog.findViewById(R.id.CBBreedAudioKeepAlive);
        cbBreedAudioKeepAlive.setChecked(ExecutableInputSettings.getBreedAudioKeepAlive(container, currentExecutablePath));
        cbBreedAudioKeepAlive.setEnabled(com.winlator.core.BreedMemorialAudioCompatibility.appliesTo(currentExecutablePath));
        cbBavisoftRawQueue.setChecked(bavisoftRawQueueEnabled);
        cbBavisoftRawQueue.setEnabled(!currentExecutablePath.isEmpty() && com.winlator.core.BavisoftInputCompatibility.appliesTo(currentExecutablePath));

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            if (!currentExecutablePath.isEmpty()) {
                shortDpadTapFilterEnabled = cbShortDpadTaps.isChecked();
                ExecutableInputSettings.setShortDpadTaps(container, currentExecutablePath, shortDpadTapFilterEnabled);
                ExecutableInputSettings.setBreedAudioKeepAlive(container, currentExecutablePath, cbBreedAudioKeepAlive.isChecked());
                bavisoftRawQueueEnabled = cbBavisoftRawQueue.isChecked();
                ExecutableInputSettings.setBavisoftRawQueue(container, currentExecutablePath, bavisoftRawQueueEnabled);
                clearShortDpadTapState();
            }
            xServer.setRelativeMouseMovement(cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
        });

        dialog.show();
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        GLRenderer renderer = xServerView.getRenderer();
        if (profile.isDisableMouseInput()) {
            renderer.setCursorVisible(false);
            touchpadView.setEnabled(false);
        }
        else {
            renderer.setCursorVisible(true);
            touchpadView.setEnabled(true);
        }

        inputControlsView.invalidate();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        if (!touchpadView.isEnabled()) {
            touchpadView.setEnabled(true);
            xServerView.getRenderer().setCursorVisible(true);
        }

        inputControlsView.invalidate();
    }

    private void extractGraphicsDriverFiles() {
        envVars.put("vblank_mode", "0");

        String cacheId = "";
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            cacheId += graphicsDriver[0]+"-"+graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
        }
        else cacheId += graphicsDriver[0]+"-"+DefaultVersion.valueOf(graphicsDriver[0]);
        cacheId += "-"+graphicsDriver[1]+"-"+DefaultVersion.valueOf(graphicsDriver[1]);

        String currentGraphicsDriver = preferences.getString("current_graphics_driver", "");
        boolean changed = !cacheId.equals(currentGraphicsDriver);
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "libGL.so.1.7.0"));

            File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            preferences.edit().putString("current_graphics_driver", cacheId).apply();
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            TurnipConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);

            if (changed) {
                String version = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
                GeneralComponents.extractFile(GeneralComponents.Type.TURNIP, this, version, DefaultVersion.TURNIP);
            }
        }
        else if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK) && (changed || MainActivity.DEBUG_MODE)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-" + DefaultVersion.VORTEK + ".tzst", rootDir);
        }

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.ZINK:
                envVars.put("GALLIUM_DRIVER", "zink");
                envVars.put("ZINK_CONTEXT_THREADED", "1");
                if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-"+DefaultVersion.ZINK+".tzst", rootDir);
                break;
            case GraphicsDrivers.VIRGL:
                envVars.put("GALLIUM_DRIVER", "virpipe");
                envVars.put("VIRGL_NO_READBACK", "true");
                envVars.put("VIRGL_SERVER_PATH", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                VirGLConfigDialog.setEnvVars(graphicsDriverConfig[1], envVars);

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-"+DefaultVersion.VIRGL+".tzst", rootDir);
                break;
            case GraphicsDrivers.GLADIO:
                envVars.put("GLADIO_NO_ERROR", "1");

                if (changed || MainActivity.DEBUG_MODE) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/gladio-"+DefaultVersion.GLADIO+".tzst", rootDir);
                break;
        }
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return !winHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 &&
                (event.getKeyCode() == KeyEvent.KEYCODE_CTRL_LEFT ||
                 event.getKeyCode() == KeyEvent.KEYCODE_CTRL_RIGHT) &&
                preferences.getBoolean("nvda_control_interrupt", true)) {
            nativeSpeechControl.cancel();
        }
        // Hardware keyboards are usable without an input-controls profile,
        // even when the device also advertises gamepad buttons.
        boolean physical = ExternalController.isPhysicalKeyboard(event.getDevice());
        if (physical && physicalKeyboardDevices.add(event.getDeviceId())) updateGestureMode();
        boolean handled;
        if (physical && shortDpadTapFilterEnabled && isDpadArrowKey(event.getKeyCode())) {
            handled = handleShortDpadTap(event);
        }
        else if (physical && xServer.keyboard.onKeyEvent(event)) handled = true;
        else handled = (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
                (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
        if (inputDiagnosticsEnabled && (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP)) {
            com.winlator.xserver.Window focus = xServer.windowManager.getFocusedWindow();
            android.util.Log.d("WinlatorInput", "key=" + event.getKeyCode() + " action=" + event.getAction() +
                    " device=" + event.getDeviceId() + " repeat=" + event.getRepeatCount() +
                    " physical=" + physical + " handled=" + handled +
                    " guestFocus=" + (focus == null ? "none" : focus.id));
        }
        return handled;
    }

    private boolean handleShortDpadTap(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) {
                if (pendingDpadTaps.containsKey(keyCode)) return true;
                return filteredHeldDpadKeys.contains(keyCode) && xServer.keyboard.onKeyEvent(event);
            }
            if (pendingDpadTaps.containsKey(keyCode) || filteredHeldDpadKeys.contains(keyCode)) return true;

            PendingDpadTap pending = new PendingDpadTap(event);
            pending.dispatchDown = () -> {
                if (pendingDpadTaps.get(keyCode) != pending || !pending.pulseReleased || xServer == null) return;
                pending.longHoldStarted = true;
                filteredHeldDpadKeys.add(keyCode);
                xServer.keyboard.onKeyEvent(createFilteredDpadEvent(pending.downEvent, KeyEvent.ACTION_DOWN,
                        pending.downEvent.getDownTime(), SystemClock.uptimeMillis()));
            };
            xServer.keyboard.onKeyEvent(event);
            pending.releasePulse = () -> {
                if (pendingDpadTaps.get(keyCode) != pending || pending.longHoldStarted || xServer == null) return;
                xServer.keyboard.onKeyEvent(createFilteredDpadEvent(pending.downEvent, KeyEvent.ACTION_UP,
                        pending.downEvent.getDownTime(), SystemClock.uptimeMillis()));
                pending.pulseReleased = true;
            };
            pendingDpadTaps.put(keyCode, pending);
            shortDpadTapHandler.postDelayed(pending.releasePulse, QUICK_DPAD_PULSE_MS);
            shortDpadTapHandler.postDelayed(pending.dispatchDown, QUICK_DPAD_HOLD_THRESHOLD_MS);
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            PendingDpadTap pending = pendingDpadTaps.remove(keyCode);
            if (pending != null) {
                shortDpadTapHandler.removeCallbacks(pending.dispatchDown);
                shortDpadTapHandler.removeCallbacks(pending.releasePulse);
                long heldMs = event.getEventTime() - pending.downEvent.getEventTime();
                if (pending.longHoldStarted) {
                    filteredHeldDpadKeys.remove(keyCode);
                    return xServer.keyboard.onKeyEvent(event);
                }
                android.util.Log.d("WinlatorInput", "Short-DPAD pulse key=" + keyCode + " heldMs=" + heldMs +
                        " pulseMs=" + QUICK_DPAD_PULSE_MS);
                if (guestSessionLog != null) guestSessionLog.call("Short-DPAD pulse key=" + keyCode +
                        " heldMs=" + heldMs + " pulseMs=" + QUICK_DPAD_PULSE_MS);
                if (!pending.pulseReleased) xServer.keyboard.onKeyEvent(event);
                return true;
            }
            filteredHeldDpadKeys.remove(keyCode);
            return xServer.keyboard.onKeyEvent(event);
        }

        return xServer.keyboard.onKeyEvent(event);
    }

    private static KeyEvent createFilteredDpadEvent(KeyEvent source, int action, long downTime, long eventTime) {
        return new KeyEvent(downTime, eventTime, action, source.getKeyCode(), 0, source.getMetaState(),
                source.getDeviceId(), source.getScanCode(), 0, source.getSource());
    }

    private void clearShortDpadTapState() {
        shortDpadTapHandler.removeCallbacksAndMessages(null);
        pendingDpadTaps.clear();
        filteredHeldDpadKeys.clear();
    }

    private static boolean isDpadArrowKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private boolean extractDXWrapperFiles() {
        String cacheId = "";
        if (dxwrapper.equals(DXWrappers.DXVK)) {
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.DXVK(graphicsDriver[0]));
        }
        else if (dxwrapper.equals(DXWrappers.WINED3D)) {
            WineD3DConfigDialog.setEnvVars(dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
        }

        String ddrawWrapper = dxwrapperConfig[0].get("ddrawWrapper", DXWrappers.WINED3D);
        cacheId += "-"+DXWrappers.VKD3D+"-"+dxwrapperConfig[1].get("version", DefaultVersion.VKD3D)+"-"+ddrawWrapper;
        boolean changed = !cacheId.equals(container.getExtra("dxwrapper"));
        VKD3DConfigDialog.setEnvVars(dxwrapperConfig[1], envVars);

        if (ddrawWrapper.equals(DXWrappers.CNC_DDRAW)) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini");

        if (!changed) return false;
        container.putExtra("dxwrapper", cacheId);

        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");

        if (dxwrapper.equals(DXWrappers.WINED3D)) {
            String version = dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
            if (version.equals(WineInfo.MAIN_WINE_VERSION)) {
                final String[] dlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
                restoreBuiltinDllFiles(dlls);
            }
            else GeneralComponents.extractFile(GeneralComponents.Type.WINED3D, this, version, DefaultVersion.WINED3D);
        }
        else if (dxwrapper.equals(DXWrappers.DXVK)) {
            final boolean[] hasD3D8DllFile = {false};
            final boolean[] hasD3D10DllFile = {false};

            GeneralComponents.extractFile(GeneralComponents.Type.DXVK, this, dxwrapperConfig[0].get("version"), DefaultVersion.DXVK(graphicsDriver[0]), (destination, size) -> {
                String name = destination.getName();
                if (name.equals("d3d10.dll")) {
                    hasD3D10DllFile[0] = true;
                }
                else if (name.equals("d3d8.dll")) {
                    hasD3D8DllFile[0] = true;
                }
                return destination;
            });

            if (!hasD3D8DllFile[0]) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-"+DefaultVersion.D8VK+".tzst", windowsDir);
            }
            if (!hasD3D10DllFile[0]) restoreBuiltinDllFiles("d3d10.dll", "d3d10_1.dll");
        }

        GeneralComponents.extractFile(GeneralComponents.Type.VKD3D, this, dxwrapperConfig[1].get("version"), DefaultVersion.VKD3D);

        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");
        FileUtils.delete(new File(containerSysWoW64Dir, "ddraw_.dll"));

        switch (ddrawWrapper) {
            case DXWrappers.CNC_DDRAW:
                final String assetDir = "dxwrapper/cnc-ddraw-"+DefaultVersion.CNC_DDRAW;
                File configFile = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/ddraw.ini");
                if (!configFile.isFile()) FileUtils.copy(this, assetDir+"/ddraw.ini", configFile);
                File shadersDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/Shaders");
                FileUtils.delete(shadersDir);
                FileUtils.copy(this, assetDir+"/Shaders", shadersDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetDir+"/ddraw.tzst", windowsDir);
                break;
            case DXWrappers.D7VK:
                restoreBuiltinDllFiles("ddraw.dll");
                (new File(containerSysWoW64Dir, "ddraw.dll")).renameTo(new File(containerSysWoW64Dir, "ddraw_.dll"));
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d7vk-"+DefaultVersion.D7VK+".tzst", windowsDir);
                break;
            default:
                restoreBuiltinDllFiles("ddraw.dll");
                break;
        }
        return true;
    }

    private void extractWinComponentFiles() {
        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();
            ArrayList<String> builtinDlls = new ArrayList<>();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir);
                }
                else {
                    JSONObject wincomponentJSONObject = wincomponentsJSONObject.getJSONObject(identifier);
                    if (wincomponentJSONObject.getBoolean("restoreBuiltinDlls")) {
                        JSONArray dlnames = wincomponentJSONObject.getJSONArray("dlnames");
                        for (int i = 0; i < dlnames.length(); i++) {
                            String dlname = dlnames.getString(i);
                            builtinDlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                        }
                    }
                    else {
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, (destination, size) -> {
                            String name = destination.getName();
                            if (name.endsWith(".dll") || name.endsWith(".manifest") || name.endsWith("_deadbeef")) FileUtils.delete(destination);
                            return null;
                        });
                    }
                }

                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative);
            }

            if (!builtinDlls.isEmpty()) restoreBuiltinDllFiles(builtinDlls.toArray(new String[0]));
            WineUtils.overrideWinComponentDlls(this, container, wincomponents);
        }
        catch (JSONException e) {}
    }

    private void restoreBuiltinDllFiles(final String... dlls) {
        File rootDir = rootFS.getRootDir();
        File wineDir = new File(rootDir, rootFS.getWinePath());
        File wineSystem32Dir = new File(wineDir, "/lib/wine/x86_64-windows");
        File wineSysWoW64Dir = new File(wineDir, "/lib/wine/i386-windows");
        File containerSystem32Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/system32");
        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");;

        for (String dll : dlls) {
            FileUtils.copy(new File(wineSysWoW64Dir, dll), new File(containerSysWoW64Dir, dll));
            FileUtils.copy(new File(wineSystem32Dir, dll), new File(containerSystem32Dir, dll));
        }
    }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    private String getWineStartCommand() {
        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";

        if (shortcut != null) {
            execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            if (shortcut.isLinkPath()) {
                cmdArgs = "\""+shortcut.path+"\""+execArgs;
            }
            else execPath = shortcut.path;
        }
        else {
            Intent intent = getIntent();
            if (intent.hasExtra("exec_path")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);

                if (execPath.endsWith(".lnk")) {
                    cmdArgs = "\""+execPath+"\"";
                    execPath = null;
                }
            }
        }

        if (execPath != null) {
            String execDir = FileUtils.getDirname(execPath);
            String filename = FileUtils.getName(execPath);
            int dotIndex, spaceIndex;
            if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                execArgs = filename.substring(spaceIndex+1)+execArgs;
                filename = filename.substring(0, spaceIndex);
            }
            cmdArgs = "/dir "+StringUtils.escapeDOSPath(execDir)+" \""+filename+"\""+execArgs;
            AudioGameDependencyManager.GameDefinition dependencyGame = AudioGameDependencyManager.findByExecutable(filename);
            if (dependencyGame != null && (dependencyGame.id.equals("breed-memorial") ||
                    !AudioGameDependencyManager.isInstalled(container, dependencyGame.id))) {
                try {
                    AudioGameDependencyManager.install(this, container, dependencyGame.id);
                    if (guestSessionLog != null) guestSessionLog.call("Automatic dependencies installed: " + dependencyGame.name + " executable=" + filename);
                } catch (java.io.IOException exception) {
                    android.util.Log.e("AudioGameDependencies", "Could not provision " + dependencyGame.name, exception);
                    if (guestSessionLog != null) guestSessionLog.call("Automatic dependencies failed: " + dependencyGame.name + ": " + exception.getMessage());
                    runOnUiThread(() -> AppUtils.showToast(this, getString(R.string.audio_game_install_failed, exception.getMessage())));
                }
            }
            if (com.winlator.core.BreedMemorialAudioCompatibility.appliesTo(filename)) {
                try {
                    File gameExecutable = new File(WineUtils.dosToUnixPath(execPath, container));
                    boolean updater = com.winlator.core.BreedMemorialUpdaterCompatibility.provision(this, gameExecutable);
                    boolean enabled = ExecutableInputSettings.getBreedAudioKeepAlive(container, execPath);
                    boolean applied = com.winlator.core.BreedMemorialAudioCompatibility.provision(this, gameExecutable, enabled);
                    if (guestSessionLog != null) guestSessionLog.call("Breed Memorial dependencies installed; updater guard=" + updater + "; audio enabled=" + enabled + " applied=" + applied);
                } catch (java.io.IOException exception) {
                    android.util.Log.e("BreedMemorialDependencies", "Could not provision game dependencies", exception);
                    if (guestSessionLog != null) guestSessionLog.call("Breed Memorial provisioning failed: " + exception.getMessage());
                    runOnUiThread(() -> AppUtils.showToast(this, getString(R.string.audio_game_install_failed, exception.getMessage())));
                }
            }
            if (com.winlator.core.BavisoftInputCompatibility.appliesTo(filename) &&
                    com.winlator.core.BavisoftInputCompatibility.provision(this,
                            new File(rootFS.getRootDir().getPath()+RootFS.WINEPREFIX))) {
                cmdArgs = com.winlator.core.BavisoftInputCompatibility.command(
                        execDir+"\\"+filename, execArgs,
                        ExecutableInputSettings.getBavisoftRawQueue(container, execDir+"\\"+filename));
                if (guestSessionLog != null)
                    guestSessionLog.call("Bavisoft DirectInput 8 keyboard candidate enabled: "+filename);
            }
        }

        if (cmdArgs.isEmpty()) cmdArgs = prepareFileBrowser();

        if (overrideEnvVars != null && overrideEnvVars.has("EXTRA_EXEC_ARGS")) {
            cmdArgs += " "+overrideEnvVars.get("EXTRA_EXEC_ARGS");
            overrideEnvVars.remove("EXTRA_EXEC_ARGS");
        }
        return "C:\\windows\\winhandler.exe "+cmdArgs;
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) overrideEnvVars = new EnvVars();
        return overrideEnvVars;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public void setScreenInfo(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public DebugDialog getDebugDialog() {
        return debugDialog;
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = rootFS.getRootDir();
            File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals(AudioDrivers.ALSA)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = rootFS.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "rootfs_patches.tzst", rootDir);
        File nativeSapiDir = new File(rootDir.getPath()+RootFS.WINEPREFIX+"/drive_c/WinlatorNativeSapi");
        nativeSapiDir.mkdirs();
        // The archive contains no backup directory; extracting updates preserves rollback data.
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                "winlator-native-sapi-candidate.tzst", nativeSapiDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("dxwrapper", null);
        container.putExtra("desktopTheme", null);
        SettingsFragment.resetPreferenceVersions(this);
    }

    public void changeFrameRatingVisibility(Window window, boolean visible) {
        if (frameRating == null) return;
        if (visible) {
            if (window.id == frameRatingWindowId) return;
            Window child = window.getChildAt(0);
            boolean viewable = window.attributes.isMapped() && window.getWidth() >= ScreenInfo.MIN_WIDTH && window.getHeight() >= ScreenInfo.MIN_HEIGHT;
            Window frameRatingWindow = null;
            if (viewable && (window.isSurface() || (child != null && child.isSurface()))) {
                frameRatingWindow = window.isSurface() ? window : child;
            }
            else if (window.isSurface() && !window.isApplicationWindow()) {
                Window parent = window.getParent();
                if (parent != null && parent.isApplicationWindow() && !parent.isSurface()) frameRatingWindow = window;
            }

            if (frameRatingWindow != null) {
                if (frameRating.getMode() == FrameRating.Mode.FULL) {
                    Property gpuInfo = frameRatingWindow.getProperty(Atom._NET_WM_GPU_INFO);
                    frameRating.setGPUInfo(gpuInfo != null ? new String(gpuInfo.data.array()) : "N/A");
                }
                frameRatingWindowId = frameRatingWindow.id;
                frameRating.reset();
            }
        }
        else if (window.id == frameRatingWindowId) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.setVisibility(View.GONE));
        }
    }

    public boolean verifyUserRegistry() {
        File userRegFile = new File(rootFS.getRootDir(), RootFS.WINEPREFIX+"/user.reg");
        String lastModified = String.valueOf(userRegFile.lastModified());

        if (!lastModified.equals(container.getExtra("userRegLastModified"))) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.removeKey("Software\\Wow6432Node\\Wine", true);
            }

            container.putExtra("userRegLastModified", lastModified);
            return true;
        }
        else return false;
    }
}
