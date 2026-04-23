package com.termlab.vault.credentials;

import com.termlab.sdk.CredentialProvider;
import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.VaultAccount;
import com.termlab.vault.model.VaultKey;
import org.jetbrains.annotations.NotNull;

/**
 * Boundary converter from the vault's internal {@link AuthMethod} sealed
 * interface (String-backed, Gson-friendly) to the SDK's flat
 * {@link CredentialProvider.Credential} record (enum tag + {@code char[]}
 * sensitive fields that consumers can zero via {@link
 * CredentialProvider.Credential#destroy()}).
 *
 * <p>This is the <b>only</b> place that performs the String → char[] copy.
 * The copy itself is the known v1 weakness tracked in the vault plan
 * ({@code docs/plans/2026-04-10-vault-plugin.md}, Risks #7): the internal
 * String form can't be zeroed, so an attacker with memory access to an
 * unlocked TermLab process can still recover cleartext. Post-v1 hardening
 * will move the internal model to {@code char[]} via a custom Gson
 * {@code TypeAdapter} — out of scope here.
 */
public final class AuthMethodMapper {

    private AuthMethodMapper() {}

    public static @NotNull CredentialProvider.Credential toCredential(@NotNull VaultAccount account) {
        AuthMethod auth = account.auth();
        return switch (auth) {
            case AuthMethod.Password p -> new CredentialProvider.Credential(
                account.id(),
                account.displayName(),
                account.username(),
                CredentialProvider.AuthMethod.PASSWORD,
                p.password().toCharArray(),
                null,
                null
            );
            case AuthMethod.Key k -> new CredentialProvider.Credential(
                account.id(),
                account.displayName(),
                account.username(),
                CredentialProvider.AuthMethod.KEY,
                null,
                k.keyPath(),
                k.passphrase() == null ? null : k.passphrase().toCharArray()
            );
            case AuthMethod.KeyAndPassword kp -> new CredentialProvider.Credential(
                account.id(),
                account.displayName(),
                account.username(),
                CredentialProvider.AuthMethod.KEY_AND_PASSWORD,
                kp.password().toCharArray(),
                kp.keyPath(),
                kp.passphrase() == null ? null : kp.passphrase().toCharArray()
            );
            case AuthMethod.ApiToken token -> new CredentialProvider.Credential(
                account.id(),
                account.displayName(),
                account.username(),
                CredentialProvider.AuthMethod.API_TOKEN,
                token.token().toCharArray(),
                null,
                null
            );
            case AuthMethod.SecureNote note -> new CredentialProvider.Credential(
                account.id(),
                account.displayName(),
                null,
                CredentialProvider.AuthMethod.SECURE_NOTE,
                note.note().toCharArray(),
                null,
                null
            );
        };
    }

    /**
     * Map a standalone {@link VaultKey} to an SDK {@link CredentialProvider.Credential}.
     *
     * <p>Unlike accounts, vault keys don't carry a username — the field is
     * {@code null}, and the SDK contract says the consumer (SSH plugin) is
     * responsible for prompting the user for one before using the key.
     * The auth method is always {@link CredentialProvider.AuthMethod#KEY}.
     */
    public static @NotNull CredentialProvider.Credential toCredential(@NotNull VaultKey key) {
        return new CredentialProvider.Credential(
            key.id(),
            key.name(),
            null,                                          // username — consumer prompts
            CredentialProvider.AuthMethod.KEY,
            null,                                          // no password for a bare key
            key.privatePath(),
            null                                           // passphrase not stored yet
        );
    }

    /** Compact view used by {@link CredentialProvider#listCredentials()}. */
    public static @NotNull CredentialProvider.CredentialDescriptor toDescriptor(@NotNull VaultAccount account) {
        CredentialProvider.Kind kind = switch (account.auth()) {
            case AuthMethod.Password ignored -> CredentialProvider.Kind.ACCOUNT_PASSWORD;
            case AuthMethod.Key ignored -> CredentialProvider.Kind.ACCOUNT_KEY;
            case AuthMethod.KeyAndPassword ignored -> CredentialProvider.Kind.ACCOUNT_KEY_AND_PASSWORD;
            case AuthMethod.ApiToken ignored -> CredentialProvider.Kind.API_TOKEN;
            case AuthMethod.SecureNote ignored -> CredentialProvider.Kind.SECURE_NOTE;
        };
        return new CredentialProvider.CredentialDescriptor(
            account.id(),
            account.displayName(),
            descriptorSubtitle(account),
            kind
        );
    }

    private static @NotNull String descriptorSubtitle(@NotNull VaultAccount account) {
        return switch (account.auth()) {
            case AuthMethod.ApiToken ignored -> account.username();
            case AuthMethod.SecureNote ignored -> "Secure note";
            default -> account.username();
        };
    }

    /** Compact view used by {@link CredentialProvider#listCredentials()}. */
    public static @NotNull CredentialProvider.CredentialDescriptor toDescriptor(@NotNull VaultKey key) {
        return new CredentialProvider.CredentialDescriptor(
            key.id(),
            key.name(),
            key.algorithm() + " · " + key.fingerprint(),
            CredentialProvider.Kind.SSH_KEY
        );
    }
}
