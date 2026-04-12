package com.conch.ssh.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConchSshClientProxyCommandTest {

    @Test
    void proxyCommand_sshW_basicHost() {
        assertEquals(
            "bastion",
            ConchSshClient.proxyJumpFromProxyCommand("ssh -W %h:%p bastion"));
    }

    @Test
    void proxyCommand_sshW_withOptionsAndQuotedTarget() {
        assertEquals(
            "deploy@bastion.internal:2222",
            ConchSshClient.proxyJumpFromProxyCommand(
                "ssh -o StrictHostKeyChecking=no -W '%h:%p' deploy@bastion.internal:2222"));
    }

    @Test
    void proxyCommand_nonSshCommand_notSupported() {
        assertNull(ConchSshClient.proxyJumpFromProxyCommand("nc %h %p"));
    }

    @Test
    void proxyCommand_missingW_notSupported() {
        assertNull(ConchSshClient.proxyJumpFromProxyCommand("ssh bastion"));
    }
}
