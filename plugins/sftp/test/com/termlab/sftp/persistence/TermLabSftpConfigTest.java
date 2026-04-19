package com.termlab.sftp.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TermLabSftpConfigTest {

    @Test
    void defaultsToBothWhenUnset() {
        TermLabSftpConfig config = new TermLabSftpConfig();
        assertEquals(TermLabSftpConfig.ViewMode.BOTH, config.getViewMode());
    }

    @Test
    void fallsBackToBothWhenStoredValueIsInvalid() {
        TermLabSftpConfig config = new TermLabSftpConfig();
        TermLabSftpConfig.State state = new TermLabSftpConfig.State();
        state.viewMode = "SIDEWAYS";
        config.loadState(state);

        assertEquals(TermLabSftpConfig.ViewMode.BOTH, config.getViewMode());
    }

    @Test
    void persistsSelectedViewMode() {
        TermLabSftpConfig config = new TermLabSftpConfig();

        config.setViewMode(TermLabSftpConfig.ViewMode.REMOTE_ONLY);

        assertEquals("REMOTE_ONLY", config.getState().viewMode);
        assertEquals(TermLabSftpConfig.ViewMode.REMOTE_ONLY, config.getViewMode());
    }

    @Test
    void hiddenFilesVisibilityDefaultsToOff() {
        TermLabSftpConfig config = new TermLabSftpConfig();
        assertFalse(config.isShowHiddenLocalFiles());
        assertFalse(config.isShowHiddenRemoteFiles());
    }

    @Test
    void persistsLocalHiddenFilesVisibility() {
        TermLabSftpConfig config = new TermLabSftpConfig();

        config.setShowHiddenLocalFiles(true);

        assertTrue(config.getState().showHiddenLocalFiles);
        assertTrue(config.isShowHiddenLocalFiles());
        assertFalse(config.isShowHiddenRemoteFiles());
    }

    @Test
    void persistsRemoteHiddenFilesVisibility() {
        TermLabSftpConfig config = new TermLabSftpConfig();

        config.setShowHiddenRemoteFiles(true);

        assertTrue(config.getState().showHiddenRemoteFiles);
        assertTrue(config.isShowHiddenRemoteFiles());
        assertFalse(config.isShowHiddenLocalFiles());
    }
}
