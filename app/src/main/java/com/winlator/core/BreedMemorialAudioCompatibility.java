package com.winlator.core;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Game-local BASS keep-output-active wrapper; applied before the next launch. */
public final class BreedMemorialAudioCompatibility {
    private static final String ASSET = "audio_game_dependencies/breed_memorial_bass_keepalive.dll";
    private BreedMemorialAudioCompatibility() {}

    public static boolean appliesTo(String executable) {
        if (executable == null) return false;
        String path = executable.replace('\\', '/');
        return path.substring(path.lastIndexOf('/') + 1).equalsIgnoreCase("breed memorial.exe");
    }

    public static boolean provision(Context context, File executable, boolean enabled) {
        File directory = executable.getParentFile();
        if (directory == null) return false;
        File library = new File(directory, "dll/bass.dll");
        File original = new File(directory, "bass-original.dll");
        File backup = new File(directory, "dll/bass.dll.before-audio-trace");
        if (!library.isFile()) return false;
        try {
            byte[] wrapper;
            try (java.io.InputStream input = context.getAssets().open(ASSET)) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                wrapper = output.toByteArray();
            }
            if (!original.isFile()) {
                if (backup.isFile()) { if (!FileUtils.copy(backup, original)) return false; }
                else {
                    if (Arrays.equals(wrapper, Files.readAllBytes(library.toPath()))) return false;
                    if (!FileUtils.copy(library, original)) return false;
                }
            }
            byte[] desired = enabled ? wrapper : Files.readAllBytes(original.toPath());
            byte[] current = Files.readAllBytes(library.toPath());
            if (!Arrays.equals(desired, current)) {
                // Preserve third-party/custom replacements, rather than overwriting them.
                if (!Arrays.equals(current, wrapper) &&
                        !Arrays.equals(current, Files.readAllBytes(original.toPath()))) return false;
                File pending = new File(directory, "dll/bass.dll.winlator-pending");
                Files.write(pending.toPath(), desired);
                Files.move(pending.toPath(), library.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            android.util.Log.w("BreedMemorialAudio", "Unable to apply audio compatibility", exception);
            return false;
        }
    }
}
