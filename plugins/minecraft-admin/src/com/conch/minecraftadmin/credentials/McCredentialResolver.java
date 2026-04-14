package com.conch.minecraftadmin.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.sdk.CredentialProvider.Credential;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Maps a vault credential id to a short-lived {@link McCredential} by walking
 * the registered {@link CredentialProvider} extensions.
 *
 * <p>Only PASSWORD-type credentials are supported (AMP and RCON have no key
 * surface). If the resolved credential carries no password (e.g. a key-only
 * entry that somehow ended up referenced), {@code resolve} returns {@code null}.
 *
 * <p>Two constructors: a zero-arg one that discovers providers via the
 * IntelliJ extension area (production path), and one that accepts an explicit
 * list (tests).
 */
public final class McCredentialResolver {

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.conch.core.credentialProvider");

    private final ProviderLookup providerLookup;

    /** Production constructor — resolves providers via the IntelliJ EP at call time. */
    public McCredentialResolver() {
        this.providerLookup = () -> {
            if (ApplicationManager.getApplication() == null) {
                return List.of();
            }
            return EP_NAME.getExtensionList();
        };
    }

    /** Test constructor — uses an explicit provider list. */
    public McCredentialResolver(@NotNull List<? extends CredentialProvider> providers) {
        this.providerLookup = () -> List.copyOf(providers);
    }

    /**
     * Resolve {@code credentialId} through any registered provider and return
     * a fresh {@link McCredential} the caller owns and must {@code close()}.
     *
     * @param credentialId    the saved credential's id
     * @param fallbackUsername substituted when the resolved credential has no username
     * @return the resolved credential, or {@code null} if no provider knows this id,
     *         the application is not yet initialised, or the credential carries no password
     */
    public @Nullable McCredential resolve(
        @NotNull UUID credentialId,
        @NotNull String fallbackUsername
    ) {
        Credential sdkCredential = null;
        for (CredentialProvider provider : providerLookup.get()) {
            if (!provider.isAvailable()) continue;
            Credential candidate = provider.getCredential(credentialId);
            if (candidate != null) {
                sdkCredential = candidate;
                break;
            }
        }
        if (sdkCredential == null) return null;

        try {
            return convert(sdkCredential, fallbackUsername);
        } finally {
            sdkCredential.destroy();
        }
    }

    /**
     * Ask every registered provider to become available (the vault
     * provider pops its unlock dialog). Returns {@code true} if at
     * least one provider reports {@link CredentialProvider#isAvailable()}
     * after the attempt. Callers that already know a specific
     * {@code credentialId} should use this to retry
     * {@link #resolve(UUID, String)} after an initial miss, instead
     * of falling back to an account picker the user never asked for.
     *
     * <p>Must be called on the EDT.
     */
    public boolean ensureAnyProviderAvailable() {
        boolean anyAvailable = false;
        for (CredentialProvider provider : providerLookup.get()) {
            if (provider.ensureAvailable()) {
                anyAvailable = true;
            }
        }
        return anyAvailable;
    }

    /**
     * Convert an SDK-level {@link Credential} into an {@link McCredential},
     * cloning the password char array so the caller's {@link Credential#destroy()}
     * does not zero our copy.
     */
    private static @Nullable McCredential convert(
        @NotNull Credential sdkCredential,
        @NotNull String fallbackUsername
    ) {
        char[] pw = sdkCredential.password();
        if (pw == null) {
            // Key-only credential — not usable for AMP/RCON.
            return null;
        }

        String username = sdkCredential.username();
        if (username == null || username.isBlank()) {
            username = fallbackUsername;
        }

        return new McCredential(username, pw.clone());
    }

    @FunctionalInterface
    private interface ProviderLookup {
        @NotNull List<? extends CredentialProvider> get();
    }
}
