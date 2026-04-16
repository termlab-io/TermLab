package com.termlab.sftp.transfer;

/**
 * Which way a transfer job moves data through the SFTP tool window.
 *
 * <p>{@link #UPLOAD} means the local pane's selection is copied to
 * the remote pane's current directory; {@link #DOWNLOAD} goes the
 * other way.
 */
public enum TransferDirection {
    UPLOAD,
    DOWNLOAD
}
