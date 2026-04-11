package com.conch.ssh.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.sdk.CredentialProvider.Credential;
import com.conch.ssh.client.SshResolvedCredential;
import com.conch.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Maps an {@link SshHost} to a short-lived {@link SshResolvedCredential}
 * by walking the registered {@link CredentialProvider} extensions and
 * looking up the host's {@link SshHost#credentialId}.
 *
 * <p>The lookup is by-id only. If the host has no credential id, or the
 * credential was deleted from the vault, {@link #resolve(SshHost)}
 * returns {@code null} and the caller runs a picker instead (see
 * {@link SshCredentialPicker}).
 *
 * <p>Username handling: SSH credentials of kind {@code SSH_KEY} come
 * back from the vault with {@code username = null} — standalone keys
 * aren't bound to a user. In that case the resolver substitutes
 * {@link SshHost#username()}.
 *
 * <p>Two constructors: a zero-arg one that discovers providers via
 * the IntelliJ extension-area lookup (production path), and one that
 * accepts an explicit list (test path — lets unit tests inject a fake
 * {@code CredentialProvider} without booting the platform).
 */
public final class SshCredentialResolver {

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.conch.core.credentialProvider");

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
     * Try to resolve the host's saved credential from any registered
     * provider.
     *
     * <p>Callers own the returned {@link SshResolvedCredential} and MUST
     * close it when done. The resolver also calls {@link Credential#destroy()}
     * on the original SDK-level credential before returning — all sensitive
     * material after this point lives inside the returned
     * {@code SshResolvedCredential} and gets zeroed on its close.
     *
     * @return a fresh {@link SshResolvedCredential}, or {@code null} if:
     *         <ul>
     *           <li>the host has no saved credential id, or</li>
     *           <li>no provider knows the credential (vault locked, deleted), or</li>
     *           <li>the returned credential doesn't carry enough material
     *               for any supported auth mode (shouldn't happen in
     *               practice)</li>
     *         </ul>
     */
    public @Nullable SshResolvedCredential resolve(@NotNull SshHost host) {
        UUID credentialId = host.credentialId();
        if (credentialId == null) return null;

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
            return convert(sdkCredential, host.username());
        } finally {
            sdkCredential.destroy();
        }
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
        };
    }

    @FunctionalInterface
    private interface ProviderLookup {
        @NotNull List<? extends CredentialProvider> get();
    }
}
