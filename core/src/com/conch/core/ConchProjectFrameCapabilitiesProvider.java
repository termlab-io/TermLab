package com.conch.core;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ex.ProjectFrameCapabilitiesProvider;
import com.intellij.openapi.wm.ex.ProjectFrameCapability;
import com.intellij.openapi.wm.ex.ProjectFrameUiPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Conch is a terminal-first product, so we suppress IDE-oriented UI/features
 * like Project View and indexing/background analysis at the frame capability level.
 */
public final class ConchProjectFrameCapabilitiesProvider implements ProjectFrameCapabilitiesProvider {
    private static final Set<ProjectFrameCapability> CONCH_CAPABILITIES = EnumSet.of(
        ProjectFrameCapability.SUPPRESS_PROJECT_VIEW,
        ProjectFrameCapability.SUPPRESS_INDEXING_ACTIVITIES,
        ProjectFrameCapability.SUPPRESS_BACKGROUND_ACTIVITIES
    );

    @Override
    public @NotNull Set<ProjectFrameCapability> getCapabilities(@NotNull Project project) {
        return CONCH_CAPABILITIES;
    }

    @Override
    public @Nullable ProjectFrameUiPolicy getUiPolicy(@NotNull Project project,
                                                      @NotNull Set<? extends ProjectFrameCapability> capabilities) {
        return null;
    }
}
