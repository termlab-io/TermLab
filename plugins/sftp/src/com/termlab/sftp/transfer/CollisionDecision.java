package com.termlab.sftp.transfer;

/**
 * The user's response to a single file-collision prompt. The
 * {@code *_ALL} variants latch the choice for the rest of the
 * current batch via {@link CollisionResolver}.
 */
public enum CollisionDecision {
    OVERWRITE,
    OVERWRITE_ALL,
    RENAME,
    SKIP,
    SKIP_ALL,
    CANCEL
}
