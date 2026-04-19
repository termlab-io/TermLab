package com.termlab.sftp.toolwindow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SftpVisibilityFiltersTest {

    @Test
    void hidesDotfilesLocallyWhenHiddenFilesAreOff() throws IOException {
        Path tempDir = Files.createTempDirectory("sftp-visibility");
        try {
            Path dotfile = Files.createFile(tempDir.resolve(".env"));
            assertFalse(SftpVisibilityFilters.shouldShowLocal(dotfile, false));
            assertTrue(SftpVisibilityFilters.shouldShowLocal(dotfile, true));
        } finally {
            Files.deleteIfExists(tempDir.resolve(".env"));
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void hidesDotfilesRemotelyWhenHiddenFilesAreOff() {
        assertFalse(SftpVisibilityFilters.shouldShowRemote(".ssh", false));
        assertTrue(SftpVisibilityFilters.shouldShowRemote(".ssh", true));
        assertTrue(SftpVisibilityFilters.shouldShowRemote("Documents", false));
    }
}
