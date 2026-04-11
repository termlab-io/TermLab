package com.conch.ssh.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved SSH host.
 *
 * <p>Hostnames and ports aren't secrets — they live in plaintext under
 * {@code ~/.config/conch/ssh-hosts.json}, the same treatment
 * {@code ~/.ssh/config} already gets. The {@link #auth} field carries
 * one of three variants:
 * <ul>
 *   <li>{@link VaultAuth} — points at a
 *       {@code CredentialProvider.CredentialDescriptor} in the vault; the
 *       actual secret is fetched at connect time and never cached outside
 *       the short-lived resolved-credential window.</li>
 *   <li>{@link PromptPasswordAuth} — no saved secret; prompt every
 *       connect.</li>
 *   <li>{@link KeyFileAuth} — a private-key path is saved; the passphrase
 *       (if any) is prompted every connect.</li>
 * </ul>
 *
 * @param id         stable UUID, survives renames
 * @param label      user-facing name ("prod-db-primary")
 * @param host       hostname or IP
 * @param port       SSH port, usually 22
 * @param username   default username for this host. When the referenced
 *                   credential is a standalone SSH key (kind
 *                   {@code SSH_KEY}) the resolved credential has no
 *                   username of its own, so the connector falls back to
 *                   this field.
 * @param auth       how the host authenticates — see {@link SshAuth}
 * @param createdAt  when the host entry was created
 * @param updatedAt  when the host entry was last edited
 */
public record SshHost(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull String host,
    int port,
    @NotNull String username,
    @NotNull SshAuth auth,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) {
    /** Default SSH port — matches OpenSSH. */
    public static final int DEFAULT_PORT = 22;

    /** @return a copy of this host with a new label and a bumped {@code updatedAt}. */
    public SshHost withLabel(@NotNull String newLabel) {
        return new SshHost(id, newLabel, host, port, username, auth, createdAt, Instant.now());
    }

    /** @return a copy of this host with a new auth mode and a bumped {@code updatedAt}. */
    public SshHost withAuth(@NotNull SshAuth newAuth) {
        return new SshHost(id, label, host, port, username, newAuth, createdAt, Instant.now());
    }

    /** @return a copy with every editable field replaced. Used by the edit dialog's Save path. */
    public SshHost withEdited(
        @NotNull String newLabel,
        @NotNull String newHost,
        int newPort,
        @NotNull String newUsername,
        @NotNull SshAuth newAuth
    ) {
        return new SshHost(id, newLabel, newHost, newPort, newUsername, newAuth, createdAt, Instant.now());
    }

    /** Factory for brand-new hosts. */
    public static @NotNull SshHost create(
        @NotNull String label,
        @NotNull String host,
        int port,
        @NotNull String username,
        @NotNull SshAuth auth
    ) {
        Instant now = Instant.now();
        return new SshHost(UUID.randomUUID(), label, host, port, username, auth, now, now);
    }
}
