package com.termlab.sftp.actions;

import com.termlab.sftp.persistence.TermLabSftpConfig;
import org.jetbrains.annotations.NotNull;

public final class ShowRemoteSftpPaneAction extends AbstractSftpViewModeAction {

    public ShowRemoteSftpPaneAction() {
        super("Remote", "Show only the remote SFTP pane");
    }

    @Override
    protected @NotNull TermLabSftpConfig.ViewMode mode() {
        return TermLabSftpConfig.ViewMode.REMOTE_ONLY;
    }
}
