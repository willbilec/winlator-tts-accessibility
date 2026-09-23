package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;

import java.io.IOException;

public class FocusChange extends Event {
    public static final byte ANCESTOR = 0;
    public static final byte INFERIOR = 2;
    public static final byte NONLINEAR = 3;
    public static final byte NONE = 7;

    private final Window window;
    private final byte detail;

    public FocusChange(boolean focused, Window window, byte detail) {
        super(focused ? 9 : 10);
        this.window = window;
        this.detail = detail;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte(detail);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(window.id);
            outputStream.writeByte((byte)0); // NotifyNormal
            outputStream.writePad(23);
        }
    }
}
