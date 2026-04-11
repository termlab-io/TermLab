package com.conch.ssh.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Reference to a credential stored by a {@code CredentialProvider}
 * extension (the vault plugin, in practice).
 *
 * @param credentialId the saved credential's UUID, or {@code null} to
 *                     mean "no saved entry — run the vault picker at
 *                     connect time". The null case keeps
 *                     {@code HostEditDialog}'s "&lt;no credential&gt;"
 *                     combo option representable without a fourth
 *                     {@link SshAuth} variant.
 */
public record VaultAuth(@Nullable UUID credentialId) implements SshAuth {
}
