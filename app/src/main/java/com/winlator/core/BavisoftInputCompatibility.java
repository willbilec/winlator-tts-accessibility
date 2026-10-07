package com.winlator.core;

import android.content.Context;
import java.io.File;

/** Routes the two identified Bavisoft executables through the keyboard candidate. */
public final class BavisoftInputCompatibility {
    public static final String GUEST_DIRECTORY = "C:\\NVDA-Bridge\\grizzly-input";

    private BavisoftInputCompatibility() {}

    public static boolean appliesTo(String executable) {
        if (executable == null) return false;
        String path = executable.replace('/', '\\');
        int separator = path.lastIndexOf('\\');
        String name = path.substring(separator + 1);
        return name.equalsIgnoreCase("Grizzly Gulch.exe") || name.equalsIgnoreCase("Chillingham.exe");
    }

    public static String command(String executable, String arguments) {
        return command(executable, arguments, true);
    }

    public static String command(String executable, String arguments, boolean rawQueue) {
        return "/dir " + GUEST_DIRECTORY + " \"grizzly-input-launcher.exe\" " +
                "--bavisoft-raw-queue=" + (rawQueue ? "on" : "off") + " " +
                "\"" + executable + "\"" +
                (arguments == null ? "" : arguments);
    }

    public static boolean provision(Context context, File prefix) {
        File destination = new File(prefix, "drive_c/NVDA-Bridge/grizzly-input");
        File launcher = new File(destination, "grizzly-input-launcher.exe");
        File library = new File(destination, "grizzly-input.dll");
        FileUtils.copy(context, "grizzly-input/grizzly-input-launcher.exe", launcher);
        FileUtils.copy(context, "grizzly-input/grizzly-input.dll", library);
        return launcher.isFile() && launcher.length() > 0 &&
                library.isFile() && library.length() > 0;
    }
}
