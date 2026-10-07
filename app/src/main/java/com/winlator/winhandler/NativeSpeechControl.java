package com.winlator.winhandler;

import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cancels the native NVDA voice without blocking Android's input thread. */
public final class NativeSpeechControl implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    public void cancel() {
        if (closed) return;
        executor.execute(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                byte[] command = {'C'};
                socket.send(new DatagramPacket(command, command.length,
                        InetAddress.getByName("127.0.0.1"), 51235));
            } catch (IOException e) {
                Log.w("NativeSpeechControl", "Unable to send speech cancellation", e);
            }
        });
    }

    @Override public void close() {
        closed = true;
        executor.shutdownNow();
    }
}
