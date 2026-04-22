package com.termlab.core.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPtySessionProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void bundledGitBashCommand_defaultsToInteractiveLoginShell() {
        Path runtimeRoot = tempDir.resolve("git");

        assertEquals(
            List.of(runtimeRoot.resolve("bin").resolve("bash.exe").toString(), "--login", "-i"),
            LocalPtySessionProvider.bundledGitBashCommand(runtimeRoot, "")
        );
        assertEquals(
            List.of(runtimeRoot.resolve("bin").resolve("bash.exe").toString(), "--login", "-i"),
            LocalPtySessionProvider.bundledGitBashCommand(runtimeRoot, "-l")
        );
    }

    @Test
    void bundledGitBashEnvironment_prefersBundledToolsAndKeepsCwdBehavior() throws Exception {
        Path runtimeRoot = tempDir.resolve("git");
        Files.createDirectories(runtimeRoot.resolve("cmd"));
        Files.createDirectories(runtimeRoot.resolve("usr").resolve("bin"));
        Files.createDirectories(runtimeRoot.resolve("mingw64").resolve("bin"));

        Map<String, String> env = new HashMap<>();
        env.put("PATH", "C:\\Windows\\System32");

        LocalPtySessionProvider.applyBundledGitBashEnvironmentForTest(env, runtimeRoot);

        String expectedPrefix = String.join(
            File.pathSeparator,
            runtimeRoot.resolve("cmd").toString(),
            runtimeRoot.resolve("usr").resolve("bin").toString(),
            runtimeRoot.resolve("mingw64").resolve("bin").toString()
        );
        assertTrue(env.get("PATH").startsWith(expectedPrefix));
        assertEquals("1", env.get("CHERE_INVOKING"));
        assertEquals("MINGW64", env.get("MSYSTEM"));
        assertEquals(runtimeRoot.resolve("bin").resolve("bash.exe").toString(), env.get("SHELL"));
    }
}
