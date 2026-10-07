package com.winlator.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class BavisoftInputCompatibilityTest {
    @Test public void scopesCompatibilityToExactExecutableName() {
        assertTrue(BavisoftInputCompatibility.appliesTo("E:\\grizzly-gulch\\Grizzly Gulch.exe"));
        assertTrue(BavisoftInputCompatibility.appliesTo("/games/GRIZZLY GULCH.EXE"));
        assertTrue(BavisoftInputCompatibility.appliesTo("E:\\chillingham\\Chillingham.exe"));
        assertFalse(BavisoftInputCompatibility.appliesTo("E:\\Grizzly Gulch.exe\\Super Liam.exe"));
        assertFalse(BavisoftInputCompatibility.appliesTo("Grizzly Gulch.exe.backup"));
        assertFalse(BavisoftInputCompatibility.appliesTo(null));
    }

    @Test public void retainsOriginalGamePathAndArguments() {
        String command = BavisoftInputCompatibility.command(
                "E:\\game folder\\Grizzly Gulch.exe", " --example \"with spaces\"");
        assertTrue(command.contains("\"E:\\game folder\\Grizzly Gulch.exe\""));
        assertTrue(command.endsWith(" --example \"with spaces\""));
        String[] launchArguments = ProcessHelper.splitCommand(command);
        assertEquals("--bavisoft-raw-queue=on", launchArguments[3]);
        assertEquals("\"E:\\game folder\\Grizzly Gulch.exe\"", launchArguments[4]);
    }
}
