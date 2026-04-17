package com.termlab.sftp.actions;

import com.termlab.sftp.persistence.TermLabSftpConfig;
import org.jetbrains.annotations.NotNull;

public final class ShowLocalSftpPaneAction extends AbstractSftpViewModeAction {

    public ShowLocalSftpPaneAction() {
        super("Local", "Show only the local SFTP pane");
    }

    @Override
    protected @NotNull TermLabSftpConfig.ViewMode mode() {
        return TermLabSftpConfig.ViewMode.LOCAL_ONLY;
    }
}
