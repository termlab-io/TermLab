package com.termlab.core.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalSessionProviderClosePolicyTest {

    @Test
    void localPtyClosesTabWhenShellExits() {
        assertTrue(new LocalPtySessionProvider().closeTabOnSessionEnd());
    }
}
