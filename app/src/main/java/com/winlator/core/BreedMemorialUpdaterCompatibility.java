package com.winlator.core;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Preserve unknown/newer HSPInet versions; patch only the verified offending version. */
public final class BreedMemorialUpdaterCompatibility {
    public static final String ORIGINAL = "7279e26db1e6d2b02fa531548b259d1f21116963108e0cd7ece4a528ac2d55c9";
    public static final String PATCHED = "bb19e109edd7fd067204fe556dd56d7ae7187b749e6722d18b4448d2eff12666";
    private BreedMemorialUpdaterCompatibility() {}
    public static boolean isKnownOriginal(String hash) { return ORIGINAL.equalsIgnoreCase(hash); }
    public static boolean isPatched(String hash) { return PATCHED.equalsIgnoreCase(hash); }
    private static String hash(File file) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
            StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (NoSuchAlgorithmException exception) { throw new IOException(exception); }
    }
    public static boolean provision(Context context, File executable) throws IOException {
        File library = new File(executable.getParentFile(), "dll/hspinet.dll");
        if (!library.isFile()) return false;
        String current = hash(library);
        if (isPatched(current)) return true;
        if (!isKnownOriginal(current)) {
            android.util.Log.w("BreedMemorialUpdater", "Unknown HSPInet version; preserving " + current);
            return false;
        }
        File backup = new File(library.getParentFile(), "hspinet.dll.before-bmguard");
        if (!backup.isFile() && !FileUtils.copy(library, backup)) throw new IOException("Could not back up HSPInet.");
        File pending = new File(library.getParentFile(), "hspinet.dll.winlator-pending");
        FileUtils.copy(context, "audio_game_dependencies/breed_memorial_hspinet_guard.dll", pending);
        if (!pending.isFile() || !isPatched(hash(pending))) throw new IOException("Updater guard payload verification failed.");
        Files.move(pending.toPath(), library.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
