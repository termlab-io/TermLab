package com.termlab.ssh.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SshSessionProviderTest {

    @Test
    void sshDisconnectKeepsTerminalTabOpen() {
        assertFalse(new SshSessionProvider().closeTabOnSessionEnd());
    }
}
