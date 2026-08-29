package com.thoughtcoding.mcp;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MCPClientTest {

    @Test
    void resolvesNpxLauncherOnWindowsWhenItIsInstalled() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }

        String resolved = MCPClient.resolveExecutable("npx");

        assertTrue(resolved.toLowerCase(Locale.ROOT).endsWith("npx.cmd"),
                () -> "Expected npx.cmd but resolved: " + resolved);
    }
}
