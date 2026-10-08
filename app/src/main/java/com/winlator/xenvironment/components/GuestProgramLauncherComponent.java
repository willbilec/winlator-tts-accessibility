package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.LocaleHelper;
import com.winlator.core.ProcessHelper;
import com.winlator.widget.LogView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private String preLaunchGuestExecutable;
    private File preLaunchReadyFile;
    private File preLaunchHeartbeatFile;
    private File preLaunchErrorFile;
    private Callback<String> preLaunchFailureCallback;
    private int preLaunchPid = -1;
    private EnvVars preLaunchEnvVars;
    private File preLaunchRootDir;
    private volatile boolean preLaunchRunning;
    private Thread preLaunchWatchdog;
    private long preLaunchTime;
    private volatile boolean preLaunchExited;
    private volatile boolean launchRunning;
    private static int pid = -1;
    private EnvVars envVars;
    private String box64Preset = Box64Preset.CONSERVATIVE;
    private Callback<Integer> terminationCallback;
    private String autoExitTargetExecutable;
    private boolean monitorProgramExit;
    private volatile boolean autoExitRunning;
    private Thread autoExitMonitor;
    private boolean terminationReported;
    private volatile boolean liveTrackedProcesses;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            extractBox64File();
            copyDefaultBox64RCFile();
            launchRunning = true;
            terminationReported = false;
            liveTrackedProcesses = false;
        }
        // Provisioning can take longer than a frame or a lifecycle callback.
        // Keep pause, stop, and cancellation free to acquire the process lock.
        int launchedPid = execGuestProgram();
        synchronized (lock) {
            if (!launchRunning) {
                if (launchedPid != -1) ProcessHelper.killProcessTree(launchedPid);
                return;
            }
            pid = launchedPid;
            if (pid != -1 && preLaunchGuestExecutable != null) startPreLaunchWatchdog();
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(environment.getContext());
            if (pid != -1 && (monitorProgramExit || preferences.getBoolean("auto_close_container", true))) startAutoExitMonitor();
            else if (pid != -1) android.util.Log.i("WinlatorExitMonitor", "Automatic container close disabled by settings");
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            launchRunning = false;
            autoExitRunning = false;
            liveTrackedProcesses = false;
            if (autoExitMonitor != null) {
                autoExitMonitor.interrupt();
                autoExitMonitor = null;
            }
            preLaunchRunning = false;
            if (preLaunchWatchdog != null) {
                preLaunchWatchdog.interrupt();
                preLaunchWatchdog = null;
            }
            if (pid != -1) {
                ProcessHelper.killProcessTree(pid);
                pid = -1;
            }
            if (preLaunchPid != -1) {
                ProcessHelper.killProcessTree(preLaunchPid);
                preLaunchPid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public void setAutoExitTargetExecutable(String executableName) {
        autoExitTargetExecutable = executableName == null ? null : executableName.trim().toLowerCase(Locale.ROOT);
    }

    public void setMonitorProgramExit(boolean enabled) {
        monitorProgramExit = enabled;
    }

    private void startAutoExitMonitor() {
        autoExitRunning = true;
        android.util.Log.i("WinlatorExitMonitor", "Starting process monitor for " +
                (autoExitTargetExecutable == null ? "new Windows executables" : autoExitTargetExecutable));
        autoExitMonitor = new Thread(() -> {
            long baselineDeadline = System.currentTimeMillis() + 10000;
            Set<Integer> baseProcessIds = new HashSet<>();
            Map<Integer, String> trackedExecutables = new HashMap<>();
            boolean baselineReady = false;
            int emptySamples = 0;
            while (autoExitRunning) {
                try { Thread.sleep(1000); }
                catch (InterruptedException exception) { break; }
                if (!autoExitRunning) break;
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                if (!baselineReady && System.currentTimeMillis() >= baselineDeadline) {
                    for (ProcessHelper.PStat process : processes) baseProcessIds.add(process.pid);
                    baselineReady = true;
                }
                for (ProcessHelper.PStat process : processes) {
                    if (process.state == ProcessHelper.PState.ZOMBIE || process.state == ProcessHelper.PState.DEAD) continue;
                    String executable = getWindowsExecutable(process);
                    if (executable == null) continue;
                    boolean expectedLaunch = autoExitTargetExecutable != null &&
                            autoExitTargetExecutable.equalsIgnoreCase(executable);
                    if ((expectedLaunch || (baselineReady && !baseProcessIds.contains(process.pid))) &&
                            !trackedExecutables.containsKey(process.pid)) {
                        trackedExecutables.put(process.pid, executable);
                        android.util.Log.i("WinlatorExitMonitor", "Tracking pid=" + process.pid + " executable=" + executable);
                    }
                }
                Set<Integer> liveTrackedProcesses = new HashSet<>();
                for (ProcessHelper.PStat process : processes) {
                    if (trackedExecutables.containsKey(process.pid) &&
                            process.state != ProcessHelper.PState.ZOMBIE && process.state != ProcessHelper.PState.DEAD &&
                            getWindowsExecutable(process) != null)
                        liveTrackedProcesses.add(process.pid);
                }
                this.liveTrackedProcesses = !liveTrackedProcesses.isEmpty();
                if (!trackedExecutables.isEmpty() && liveTrackedProcesses.isEmpty()) {
                    if (++emptySamples < 3) continue;
                    Callback<Integer> callback = null;
                    synchronized (lock) {
                        if (autoExitRunning && launchRunning && !terminationReported) {
                            terminationReported = true;
                            callback = terminationCallback;
                        }
                        autoExitRunning = false;
                    }
                    if (callback != null) callback.call(0);
                    android.util.Log.i("WinlatorExitMonitor", "Tracked guest executables exited; stopping session");
                    break;
                }
                else if (!liveTrackedProcesses.isEmpty()) emptySamples = 0;
            }
        }, "WinlatorProcessExitMonitor");
        autoExitMonitor.setDaemon(true);
        autoExitMonitor.start();
    }

    private String getWindowsExecutable(ProcessHelper.PStat process) {
        List<String> commandLine = ProcessHelper.getProcessCmdLine(process.pid);
        for (String argument : commandLine) {
            String normalized = argument.replace('\\', '/').toLowerCase(Locale.ROOT);
            int slash = normalized.lastIndexOf('/');
            String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            if (basename.endsWith(".exe")) return basename;
        }
        String name = process.name.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('/');
        String basename = slash >= 0 ? name.substring(slash + 1) : name;
        return basename.endsWith(".exe") ? basename : null;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public void setPreLaunchGuestExecutable(String executable, File readyFile) {
        preLaunchGuestExecutable = executable;
        preLaunchReadyFile = readyFile;
        preLaunchHeartbeatFile = new File(readyFile.getParentFile(), "nvda-rpc-heartbeat");
        preLaunchErrorFile = new File(readyFile.getParentFile(), "native-sapi-error");
    }

    public void setPreLaunchFailureCallback(Callback<String> callback) {
        preLaunchFailureCallback = callback;
    }

    private void startPreLaunchWatchdog() {
        preLaunchRunning = true;
        preLaunchWatchdog = new Thread(() -> {
            while (preLaunchRunning) {
                try { Thread.sleep(3000); }
                catch (InterruptedException exception) { break; }
                synchronized (lock) {
                    if (!preLaunchRunning) break;
                    long now = System.currentTimeMillis();
                    if (now - preLaunchTime < 15000) continue;
                    if (preLaunchHeartbeatFile.isFile() &&
                            now - preLaunchHeartbeatFile.lastModified() < 8000) continue;
                    if (preLaunchPid != -1) ProcessHelper.killProcessTree(preLaunchPid);
                    preLaunchReadyFile.delete();
                    preLaunchHeartbeatFile.delete();
                    preLaunchTime = now;
                    preLaunchPid = ProcessHelper.exec(preLaunchRootDir+"/usr/local/bin/box64 "+
                            preLaunchGuestExecutable, preLaunchEnvVars, preLaunchRootDir);
                }
            }
        }, "WinlatorNvdaWatchdog");
        preLaunchWatchdog.setDaemon(true);
        preLaunchWatchdog.start();
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public boolean hasLiveTrackedProcesses() {
        return liveTrackedProcesses;
    }

    private int execGuestProgram() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();

        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars);
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PROTON_VOICE_FILES", rootDir+"/opt/proton-voices");
        envVars.put("PATH", rootDir+rootFS.getWinePath()+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
        envVars.put("BOX64_LD_LIBRARY_PATH", rootDir+"/lib/x86_64-linux-gnu");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);

        if (this.envVars != null) envVars.putAll(this.envVars);
        // Speech is selected by the container's provisioned registry, not a stale
        // per-shortcut sapi override. Preserve every unrelated DLL override.
        if (preLaunchGuestExecutable != null && envVars.has("WINEDLLOVERRIDES")) {
            StringBuilder overrides = new StringBuilder();
            for (String entry : envVars.get("WINEDLLOVERRIDES").split(";")) {
                int separator = entry.indexOf('=');
                if (separator < 0) continue;
                StringBuilder names = new StringBuilder();
                for (String name : entry.substring(0, separator).split(",")) {
                    if (name.trim().equalsIgnoreCase("sapi")) continue;
                    if (names.length() > 0) names.append(',');
                    names.append(name);
                }
                if (names.length() == 0) continue;
                if (overrides.length() > 0) overrides.append(';');
                overrides.append(names).append(entry.substring(separator));
            }
            envVars.put("WINEDLLOVERRIDES", overrides.toString());
        }

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        if (preLaunchGuestExecutable != null) {
            preLaunchEnvVars = envVars;
            preLaunchRootDir = rootDir;
            if (preLaunchReadyFile != null) preLaunchReadyFile.delete();
            if (preLaunchHeartbeatFile != null) preLaunchHeartbeatFile.delete();
            if (preLaunchErrorFile != null) preLaunchErrorFile.delete();
            preLaunchTime = System.currentTimeMillis();
            preLaunchExited = false;
            preLaunchPid = ProcessHelper.exec(rootDir+"/usr/local/bin/box64 "+preLaunchGuestExecutable,
                    envVars, rootDir, (status) -> preLaunchExited = true);
            if (!launchRunning) {
                if (preLaunchPid != -1) ProcessHelper.killProcessTree(preLaunchPid);
                preLaunchPid = -1;
                return -1;
            }
            if (preLaunchPid != -1 && preLaunchReadyFile != null) {
                long deadline = System.currentTimeMillis() + 180000;
                while (!preLaunchReadyFile.isFile() && !preLaunchErrorFile.isFile() &&
                        !preLaunchExited && launchRunning && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(100); }
                    catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!launchRunning) return -1;
            if (preLaunchPid == -1 || !preLaunchReadyFile.isFile()) {
                if (preLaunchPid != -1) ProcessHelper.killProcessTree(preLaunchPid);
                preLaunchPid = -1;
                if (preLaunchFailureCallback != null) {
                    String detail = preLaunchErrorFile.isFile() ? FileUtils.readString(preLaunchErrorFile) : "";
                    preLaunchFailureCallback.call("Speech is not ready. The game has not been started." +
                            (detail != null && !detail.trim().isEmpty() ? "\n\n" + detail.trim() : ""));
                }
                return -1;
            }
        }

        if (!launchRunning) return -1;

        String command = rootDir+"/usr/local/bin/box64 "+guestExecutable;

        return ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            android.util.Log.i("WinlatorGuest", "Guest desktop launcher exited with status " + status);
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    private void extractBox64File() {
        Context context = environment.getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String box64Version = preferences.getString("box64_version", DefaultVersion.BOX64);
        String currentBox64Version = preferences.getString("current_box64_version", "");

        if (!box64Version.equals(currentBox64Version)) {
            GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, box64Version, DefaultVersion.BOX64);
            preferences.edit().putString("current_box64_version", box64Version).apply();
        }
    }

    private void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        FileUtils.copy(context, "box64/default.box64rc", new File(rootFS.getRootDir(), "/etc/config.box64rc"));
    }

    private void addBox64EnvVars(EnvVars envVars) {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int box64Logs = preferences.getInt("box64_logs", 0);
        boolean saveToFile = preferences.getBoolean("save_logs_to_file", false);

        envVars.put("BOX64_NOBANNER", box64Logs >= 1 ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_UNITYPLAYER", "0");
        envVars.put("BOX64_DYNACACHE", "0");

        if (box64Logs >= 1) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");

            if (box64Logs == 2) {
                envVars.put("BOX64_SHOWSEGV", "1");
                envVars.put("BOX64_DLSYM_ERROR", "1");
                envVars.put("BOX64_TRACE_FILE", "stderr");

                if (saveToFile) {
                    File parent = (new File(preferences.getString("log_file", LogView.getLogFile().getPath()))).getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        File traceDir = new File(parent, "trace");
                        if (!traceDir.isDirectory()) traceDir.mkdirs();
                        FileUtils.clear(traceDir);

                        envVars.put("BOX64_TRACE_FILE", traceDir+"/box64-%pid.txt");
                    }
                }
            }
        }

        envVars.putAll(Box64PresetManager.getEnvVars(context, box64Preset));

        File box64RCFile = new File(rootFS.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}
