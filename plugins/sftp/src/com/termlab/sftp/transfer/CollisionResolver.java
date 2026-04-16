package com.termlab.sftp.transfer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the user's latched collision choice for a single transfer
 * batch. When the user clicks "Overwrite All" or "Skip All" the
 * engine stops prompting and applies that decision to every
 * subsequent conflict; "Cancel" aborts the rest of the batch.
 */
public final class CollisionResolver {

    /**
     * Prompts the user for a collision decision. Must be safe to
     * call from a background thread — implementations typically
     * marshal to the EDT via {@code invokeAndWait}.
     */
    public interface Prompter {
        @NotNull CollisionDecision ask(@NotNull String destinationDescription, long sourceSize, long existingSize);
    }

    private final Prompter prompter;
    private @Nullable CollisionDecision latched;

    public CollisionResolver(@NotNull Prompter prompter) {
        this.prompter = prompter;
    }

    /**
     * Resolve a collision for {@code destinationDescription}. Returns
     * the decision the caller should act on — one of
     * {@link CollisionDecision#OVERWRITE},
     * {@link CollisionDecision#RENAME},
     * {@link CollisionDecision#SKIP},
     * or {@link CollisionDecision#CANCEL}. The {@code *_ALL} variants
     * are collapsed into their base decision after being latched.
     */
    public @NotNull CollisionDecision resolve(
        @NotNull String destinationDescription,
        long sourceSize,
        long existingSize
    ) {
        if (latched != null) {
            return latched;
        }
        CollisionDecision decision = prompter.ask(destinationDescription, sourceSize, existingSize);
        return switch (decision) {
            case OVERWRITE_ALL -> {
                latched = CollisionDecision.OVERWRITE;
                yield CollisionDecision.OVERWRITE;
            }
            case SKIP_ALL -> {
                latched = CollisionDecision.SKIP;
                yield CollisionDecision.SKIP;
            }
            case CANCEL -> {
                latched = CollisionDecision.CANCEL;
                yield CollisionDecision.CANCEL;
            }
            default -> decision;
        };
    }

    /** True once the user has cancelled the remaining batch. */
    public boolean isCancelled() {
        return latched == CollisionDecision.CANCEL;
    }
}
