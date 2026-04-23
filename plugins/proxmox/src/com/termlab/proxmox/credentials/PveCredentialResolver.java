package com.termlab.proxmox.credentials;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.termlab.sdk.CredentialProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public final class PveCredentialResolver {
    private static final Logger LOG = Logger.getInstance(PveCredentialResolver.class);

    private static final ExtensionPointName<CredentialProvider> EP_NAME =
        ExtensionPointName.create("com.termlab.core.credentialProvider");

    private final ProviderLookup providerLookup;

    public PveCredentialResolver() {
        this.providerLookup = () -> {
            if (ApplicationManager.getApplication() == null) return List.of();
            return EP_NAME.getExtensionList();
        };
    }

    public PveCredentialResolver(@NotNull List<? extends CredentialProvider> providers) {
        this.providerLookup = () -> List.copyOf(providers);
    }

    public @Nullable PveApiToken resolve(@NotNull UUID credentialId) {
        CredentialProvider.Credential sdkCredential = null;
        for (CredentialProvider provider : providerLookup.get()) {
            if (!provider.isAvailable()) {
                LOG.info("TermLab Proxmox: credential provider '" + provider.getDisplayName()
                    + "' is not available while resolving " + credentialId);
                continue;
            }
            CredentialProvider.Credential candidate = provider.getCredential(credentialId);
            if (candidate != null) {
                LOG.info("TermLab Proxmox: credential " + credentialId + " resolved from '"
                    + provider.getDisplayName() + "' as " + candidate.authMethod()
                    + " usernamePresent=" + (candidate.username() != null && !candidate.username().isBlank())
                    + " secretPresent=" + (candidate.password() != null && candidate.password().length > 0));
                sdkCredential = candidate;
                break;
            }
        }
        if (sdkCredential == null) {
            LOG.warn("TermLab Proxmox: credential " + credentialId + " was not found in available providers");
            return null;
        }
        try {
            PveApiToken token = convert(sdkCredential);
            if (token == null) {
                LOG.warn("TermLab Proxmox: credential " + credentialId
                    + " is not a usable Proxmox API token");
            }
            return token;
        } finally {
            sdkCredential.destroy();
        }
    }

    public boolean ensureAnyProviderAvailable() {
        boolean anyAvailable = false;
        for (CredentialProvider provider : providerLookup.get()) {
            boolean available = provider.ensureAvailable();
            LOG.info("TermLab Proxmox: ensureAvailable for credential provider '"
                + provider.getDisplayName() + "' -> " + available);
            if (available) anyAvailable = true;
        }
        return anyAvailable;
    }

    public @NotNull List<CredentialProvider.CredentialDescriptor> listUsableDescriptors() {
        return providerLookup.get().stream()
            .filter(CredentialProvider::isAvailable)
            .flatMap(provider -> provider.listCredentials().stream())
            .filter(descriptor -> descriptor.kind() == CredentialProvider.Kind.API_TOKEN)
            .sorted((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()))
            .toList();
    }

    static @Nullable PveApiToken convert(@NotNull CredentialProvider.Credential sdkCredential) {
        String tokenId = sdkCredential.username();
        char[] secret = sdkCredential.password();
        if (sdkCredential.authMethod() != CredentialProvider.AuthMethod.API_TOKEN) return null;
        if (tokenId == null || tokenId.isBlank() || secret == null || secret.length == 0) return null;
        return new PveApiToken(tokenId, secret.clone());
    }

    @FunctionalInterface
    private interface ProviderLookup {
        @NotNull List<? extends CredentialProvider> get();
    }
}
