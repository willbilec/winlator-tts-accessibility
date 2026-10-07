package com.winlator.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class BreedMemorialAudioCompatibilityTest {
    @Test public void appliesOnlyToBreedMemorial() {
        assertTrue(BreedMemorialAudioCompatibility.appliesTo("E:\\games\\Breed memorial\\breed memorial.exe"));
        assertTrue(BreedMemorialAudioCompatibility.appliesTo("/games/BREED MEMORIAL.EXE"));
        assertFalse(BreedMemorialAudioCompatibility.appliesTo("breed memorial.exe.backup"));
        assertFalse(BreedMemorialAudioCompatibility.appliesTo("/breed memorial.exe/translate checker.exe"));
        assertFalse(BreedMemorialAudioCompatibility.appliesTo("Chillingham.exe"));
        assertFalse(BreedMemorialAudioCompatibility.appliesTo(null));
    }
}
