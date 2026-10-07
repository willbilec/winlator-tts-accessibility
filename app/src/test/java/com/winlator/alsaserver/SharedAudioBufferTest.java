package com.winlator.alsaserver;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class SharedAudioBufferTest {
    @Test public void copiesOnlyPayloadWithoutChangingMappedCursor() throws Exception {
        ByteBuffer source = ByteBuffer.allocate(12);
        source.putInt(123).put(new byte[]{1,2,3,4,5,6,7,8});
        source.position(7).limit(9);
        ByteBuffer target = ByteBuffer.allocate(8);
        SharedAudioBuffer.copy(source, target, 4, 8);
        assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8}, target.array());
        assertEquals(7, source.position()); assertEquals(9, source.limit());
    }
    @Test public void rejectsAllObservedCrashSizesWithoutChangingBuffers() throws Exception {
        for (int length : new int[]{-1,6352,7088,7200,8040,Integer.MAX_VALUE}) {
            ByteBuffer source = ByteBuffer.allocate(6148);
            ByteBuffer target = ByteBuffer.allocate(6144);
            try { SharedAudioBuffer.copy(source,target,4,length); fail("Accepted " + length); }
            catch (IOException expected) { assertEquals(0,target.position()); assertEquals(0,source.position()); }
        }
    }
    @Test public void exactCapacityAndSubsequentSmallWriteAreValid() throws Exception {
        ByteBuffer source=ByteBuffer.allocate(6148), target=ByteBuffer.allocate(6144);
        SharedAudioBuffer.copy(source,target,4,6144);
        assertEquals(6144,target.position());
        SharedAudioBuffer.copy(source,target,4,8);
        assertEquals(8,target.position()); assertEquals(8,target.limit());
    }
}
