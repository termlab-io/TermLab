package com.termlab.ssh.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical paths for the SSH plugin's on-disk state. Same convention as
 * the rest of TermLab — everything under {@code ~/.config/termlab/} regardless
 * of platform (XDG-style on every OS).
 */
public final class HostPaths {

    private HostPaths() {}

    /**
     * Plaintext JSON file holding the list of saved SSH hosts. Contains
     * no secrets; credentials are referenced by UUID into the vault.
     */
    public static Path hostsFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "termlab", "ssh-hosts.json");
    }

    /**
     * OpenSSH-format known_hosts file. Maintained by {@code KnownHostsFile}
     * and consulted by the server-key verifier on every connect.
     */
    public static Path knownHostsFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "termlab", "known_hosts");
    }
}
