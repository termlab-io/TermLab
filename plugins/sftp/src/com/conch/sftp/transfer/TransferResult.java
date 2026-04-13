package com.conch.sftp.transfer;

/**
 * Summary of a single {@link TransferEngine} batch run. Carries the
 * counters the {@link TransferCoordinator} needs to build a status
 * notification without having to snapshot {@link TransferEngine}
 * internals separately.
 *
 * <p>{@code bytesTransferred} counts only bytes actually copied — it
 * excludes the "synthetic" advance that {@code TransferEngine} applies
 * to the progress bar when a file is skipped. {@code filesTransferred}
 * is the same: skipped files are not counted.
 */
public record TransferResult(
    int filesTransferred,
    long bytesTransferred,
    long totalBytes,
    long elapsedNanos
) {
    public static final TransferResult EMPTY = new TransferResult(0, 0L, 0L, 0L);
}
