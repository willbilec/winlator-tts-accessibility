package com.winlator.core;

import android.content.Context;
import com.winlator.container.Container;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class AccessibleFileBrowser {
    public static final String DIRECTORY = "C:\\WinlatorFileBrowser";
    public static final String EXECUTABLE = DIRECTORY + "\\accessible-browser.exe";
    private AccessibleFileBrowser() {}

    public static File provision(Context context, File prefix, String configuration) throws IOException {
        File directory = new File(prefix, "drive_c/WinlatorFileBrowser");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create browser directory");
        File target = new File(directory, "accessible-browser.exe"), temporary = new File(directory, "browser.new");
        try (InputStream input = context.getAssets().open("file-browser/accessible-browser.exe");
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16384]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            output.getFD().sync();
        }
        Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.write(new File(directory, "session.ini").toPath(), configuration.getBytes(StandardCharsets.UTF_8));
        return target;
    }

    public static File mappedFile(Container container, String dosPath) throws IOException {
        String mapped = WineUtils.dosToUnixPath(dosPath, container);
        if (mapped == null || mapped.isEmpty()) throw new IOException("This drive is not mapped in the container");
        File file = new File(mapped);
        if (!file.isFile()) throw new IOException("The selected file no longer exists");
        return file;
    }

    public static String createShortcut(Container container, String dosPath) throws IOException {
        File executable = mappedFile(container, dosPath);
        String name = FileUtils.getBasename(executable.getName());
        File desktop = new File(container.getUserDir(), "Desktop");
        if (!desktop.isDirectory() && !desktop.mkdirs()) throw new IOException("Cannot create shortcut directory");
        File file = new File(desktop, name + ".desktop");
        // Never replace existing shortcuts, including their per-game settings.
        for (int suffix = 2; !file.createNewFile(); suffix++) file = new File(desktop, name + " (" + suffix + ").desktop");
        try {
            String content = "[Desktop Entry]\nType=Application\nName=" + FileUtils.getBasename(file.getName()) +
                    "\nExec=" + shortcutCommand(dosPath) + "\nStartupWMClass=" + executable.getName() + "\n";
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { file.delete(); throw e; }
        return "Created Winlator shortcut " + FileUtils.getBasename(file.getName());
    }

    public static String shortcutCommand(String dosPath) {
        // Shortcut's existing parser performs two unescape passes, then collapses
        // doubled separators. Wine-generated .desktop files use this encoding.
        return "wine " + dosPath.replace("\\", "\\\\\\\\").replace(" ", "\\\\ ");
    }
}
