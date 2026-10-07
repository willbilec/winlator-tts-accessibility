package com.winlator.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class BreedMemorialUpdaterCompatibilityTest {
    @Test public void patchesOnlyTheVerifiedOriginal() {
        assertTrue(BreedMemorialUpdaterCompatibility.isKnownOriginal(BreedMemorialUpdaterCompatibility.ORIGINAL.toUpperCase()));
        assertFalse(BreedMemorialUpdaterCompatibility.isKnownOriginal(BreedMemorialUpdaterCompatibility.PATCHED));
        assertFalse(BreedMemorialUpdaterCompatibility.isKnownOriginal("newer-custom-plugin"));
        assertTrue(BreedMemorialUpdaterCompatibility.isPatched(BreedMemorialUpdaterCompatibility.PATCHED));
        assertFalse(BreedMemorialUpdaterCompatibility.isPatched(null));
    }
}
