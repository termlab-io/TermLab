package com.conch.core.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "ConchTerminalConfig", storages = @Storage("conch-terminal.xml"))
public final class ConchTerminalConfig implements PersistentStateComponent<ConchTerminalConfig.State> {

    public static ConchTerminalConfig getInstance() {
        return ApplicationManager.getApplication().getService(ConchTerminalConfig.class);
    }

    public static class State {
        // Font
        public String fontFamily = "";  // empty = auto-detect best monospace
        public int fontSize = 14;

        // Colors (hex)
        public String foreground = "#BBBBBB";
        public String background = "#2B2B2B";
        public String selectionForeground = "#FFFFFF";
        public String selectionBackground = "#214283";

        // Cursor
        public String cursorShape = "BLOCK";  // BLOCK, UNDERLINE, VERTICAL_BAR

        // Behavior
        public int scrollbackLines = 10000;
        public boolean copyOnSelect = false;
        public boolean audibleBell = false;
        public boolean enableMouseReporting = true;
    }

    private State myState = new State();

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }
}
