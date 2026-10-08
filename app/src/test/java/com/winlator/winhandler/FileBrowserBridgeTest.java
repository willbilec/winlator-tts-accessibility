package com.winlator.winhandler;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FileBrowserBridgeTest {
    private String call(FileBrowserBridge bridge, String token, String id, String operation, String path, int expected) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", bridge.port())) {
            socket.setSoTimeout(3000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(FileBrowserBridge.MAGIC);
            for (String value : new String[]{token, id, operation, path}) FileBrowserBridge.writeString(out, value);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            assertEquals(id, FileBrowserBridge.readString(in, 128)); assertEquals(expected, in.readInt());
            return FileBrowserBridge.readString(in, 131072);
        }
    }
    @Test public void unicodeAndDuplicateRequestsRunOnce() throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (FileBrowserBridge bridge = new FileBrowserBridge((op, path) -> {
            count.incrementAndGet(); assertEquals("D:\\日本 game\\game.exe", path);
            return new FileBrowserBridge.Reply(0, "Ready 日本", null);
        })) {
            assertEquals("Ready 日本", call(bridge, bridge.token(), "1", "launch", "D:\\日本 game\\game.exe", 0));
            call(bridge, bridge.token(), "1", "launch", "D:\\日本 game\\game.exe", 0);
            call(bridge, bridge.token(), "1", "launch", "C:\\other.exe", 1);
            assertEquals(1, count.get());
        }
    }
    @Test public void launchTransitionRunsAfterReplyAndOnlyOnce() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        java.util.concurrent.CountDownLatch dispatched = new java.util.concurrent.CountDownLatch(1);
        try (FileBrowserBridge bridge = new FileBrowserBridge((op, path) ->
                new FileBrowserBridge.Reply(0, "Launch ready", () -> { delivered.incrementAndGet(); dispatched.countDown(); }))) {
            call(bridge, bridge.token(), "1", "launch", "C:\\game.exe", 0);
            assertTrue(dispatched.await(2, java.util.concurrent.TimeUnit.SECONDS));
            call(bridge, bridge.token(), "1", "launch", "C:\\game.exe", 0);
            assertEquals(1, delivered.get());
        }
    }
    @Test public void invalidRequestsDoNotInvokeHandler() throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (FileBrowserBridge bridge = new FileBrowserBridge((op, path) -> {
            count.incrementAndGet(); return new FileBrowserBridge.Reply(0, "OK", null);
        })) {
            call(bridge, bridge.token(), "1", "shell", "C:\\game.exe", 1);
            call(bridge, bridge.token(), "2", "launch", "relative.exe", 1);
            call(bridge, bridge.token(), "3", "launch", "C:\\evil\".exe", 1);
            try { call(bridge, "old-session", "4", "launch", "C:\\game.exe", 0); fail(); } catch (IOException expected) {}
            assertEquals(0, count.get());
            call(bridge, bridge.token(), "5", "shortcut", "C:\\valid.exe", 0);
            assertEquals(1, count.get());
        }
    }
    @Test public void malformedLengthsAndUtf8AreRejected() throws Exception {
        for (byte[] bytes : new byte[][]{{-1,-1,-1,-1}, {0,0,0,2,(byte)0xc0,(byte)0xaf}, {0,0,0,1,0}}) {
            try { FileBrowserBridge.readString(new DataInputStream(new ByteArrayInputStream(bytes)), 128); fail(); }
            catch (IOException expected) {}
        }
    }
    @Test public void handlerFailureIsAnActionableReply() throws Exception {
        try (FileBrowserBridge bridge = new FileBrowserBridge((op, path) -> { throw new IOException("File missing"); })) {
            assertEquals("File missing", call(bridge, bridge.token(), "1", "launch", "C:\\missing.exe", 1));
        }
    }
}
