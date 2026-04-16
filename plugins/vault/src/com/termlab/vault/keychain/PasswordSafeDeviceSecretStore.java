package com.termlab.vault.keychain;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;

/**
 * Production {@link DeviceSecretStore} backed by IntelliJ's
 * {@link PasswordSafe}.
 *
 * <p>PasswordSafe is IntelliJ's cross-platform credential store — it routes
 * to:
 * <ul>
 *   <li>macOS Keychain (via the native Security framework)</li>
 *   <li>Windows Credential Manager (via Advapi32)</li>
 *   <li>Linux: KWallet, GNOME Keyring / libsecret, or an encrypted KeePass
 *       file when no desktop keychain is present (headless servers, etc.)</li>
 * </ul>
 *
 * <p>Because PasswordSafe's API is String-oriented, we base64-encode the
 * raw 32-byte device secret. The net effect is that the vault's key
 * derivation ends up reading the same 32 bytes it originally wrote, on any
 * platform the IntelliJ Platform supports.
 *
 * <p>The service name is {@code "Credential Vault"} and the user name is
 * {@code "device-secret"}, matching the convention recommended by the
 * IntelliJ Platform docs (human-readable prefix + key).
 */
public final class PasswordSafeDeviceSecretStore implements DeviceSecretStore {

    private static final String SERVICE_NAME = "Credential Vault";
    private static final String KEY = "device-secret";

    private static CredentialAttributes attributes() {
        return new CredentialAttributes(SERVICE_NAME, KEY);
    }

    @Override
    public byte @Nullable [] read() {
        Credentials creds = PasswordSafe.getInstance().get(attributes());
        if (creds == null) return null;
        String encoded = creds.getPasswordAsString();
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalidEncoding) {
            // Corrupted entry — treat as absent so getOrCreate() can recover.
            return null;
        }
    }

    @Override
    public void write(byte @NotNull [] secret) {
        if (secret.length != 32) {
            throw new IllegalArgumentException("device secret must be 32 bytes, got " + secret.length);
        }
        String encoded = Base64.getEncoder().encodeToString(secret);
        PasswordSafe.getInstance().set(attributes(), new Credentials(KEY, encoded));
    }

    @Override
    public void delete() {
        PasswordSafe.getInstance().set(attributes(), null);
    }
}
