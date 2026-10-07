package com.winlator.alsaserver;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Validate guest IPC before touching mapped memory; malformed clients must not abort ART. */
public final class SharedAudioBuffer {
    private SharedAudioBuffer() {}
    public static void copy(ByteBuffer source, ByteBuffer target, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > source.capacity() ||
                length > source.capacity() - offset || length > target.capacity()) {
            throw new IOException("Invalid ALSA shared write: bytes=" + length +
                    " mapped=" + source.capacity() + " target=" + target.capacity());
        }
        ByteBuffer input = source.duplicate();
        input.clear();
        input.position(offset).limit(offset + length);
        target.clear().limit(length);
        target.put(input);
    }
}
