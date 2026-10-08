package com.winlator.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class AccessibleFileBrowserTest {
    @Test public void generatedShortcutSurvivesExistingDoubleUnescape() {
        for (String path : new String[]{"C:\\WinlatorFileBrowser\\browser-launch-probe.exe",
                "D:\\日本 game\\folder (x86)\\spaced game.exe", "E:\\games\\O'Brien.exe"}) {
            String command = AccessibleFileBrowser.shortcutCommand(path);
            assertEquals(path, StringUtils.unescapeDOSPath(command.substring(5)));
        }
    }
}
