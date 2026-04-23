package com.termlab.ssh.credentials;

import com.termlab.sdk.CredentialProvider;
import com.termlab.sdk.CredentialProvider.Credential;
import com.termlab.ssh.client.SshResolvedCredential;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Maps a vault credential id to a short-lived {@link SshResolvedCredential}
 * by walking the registered {@link CredentialProvider} extensions.
 *
 * <p>The caller decides the policy (which credential id to look up, what
 * to do on miss) — this class is a pure dispatcher. When the returned
 * credential is a standalone key that has no username of its own,
 * {@code fallbackUsername} is substituted.
 *
 * <p>Two constructors: a zero-arg one that discovers providers via
 * the IntelliJ extension area (production path), and one that accepts an
 * explicit list (tests).
 */
public final class SshCredentialResolver {

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.termlab.core.credentialProvider");

    private final ProviderLookup providerLookup;

    /** Production constructor — resolves providers via the IntelliJ EP at call time. */
    public SshCredentialResolver() {
        this.providerLookup = () -> {
            if (ApplicationManager.getApplication() == null) {
                return List.of();
            }
            return EP_NAME.getExtensionList();
        };
    }

    /** Test constructor — uses an explicit provider list. */
    public SshCredentialResolver(@NotNull List<? extends CredentialProvider> providers) {
        this.providerLookup = () -> List.copyOf(providers);
    }

    /**
     * Resolve {@code credentialId} through any registered provider and
     * copy the result into a fresh {@link SshResolvedCredential} the
     * caller owns and must {@code close()}.
     *
     * @param credentialId     the saved credential's id
     * @param fallbackUsername used when the resolved credential is a
     *                         standalone key with no username of its own
     * @return the resolved credential, or {@code null} if no provider
     *         knows this id (locked vault, deleted entry)
     */
    public @Nullable SshResolvedCredential resolve(
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
     * Convert an SDK-level {@link Credential} into an
     * {@link SshResolvedCredential}, copying char-array fields so the
     * caller can freely call {@link Credential#destroy()} afterwards.
     *
     * <p>Visible for {@link SshCredentialPicker} to reuse.
     */
    static @Nullable SshResolvedCredential convert(
        @NotNull Credential sdkCredential,
        @NotNull String fallbackUsername
    ) {
        String username = sdkCredential.username();
        if (username == null || username.isEmpty()) {
            username = fallbackUsername;
        }

        return switch (sdkCredential.authMethod()) {
            case PASSWORD -> {
                char[] pw = sdkCredential.password();
                if (pw == null) yield null;
                yield SshResolvedCredential.password(username, pw.clone());
            }
            case KEY -> {
                if (sdkCredential.keyPath() == null) yield null;
                char[] passphrase = sdkCredential.keyPassphrase();
                yield SshResolvedCredential.key(
                    username,
                    Path.of(sdkCredential.keyPath()),
                    passphrase == null ? null : passphrase.clone()
                );
            }
            case KEY_AND_PASSWORD -> {
                if (sdkCredential.keyPath() == null) yield null;
                char[] pw = sdkCredential.password();
                if (pw == null) yield null;
                char[] passphrase = sdkCredential.keyPassphrase();
                yield SshResolvedCredential.keyAndPassword(
                    username,
                    Path.of(sdkCredential.keyPath()),
                    passphrase == null ? null : passphrase.clone(),
                    pw.clone()
                );
            }
            case API_TOKEN -> null;
            case SECURE_NOTE -> null;
        };
    }

    @FunctionalInterface
    private interface ProviderLookup {
        @NotNull List<? extends CredentialProvider> get();
    }
}
