package com.termlab.sftp.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
