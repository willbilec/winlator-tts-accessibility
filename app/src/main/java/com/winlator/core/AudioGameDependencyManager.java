package com.winlator.core;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.xenvironment.RootFS;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installs game-specific Wine dependencies and records which game processes may close a session. */
public final class AudioGameDependencyManager {
    private static final String CONTAINER_EXTRA = "audioGameDependencies";
    private static final String ASSET_ROOT = "audio_game_dependencies/";

    public static final class RuntimeFile {
        public final String assetName;
        public final String destinationName;

        private RuntimeFile(String assetName, String destinationName) {
            this.assetName = assetName;
            this.destinationName = destinationName;
        }
    }

    public static final class ConfigPreset {
        public final String[] gameDirectories;
        public final String executableName;
        public final String fileName;
        public final String contentTemplate;
        public final String countryCode;

        private ConfigPreset(String[] gameDirectories, String executableName, String fileName, String contentTemplate, String countryCode) {
            this.gameDirectories = gameDirectories;
            this.executableName = executableName;
            this.fileName = fileName;
            this.contentTemplate = contentTemplate;
            this.countryCode = countryCode;
        }
    }

    public static final class GameDefinition {
        public final String id;
        public final String name;
        public final String description;
        public final List<String> processNames;
        public final List<RuntimeFile> runtimeFiles;
        public final Map<String, String> dllOverrides;
        public final ConfigPreset configPreset;

        private GameDefinition(String id, String name, String description, List<String> processNames,
                               List<RuntimeFile> runtimeFiles, Map<String, String> dllOverrides,
                               ConfigPreset configPreset) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.processNames = Collections.unmodifiableList(processNames);
            this.runtimeFiles = Collections.unmodifiableList(runtimeFiles);
            this.dllOverrides = Collections.unmodifiableMap(dllOverrides);
            this.configPreset = configPreset;
        }
    }

    public static final class InstallResult {
        public final boolean configurationPrepared;
        public final boolean configurationRequired;

        private InstallResult(boolean configurationPrepared, boolean configurationRequired) {
            this.configurationPrepared = configurationPrepared;
            this.configurationRequired = configurationRequired;
        }
    }

    private static final List<GameDefinition> CATALOG = createCatalog();

    private AudioGameDependencyManager() {}

    private static List<GameDefinition> createCatalog() {
        List<RuntimeFile> lightCarsFiles = Arrays.asList(
                new RuntimeFile("light_cars/dx8vb.dll", "dx8vb.dll"),
                new RuntimeFile("light_cars/msvbvm60.dll", "msvbvm60.dll"));
        Map<String, String> lightCarsOverrides = new LinkedHashMap<>();
        lightCarsOverrides.put("dx8vb", "native");
        ConfigPreset lightCarsConfig = new ConfigPreset(
                new String[]{
                        "Program Files/Lighttech Interactive/Light Cars",
                        "Program Files (x86)/Lighttech Interactive/Light Cars"},
                "lightCars.exe", "config.dat", "${USER} ${HOST}\r\nna@na.na\r\n${COUNTRY}\r\n", "US");

        GameDefinition lightCars = new GameDefinition(
                "light-cars", "Light Cars",
                "Visual Basic 6 and DirectX 8 VB support; prepares the game's first-run configuration.",
                Collections.singletonList("lightCars.exe"), lightCarsFiles,
                lightCarsOverrides, lightCarsConfig);

        List<RuntimeFile> bavisoftFiles = Arrays.asList(
                new RuntimeFile("grizzly_gulch/msvbvm60.dll", "msvbvm60.dll"),
                new RuntimeFile("grizzly_gulch/mfc42.dll", "mfc42.dll"),
                new RuntimeFile("grizzly_gulch/mfc42u.dll", "mfc42u.dll"));
        GameDefinition grizzlyGulch = new GameDefinition(
                "grizzly-gulch", "Grizzly Gulch",
                "Visual Basic 6 runtime and Visual C++ 6 MFC 4.2 libraries.",
                Collections.singletonList("Grizzly Gulch.exe"), bavisoftFiles,
                Collections.emptyMap(), null);

        GameDefinition chillingham = new GameDefinition(
                "chillingham", "Chillingham",
                "Visual Basic 6 runtime and Visual C++ 6 MFC 4.2 libraries.",
                Collections.singletonList("Chillingham.exe"), bavisoftFiles,
                Collections.emptyMap(), null);

        GameDefinition breedMemorial = new GameDefinition(
                "breed-memorial", "Breed Memorial",
                "CJK fonts and Windows 7 compatibility; applies updater and audio fixes automatically on launch.",
                Arrays.asList("breed memorial.exe", "bm_updater.exe"), Collections.emptyList(),
                Collections.emptyMap(), null);
        return Collections.unmodifiableList(Arrays.asList(lightCars, grizzlyGulch, chillingham, breedMemorial));
    }

    public static List<GameDefinition> getCatalog() {
        return CATALOG;
    }

    /** Direct launches automatically provision a catalog entry by exact executable basename. */
    public static GameDefinition findByExecutable(String executable) {
        if (executable == null) return null;
        String path = executable.trim().replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1);
        for (GameDefinition game : CATALOG) {
            for (String processName : game.processNames) {
                if (processName.equalsIgnoreCase(name)) return game;
            }
        }
        return null;
    }

    public static boolean isInstalled(Container container, String gameId) {
        return getInstalledIds(container).contains(gameId);
    }

    public static InstallResult install(Context context, Container container, String gameId) throws IOException {
        GameDefinition game = findGame(gameId);
        if (game == null) throw new IOException("Unknown audio game: " + gameId);
        if (container.getRootDir() == null) throw new IOException("The selected container has no Wine prefix.");

        File prefixDir = new File(container.getRootDir(), ".wine");
        File windowsDir = new File(prefixDir, "drive_c/windows");
        if (!windowsDir.isDirectory()) throw new IOException("The selected container's Wine prefix is missing.");

        File x86Directory = new File(windowsDir, "syswow64");
        if (!x86Directory.isDirectory()) x86Directory = new File(windowsDir, "system32");
        File backupDir = new File(prefixDir, "drive_c/WinlatorAudioGameDependencies/backup/" + game.id);
        File registryFile = new File(prefixDir, "user.reg");
        if (!game.dllOverrides.isEmpty()) backupOnce(registryFile, new File(backupDir, "user.reg.original"));

        for (RuntimeFile runtimeFile : game.runtimeFiles) {
            File destination = new File(x86Directory, runtimeFile.destinationName);
            backupOnce(destination, new File(backupDir, "windows/" + x86Directory.getName() + "/" + runtimeFile.destinationName));
            copyAsset(context, ASSET_ROOT + runtimeFile.assetName, destination);
        }

        if (!game.dllOverrides.isEmpty()) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(registryFile)) {
                for (Map.Entry<String, String> entry : game.dllOverrides.entrySet()) {
                    registryEditor.setStringValue(
                            "Software\\Wine\\AppDefaults\\" + game.processNames.get(0) + "\\DllOverrides",
                            entry.getKey(), entry.getValue());
                }
            }
        }

        if (game.id.equals("breed-memorial")) {
            backupOnce(registryFile, new File(backupDir, "user.reg.original"));
            backupOnce(new File(windowsDir, "Fonts/unifont.ttf"), new File(backupDir, "Fonts/unifont.ttf.original"));
            backupOnce(new File(windowsDir, "Fonts/sourcehansans.ttc"), new File(backupDir, "Fonts/sourcehansans.ttc.original"));
            if (!BreedMemorialCjkFonts.provision(context, prefixDir)) throw new IOException("Could not install Breed Memorial CJK fonts.");
            try (WineRegistryEditor editor = new WineRegistryEditor(registryFile)) {
                editor.setStringValue("Software\\Wine\\AppDefaults\\breed memorial.exe", "Version", "win7");
            }
        }
        boolean configurationPrepared = prepareConfiguration(prefixDir, backupDir, game.configPreset);
        LinkedHashSet<String> installedIds = getInstalledIds(container);
        installedIds.add(game.id);
        JSONArray jsonArray = new JSONArray();
        for (String id : installedIds) jsonArray.put(id);
        container.putExtra(CONTAINER_EXTRA, jsonArray.toString());
        container.saveData();
        return new InstallResult(configurationPrepared, game.configPreset != null);
    }

    private static GameDefinition findGame(String id) {
        for (GameDefinition game : CATALOG) if (game.id.equals(id)) return game;
        return null;
    }

    private static LinkedHashSet<String> getInstalledIds(Container container) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        try {
            JSONArray ids = new JSONArray(container.getExtra(CONTAINER_EXTRA, "[]"));
            for (int i = 0; i < ids.length(); i++) result.add(ids.getString(i));
        }
        catch (JSONException ignored) {}
        return result;
    }

    private static void backupOnce(File source, File backup) throws IOException {
        if (!source.isFile() || backup.isFile()) return;
        File parent = backup.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create dependency backup folder.");
        }
        if (!FileUtils.copy(source, backup)) throw new IOException("Could not back up " + source.getName() + ".");
    }

    private static void copyAsset(Context context, String assetName, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create the Windows system folder.");
        }
        File temporaryFile = new File(parent, destination.getName() + ".audio-game-new");
        try (InputStream input = context.getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(temporaryFile)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (!temporaryFile.renameTo(destination)) {
            if (!FileUtils.copy(temporaryFile, destination)) {
                temporaryFile.delete();
                throw new IOException("Could not install " + destination.getName() + ".");
            }
            temporaryFile.delete();
        }
    }

    private static boolean prepareConfiguration(File prefixDir, File backupDir, ConfigPreset preset) throws IOException {
        if (preset == null) return false;
        for (String relativeDirectory : preset.gameDirectories) {
            File gameDirectory = new File(prefixDir, "drive_c/" + relativeDirectory);
            File executable = new File(gameDirectory, preset.executableName);
            if (!executable.isFile()) continue;

            File configFile = new File(gameDirectory, preset.fileName);
            File marker = new File(backupDir, "configuration-created");
            if (!marker.isFile()) {
                backupOnce(configFile, new File(backupDir, "game/" + preset.fileName + ".original"));
                String content = preset.contentTemplate
                        .replace("${USER}", RootFS.USER)
                        .replace("${HOST}", "Winlator")
                        .replace("${COUNTRY}", preset.countryCode);
                try (FileOutputStream output = new FileOutputStream(configFile)) {
                    output.write(content.getBytes(StandardCharsets.US_ASCII));
                    output.getFD().sync();
                }
                File parent = marker.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create the configuration marker.");
                }
                if (!marker.createNewFile() && !marker.isFile()) {
                    throw new IOException("Could not save the configuration marker.");
                }
            }
            return configFile.isFile();
        }
        return false;
    }
}
