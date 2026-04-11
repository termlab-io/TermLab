package com.conch.vault.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.vault.model.AuthMethod;
import com.conch.vault.model.VaultAccount;
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
 * unlocked Conch process can still recover cleartext. Post-v1 hardening
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
        };
    }
}
