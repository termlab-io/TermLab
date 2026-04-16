package com.termlab.ssh.credentials;

import com.termlab.sdk.CredentialProvider;
import com.termlab.sdk.CredentialProvider.Credential;
import com.termlab.ssh.client.SshResolvedCredential;
import com.termlab.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Delegates to {@link CredentialProvider#promptForCredential()} to let
 * the user pick a credential interactively, then massages the result
 * into an {@link SshResolvedCredential} the SSH client can actually use.
 *
 * <p>Called when:
 * <ul>
 *   <li>The host has no saved credential id at all, or</li>
 *   <li>{@link SshCredentialResolver#resolve(SshHost)} returned null
 *       because the saved id no longer resolves (vault locked / entry
 *       deleted), or</li>
 *   <li>A prior connect attempt came back with
 *       {@code AUTH_FAILED} — the user gets a chance to pick a different
 *       credential without closing and re-opening the tab.</li>
 * </ul>
 *
 * <p>Returns {@code null} when the user cancels the picker or when no
 * provider is available.
 */
public final class SshCredentialPicker {

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.termlab.core.credentialProvider");

    private final ProviderLookup providerLookup;

    /** Production constructor — resolves providers via the IntelliJ EP at call time. */
    public SshCredentialPicker() {
        this.providerLookup = () -> {
            if (ApplicationManager.getApplication() == null) {
                return List.of();
            }
            return EP_NAME.getExtensionList();
        };
    }

    /** Test constructor — uses an explicit provider list. */
    public SshCredentialPicker(@NotNull List<? extends CredentialProvider> providers) {
        this.providerLookup = () -> List.copyOf(providers);
    }

    /**
     * Show the credential picker and return a resolved credential.
     *
     * @param host the host being connected to — used only as the
     *             fallback username when the picked credential is a
     *             standalone SSH key
     * @return a fresh {@link SshResolvedCredential} the caller owns and
     *         must close, or {@code null} if cancelled / unavailable
     */
    public @Nullable SshResolvedCredential pick(@NotNull SshHost host) {
        for (CredentialProvider provider : providerLookup.get()) {
            Credential sdkCredential = provider.promptForCredential();
            if (sdkCredential == null) continue;
            try {
                return SshCredentialResolver.convert(sdkCredential, host.username());
            } finally {
                sdkCredential.destroy();
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ProviderLookup {
        @NotNull List<? extends CredentialProvider> get();
    }
}
