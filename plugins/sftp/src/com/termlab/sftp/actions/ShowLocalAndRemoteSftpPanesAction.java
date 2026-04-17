package com.termlab.sftp.actions;

import com.termlab.sftp.persistence.TermLabSftpConfig;
import org.jetbrains.annotations.NotNull;

public final class ShowLocalAndRemoteSftpPanesAction extends AbstractSftpViewModeAction {

    public ShowLocalAndRemoteSftpPanesAction() {
        super("Local + Remote", "Show both local and remote SFTP panes");
    }

    @Override
    protected @NotNull TermLabSftpConfig.ViewMode mode() {
        return TermLabSftpConfig.ViewMode.BOTH;
    }
}
