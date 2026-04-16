package com.termlab.core.actions;

import org.jetbrains.annotations.NotNull;

public final class FocusTerminalDownAction extends DirectionalTerminalNavigationAction {
    @Override
    protected @NotNull Direction direction() {
        return Direction.DOWN;
    }
}
