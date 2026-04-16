package com.termlab.ssh.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TermLabSshClientProxyCommandTest {

    @Test
    void expandProxyCommand_replacesHostPortUserAndPercent() {
        assertEquals(
            "cloudflared access ssh --hostname lab.example.com --user root --port 2222 --literal-%",
            TermLabSshClient.expandProxyCommand(
                "cloudflared access ssh --hostname %h --user %r --port %p --literal-%%",
                "lab.example.com",
                2222,
                "root"));
    }

    @Test
    void expandProxyCommand_unknownMacrosRemainAsIs() {
        assertEquals(
            "cmd %x lab.example.com",
            TermLabSshClient.expandProxyCommand("cmd %x %h", "lab.example.com", 22, "root"));
    }

    @Test
    void proxyCommand_sshW_basicHost() {
        assertEquals(
            "bastion",
            TermLabSshClient.proxyJumpFromProxyCommand("ssh -W %h:%p bastion"));
    }

    @Test
    void proxyCommand_sshW_withOptionsAndQuotedTarget() {
        assertEquals(
            "deploy@bastion.internal:2222",
            TermLabSshClient.proxyJumpFromProxyCommand(
                "ssh -o StrictHostKeyChecking=no -W '%h:%p' deploy@bastion.internal:2222"));
    }

    @Test
    void proxyCommand_nonSshCommand_notSupported() {
        assertNull(TermLabSshClient.proxyJumpFromProxyCommand("nc %h %p"));
    }

    @Test
    void proxyCommand_missingW_notSupported() {
        assertNull(TermLabSshClient.proxyJumpFromProxyCommand("ssh bastion"));
    }
}
