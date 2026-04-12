package com.conch.tunnels.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SshConfigParserTest {

    @Test
    void parseHostAliases_basicHosts(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host bastion
                HostName bastion.example.com
                User deploy

            Host prod-db
                HostName db.internal
                Port 2222
            """);
        List<String> aliases = SshConfigParser.parseHostAliases(config);
        assertEquals(List.of("bastion", "prod-db"), aliases);
    }

    @Test
    void parseHostAliases_skipsWildcards(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host *
                ServerAliveInterval 60

            Host bastion
                HostName bastion.example.com

            Host *.internal
                User deploy
            """);
        List<String> aliases = SshConfigParser.parseHostAliases(config);
        assertEquals(List.of("bastion"), aliases);
    }

    @Test
    void parseHostAliases_multipleAliasesPerLine(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host alpha bravo charlie
                HostName example.com
            """);
        List<String> aliases = SshConfigParser.parseHostAliases(config);
        assertEquals(List.of("alpha", "bravo", "charlie"), aliases);
    }

    @Test
    void parseHostAliases_skipsNegated(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, """
            Host bastion !internal
                HostName bastion.example.com
            """);
        List<String> aliases = SshConfigParser.parseHostAliases(config);
        assertEquals(List.of("bastion"), aliases);
    }

    @Test
    void parseHostAliases_missingFile(@TempDir Path tmp) {
        List<String> aliases = SshConfigParser.parseHostAliases(tmp.resolve("nonexistent"));
        assertTrue(aliases.isEmpty());
    }

    @Test
    void parseHostAliases_emptyFile(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.writeString(config, "");
        assertTrue(SshConfigParser.parseHostAliases(config).isEmpty());
    }
}
