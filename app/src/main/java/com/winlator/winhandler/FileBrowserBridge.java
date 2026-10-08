package com.winlator.winhandler;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Private, session-scoped guest requests. No shell commands or exported Android component. */
public final class FileBrowserBridge implements AutoCloseable {
    public interface Handler { Reply handle(String operation, String path) throws Exception; }
    public static final class Reply {
        public final int status;
        public final String message;
        public final Runnable afterReply;
        public Reply(int status, String message, Runnable afterReply) {
            this.status = status; this.message = message; this.afterReply = afterReply;
        }
    }
    public static final int MAGIC = 0x57464231;
    private static final class Completed {
        final String operation, path;
        final Reply reply;
        boolean dispatched;
        Completed(String operation, String path, Reply reply) {
            this.operation = operation; this.path = path; this.reply = reply;
        }
    }
    private final ServerSocket server;
    private final String token = UUID.randomUUID().toString();
    private final Handler handler;
    private volatile boolean closed;
    private volatile Socket active;
    private final Map<String, Completed> completed = new LinkedHashMap<>();

    public FileBrowserBridge(Handler handler) throws IOException {
        this.handler = handler;
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(this::serve, "WinlatorFileBrowser");
        thread.setDaemon(true); thread.start();
    }
    public String configuration() {
        return "[session]\nport=" + server.getLocalPort() + "\ntoken=" + token + "\n";
    }
    public int port() { return server.getLocalPort(); }
    public String token() { return token; }

    public static String readString(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > limit) throw new IOException("Invalid field length");
        byte[] bytes = new byte[length]; input.readFully(bytes);
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
                throw new IOException("Invalid control character");
            return value;
        } catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8", e); }
    }
    public static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }
    private void serve() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                active = socket; socket.setSoTimeout(5000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                if (input.readInt() != MAGIC) throw new IOException("Unknown protocol");
                String suppliedToken = readString(input, 128);
                if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8)))
                    throw new IOException("Wrong session");
                String id = readString(input, 128), operation = readString(input, 32), path = readString(input, 131072);
                if (id.isEmpty()) throw new IOException("Missing request id");
                Completed previous = completed.get(id);
                Reply reply;
                if (previous != null) {
                    reply = previous.operation.equals(operation) && previous.path.equals(path) ? previous.reply :
                            new Reply(1, "Request id already used for a different request", null);
                } else {
                    try {
                        if (!"launch".equals(operation) && !"shortcut".equals(operation)) throw new IOException("Unknown operation");
                        if (!path.matches("(?s)^[A-Za-z]:[\\\\/].*") || path.indexOf('"') >= 0)
                            throw new IOException("An absolute mapped Windows path is required");
                        reply = handler.handle(operation, path);
                    } catch (Exception e) {
                        reply = new Reply(1, e.getMessage() == null ? "Request failed" : e.getMessage(), null);
                    }
                    previous = new Completed(operation, path, reply);
                    completed.put(id, previous);
                    if (completed.size() > 32) completed.remove(completed.keySet().iterator().next());
                }
                writeString(output, id); output.writeInt(reply.status); writeString(output, reply.message); output.flush();
                // Tear down only after the response has been sent to the guest.
                if (!previous.dispatched && reply.status == 0 && reply.afterReply != null) {
                    previous.dispatched = true;
                    reply.afterReply.run();
                }
            } catch (IOException ignored) {
                // Malformed/stale/abandoned clients never end the guest session.
            } finally { active = null; }
        }
    }
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) {}
        Socket socket = active;
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }
}
