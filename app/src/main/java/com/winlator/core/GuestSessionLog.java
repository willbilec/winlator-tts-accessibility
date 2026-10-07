package com.winlator.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded launch/error evidence, also available when the debug UI is disabled. */
public final class GuestSessionLog implements Callback<String>, AutoCloseable {
    private FileOutputStream output;
    private int bytes;

    public GuestSessionLog(File directory) {
        File file = new File(directory, "guest-session.log");
        File previous = new File(directory, "guest-session.previous.log");
        if (file.exists()) {
            previous.delete();
            file.renameTo(previous);
        }
        try { output = new FileOutputStream(file); }
        catch (IOException ignored) {}
        call("Session started: " + new java.util.Date());
    }

    @Override public synchronized void call(String line) {
        if (output == null || bytes >= 8 * 1024 * 1024) return;
        byte[] data = ((line.length() > 4096 ? line.substring(0, 4096) : line) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try { output.write(data); bytes += data.length; }
        catch (IOException ignored) { close(); }
    }

    @Override public synchronized void close() {
        if (output == null) return;
        try { output.close(); } catch (IOException ignored) {}
        output = null;
    }
}
