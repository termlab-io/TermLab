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
    void columnsDefaultToVisible() {
        TermLabSftpConfig config = new TermLabSftpConfig();
        for (int column = 0; column < 4; column++) {
            assertTrue(config.isColumnVisible(TermLabSftpConfig.TablePane.LOCAL, column));
            assertTrue(config.isColumnVisible(TermLabSftpConfig.TablePane.REMOTE, column));
        }
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

    @Test
    void persistsColumnVisibilityPerPane() {
        TermLabSftpConfig config = new TermLabSftpConfig();

        config.setColumnVisible(TermLabSftpConfig.TablePane.LOCAL, 1, false);
        config.setColumnVisible(TermLabSftpConfig.TablePane.LOCAL, 3, false);
        config.setColumnVisible(TermLabSftpConfig.TablePane.REMOTE, 2, false);

        assertFalse(config.isColumnVisible(TermLabSftpConfig.TablePane.LOCAL, 1));
        assertFalse(config.isColumnVisible(TermLabSftpConfig.TablePane.LOCAL, 3));
        assertFalse(config.isColumnVisible(TermLabSftpConfig.TablePane.REMOTE, 2));
        assertTrue(config.isColumnVisible(TermLabSftpConfig.TablePane.REMOTE, 0));
        assertFalse(config.getState().showLocalSizeColumn);
        assertFalse(config.getState().showLocalPermissionsColumn);
        assertFalse(config.getState().showRemoteModifiedColumn);
    }

    @Test
    void persistsColumnWidthsPerPane() {
        TermLabSftpConfig config = new TermLabSftpConfig();

        config.setColumnWidth(TermLabSftpConfig.TablePane.LOCAL, 0, 320);
        config.setColumnWidth(TermLabSftpConfig.TablePane.LOCAL, 1, 90);
        config.setColumnWidth(TermLabSftpConfig.TablePane.REMOTE, 2, 180);
        config.setColumnWidth(TermLabSftpConfig.TablePane.REMOTE, 3, 140);

        assertEquals(320, config.getColumnWidth(TermLabSftpConfig.TablePane.LOCAL, 0));
        assertEquals(90, config.getColumnWidth(TermLabSftpConfig.TablePane.LOCAL, 1));
        assertEquals(180, config.getColumnWidth(TermLabSftpConfig.TablePane.REMOTE, 2));
        assertEquals(140, config.getColumnWidth(TermLabSftpConfig.TablePane.REMOTE, 3));
    }

    @Test
    void ignoresNonPositiveColumnWidths() {
        TermLabSftpConfig config = new TermLabSftpConfig();

        config.setColumnWidth(TermLabSftpConfig.TablePane.LOCAL, 0, -5);
        config.setColumnWidth(TermLabSftpConfig.TablePane.REMOTE, 1, 0);

        assertEquals(0, config.getColumnWidth(TermLabSftpConfig.TablePane.LOCAL, 0));
        assertEquals(0, config.getColumnWidth(TermLabSftpConfig.TablePane.REMOTE, 1));
    }
}
