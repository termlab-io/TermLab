package com.conch.core.actions;

import org.jetbrains.annotations.NotNull;

public final class FocusTerminalUpAction extends DirectionalTerminalNavigationAction {
    @Override
    protected @NotNull Direction direction() {
        return Direction.UP;
    }
}
