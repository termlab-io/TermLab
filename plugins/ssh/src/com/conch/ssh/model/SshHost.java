package com.conch.ssh.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved SSH host.
 *
 * <p>Hostnames and ports aren't secrets — they live in plaintext under
 * {@code ~/.config/conch/ssh-hosts.json}, the same treatment
 * {@code ~/.ssh/config} already gets. The {@link #credentialId} points at
 * a {@code CredentialProvider.CredentialDescriptor} in the vault; actual
 * auth material is only fetched at connect time via
 * {@code CredentialProvider.getCredential(id)} and never cached outside
 * the short-lived resolved-credential window.
 *
 * @param id           stable UUID, survives renames
 * @param label        user-facing name ("prod-db-primary")
 * @param host         hostname or IP
 * @param port         SSH port, usually 22
 * @param username     default username for this host. When the referenced
 *                     credential is a standalone SSH key (kind
 *                     {@code SSH_KEY}) the resolved credential has no
 *                     username of its own, so the connector falls back to
 *                     this field.
 * @param credentialId vault credential id. {@code null} means "no saved
 *                     credential" — connecting always runs the vault
 *                     picker first.
 * @param createdAt    when the host entry was created
 * @param updatedAt    when the host entry was last edited
 */
public record SshHost(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull String host,
    int port,
    @NotNull String username,
    @Nullable UUID credentialId,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) {
    /** Default SSH port — matches OpenSSH. */
    public static final int DEFAULT_PORT = 22;

    /** @return a copy of this host with a new label and a bumped {@code updatedAt}. */
    public SshHost withLabel(@NotNull String newLabel) {
        return new SshHost(id, newLabel, host, port, username, credentialId, createdAt, Instant.now());
    }

    /** @return a copy of this host with a new credential id and a bumped {@code updatedAt}. */
    public SshHost withCredentialId(@Nullable UUID newCredentialId) {
        return new SshHost(id, label, host, port, username, newCredentialId, createdAt, Instant.now());
    }

    /** @return a copy with every editable field replaced. Used by the edit dialog's Save path. */
    public SshHost withEdited(
        @NotNull String newLabel,
        @NotNull String newHost,
        int newPort,
        @NotNull String newUsername,
        @Nullable UUID newCredentialId
    ) {
        return new SshHost(id, newLabel, newHost, newPort, newUsername, newCredentialId, createdAt, Instant.now());
    }

    /** Factory for brand-new hosts. */
    public static @NotNull SshHost create(
        @NotNull String label,
        @NotNull String host,
        int port,
        @NotNull String username,
        @Nullable UUID credentialId
    ) {
        Instant now = Instant.now();
        return new SshHost(UUID.randomUUID(), label, host, port, username, credentialId, now, now);
    }
}
