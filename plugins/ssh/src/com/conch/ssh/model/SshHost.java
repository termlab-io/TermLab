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
 * @param proxyCommand optional proxy command (for example
 *                   {@code ssh -W %h:%p bastion}) used to reach the target
 * @param proxyJump  optional proxy jump host spec (for example
 *                   {@code deploy@bastion:2222}) used to reach the target
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
    @Nullable String proxyCommand,
    @Nullable String proxyJump,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) {
    /** Default SSH port — matches OpenSSH. */
    public static final int DEFAULT_PORT = 22;

    public SshHost {
        proxyCommand = trimToNull(proxyCommand);
        proxyJump = trimToNull(proxyJump);
    }

    /** @return a copy of this host with a new label and a bumped {@code updatedAt}. */
    public SshHost withLabel(@NotNull String newLabel) {
        return new SshHost(id, newLabel, host, port, username, auth, proxyCommand, proxyJump, createdAt, Instant.now());
    }

    /** @return a copy of this host with a new auth mode and a bumped {@code updatedAt}. */
    public SshHost withAuth(@NotNull SshAuth newAuth) {
        return new SshHost(id, label, host, port, username, newAuth, proxyCommand, proxyJump, createdAt, Instant.now());
    }

    /** @return a copy with every editable field replaced. Used by the edit dialog's Save path. */
    public SshHost withEdited(
        @NotNull String newLabel,
        @NotNull String newHost,
        int newPort,
        @NotNull String newUsername,
        @NotNull SshAuth newAuth
    ) {
        return withEdited(newLabel, newHost, newPort, newUsername, newAuth, proxyCommand, proxyJump);
    }

    /** @return a copy with every editable field replaced, including proxy settings. */
    public SshHost withEdited(
        @NotNull String newLabel,
        @NotNull String newHost,
        int newPort,
        @NotNull String newUsername,
        @NotNull SshAuth newAuth,
        @Nullable String newProxyCommand,
        @Nullable String newProxyJump
    ) {
        return new SshHost(
            id, newLabel, newHost, newPort, newUsername, newAuth,
            newProxyCommand, newProxyJump, createdAt, Instant.now());
    }

    /** Factory for brand-new hosts. */
    public static @NotNull SshHost create(
        @NotNull String label,
        @NotNull String host,
        int port,
        @NotNull String username,
        @NotNull SshAuth auth
    ) {
        return create(label, host, port, username, auth, null, null);
    }

    /** Factory for brand-new hosts with proxy settings. */
    public static @NotNull SshHost create(
        @NotNull String label,
        @NotNull String host,
        int port,
        @NotNull String username,
        @NotNull SshAuth auth,
        @Nullable String proxyCommand,
        @Nullable String proxyJump
    ) {
        Instant now = Instant.now();
        return new SshHost(UUID.randomUUID(), label, host, port, username, auth, proxyCommand, proxyJump, now, now);
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
