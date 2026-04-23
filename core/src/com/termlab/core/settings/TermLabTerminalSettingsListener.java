package com.termlab.core.settings;

import com.intellij.util.messages.Topic;

public interface TermLabTerminalSettingsListener {

    Topic<TermLabTerminalSettingsListener> TOPIC =
        Topic.create("TermLab terminal settings changed", TermLabTerminalSettingsListener.class);

    void settingsChanged();
}
