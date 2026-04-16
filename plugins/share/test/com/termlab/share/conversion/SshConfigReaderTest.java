package com.termlab.share.conversion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshConfigReaderTest {

    @Test
    void directAlias_parsesAllCommonFields(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host bastion
                HostName bastion.example.com
                Port 2222
                User ops
                IdentityFile ~/.ssh/id_ed25519
                ProxyCommand /usr/bin/nc -x proxy:1080 %h %p
            """);

        Optional<SshConfigEntry> result = SshConfigReader.read(config, "bastion");
        assertTrue(result.isPresent());
        SshConfigEntry e = result.get();
        assertEquals("bastion.example.com", e.hostName());
        assertEquals(2222, e.port());
        assertEquals("ops", e.user());
        assertNotNull(e.identityFile());
        assertTrue(e.identityFile().endsWith("id_ed25519"));
        assertNotNull(e.proxyCommand());
        assertTrue(e.warnings().isEmpty());
    }

    @Test
    void aliasNotFound_returnsEmpty(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "Host other\n  HostName other.example.com\n");

        Optional<SshConfigEntry> result = SshConfigReader.read(config, "bastion");
        assertFalse(result.isPresent());
    }

    @Test
    void wildcardHostOnly_returnsEmpty(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host *.internal
                User ops
            """);

        Optional<SshConfigEntry> result = SshConfigReader.read(config, "bastion.internal");
        assertFalse(result.isPresent(), "wildcards are not expanded in v1");
    }

    @Test
    void matchBlock_producesWarning(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Match host bastion
                User ops
            Host bastion
                HostName bastion.example.com
            """);

        Optional<SshConfigEntry> result = SshConfigReader.read(config, "bastion");
        assertTrue(result.isPresent());
        assertFalse(result.get().warnings().isEmpty());
        assertTrue(result.get().warnings().stream().anyMatch(w -> w.contains("Match")));
    }

    @Test
    void include_followsRelativeInclude(@TempDir Path tmp) throws Exception {
        Path main = tmp.resolve("config");
        Path included = tmp.resolve("hosts.conf");
        Files.writeString(included, "Host bastion\n  HostName bastion.example.com\n");
        Files.writeString(main, "Include hosts.conf\n");

        Optional<SshConfigEntry> result = SshConfigReader.read(main, "bastion");
        assertTrue(result.isPresent());
        assertEquals("bastion.example.com", result.get().hostName());
    }

    @Test
    void unknownDirective_producesWarning(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host bastion
                HostName bastion.example.com
                FrobnicateLevel 11
            """);

        Optional<SshConfigEntry> result = SshConfigReader.read(config, "bastion");
        assertTrue(result.isPresent());
        assertTrue(result.get().warnings().stream().anyMatch(w -> w.contains("FrobnicateLevel")));
    }
}
