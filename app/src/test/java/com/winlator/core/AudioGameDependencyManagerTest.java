package com.winlator.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioGameDependencyManagerTest {
    @Test public void identifiesCatalogGamesFromDirectExecutablePaths() {
        assertEquals("grizzly-gulch", AudioGameDependencyManager.findByExecutable("E:\\games\\Grizzly Gulch.exe").id);
        assertEquals("chillingham", AudioGameDependencyManager.findByExecutable("/games/CHILLINGHAM.EXE").id);
        assertEquals("light-cars", AudioGameDependencyManager.findByExecutable("lightCars.exe").id);
        assertEquals("breed-memorial", AudioGameDependencyManager.findByExecutable("breed memorial.exe").id);
    }

    @Test public void excludesUnknownExecutablesAndIndirectLaunches() {
        assertNull(AudioGameDependencyManager.findByExecutable(null));
        assertNull(AudioGameDependencyManager.findByExecutable(""));
        assertNull(AudioGameDependencyManager.findByExecutable("Chillingham.exe.backup"));
        assertNull(AudioGameDependencyManager.findByExecutable("Chillingham.lnk"));
        assertNull(AudioGameDependencyManager.findByExecutable("E:\\Chillingham.exe\\setup.exe"));
    }
}
