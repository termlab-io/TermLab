package com.conch.sdk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Extension point for plugins that supply credentials.
 * The vault plugin implements this; SSH plugin consumes it.
 * Future: 1Password, HashiCorp Vault integrations.
 */
public interface CredentialProvider {

    /** Human-readable name of this provider (e.g., "Conch Vault"). */
    @NotNull String getDisplayName();

    /** Whether the credential store is currently unlocked and available. */
    boolean isAvailable();

    /**
     * Retrieve credentials for a specific account.
     * Returns null if the account is not found or the store is locked.
     *
     * @param accountId the UUID of the credential account
     * @return credentials, or null
     */
    @Nullable Credential getCredential(@NotNull UUID accountId);

    /**
     * Prompt the user to select a credential account.
     * Shows a picker UI. Returns null if cancelled.
     *
     * @return selected credentials, or null
     */
    @Nullable Credential promptForCredential();

    /** Credential data. Consumers must zero the char arrays after use. */
    record Credential(
        @NotNull UUID accountId,
        @NotNull String displayName,
        @NotNull String username,
        @NotNull AuthMethod authMethod,
        char @Nullable [] password,
        @Nullable String keyPath,
        char @Nullable [] keyPassphrase
    ) {
        /** Zero all sensitive fields. Call this when done with the credential. */
        public void destroy() {
            if (password != null) java.util.Arrays.fill(password, '\0');
            if (keyPassphrase != null) java.util.Arrays.fill(keyPassphrase, '\0');
        }
    }

    enum AuthMethod { PASSWORD, KEY, KEY_AND_PASSWORD }
}
