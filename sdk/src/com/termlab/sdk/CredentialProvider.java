package com.termlab.sdk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Extension point for plugins that supply credentials.
 * The vault plugin implements this; the SSH plugin consumes it.
 * Future: 1Password, HashiCorp Vault integrations.
 *
 * <p>A "credential" in this interface covers two kinds of thing:
 * <ul>
 *   <li><b>Accounts</b> — username + password, username + key, or both.
 *       A consumer plugin can use an account as-is to authenticate.</li>
 *   <li><b>Standalone SSH keys</b> — a key pair not bound to any
 *       particular username. The consumer prompts the user for a
 *       username separately, then uses the key for auth.</li>
 * </ul>
 *
 * <p>Enumerate everything with {@link #listCredentials()}, then look up
 * the full credential data by id via {@link #getCredential(UUID)}. The
 * descriptor list is safe to hold long-term (no secrets) — the actual
 * credential should be fetched immediately before use and
 * {@link Credential#destroy() destroyed} immediately after.
 */
public interface CredentialProvider {

    /** Human-readable name of this provider (e.g., "Credential Vault"). */
    @NotNull String getDisplayName();

    /** Whether the credential store is currently unlocked and available. */
    boolean isAvailable();

    /**
     * Best-effort "make the store usable" without involving a credential
     * picker. For stores that can be locked (the credential vault), this
     * shows the unlock dialog when the store is locked; the user enters
     * the master password and the store becomes available. For stores
     * that are always available, the default implementation is a no-op.
     *
     * <p>Return value reflects {@link #isAvailable()} after the attempt.
     * Callers should invoke this when they already know the
     * {@code credentialId} they want to fetch but a prior
     * {@link #getCredential(UUID)} returned {@code null} — this lets
     * them retry the same id rather than fall back to
     * {@link #promptForCredential()}, which would pop a picker the
     * user never asked for.
     *
     * <p>Must be called on the EDT.
     */
    default boolean ensureAvailable() {
        return isAvailable();
    }

    /**
     * Lightweight enumeration of every credential the provider knows about.
     * Returned descriptors contain no secrets — they're safe to show in a
     * picker dropdown or search result list.
     *
     * <p>Returns an empty list when the store is locked.
     */
    @NotNull List<CredentialDescriptor> listCredentials();

    /**
     * Retrieve a credential by id.
     *
     * <p>For descriptors of kind {@link Kind#ACCOUNT_PASSWORD},
     * {@link Kind#ACCOUNT_KEY}, {@link Kind#ACCOUNT_KEY_AND_PASSWORD}:
     * returns a {@link Credential} with {@code username} set and
     * {@code authMethod} matching the kind.
     *
     * <p>For descriptors of kind {@link Kind#API_TOKEN}: returns a
     * {@link Credential} with {@code username} set to the token identifier
     * (for example {@code user@realm!tokenid}) and {@code password} set to
     * the token secret.
     *
     * <p>For descriptors of kind {@link Kind#SSH_KEY}: returns a
     * {@link Credential} with {@code authMethod=KEY}, {@code username=null}
     * (the consumer should prompt for it), {@code keyPath} set, and
     * {@code keyPassphrase} optional.
     *
     * @return credential, or {@code null} if not found or store locked
     */
    @Nullable Credential getCredential(@NotNull UUID credentialId);

    /**
     * Prompt the user to select a credential. Shows a picker UI.
     * Returns {@code null} if cancelled.
     */
    @Nullable Credential promptForCredential();

    /**
     * Lightweight view of a credential for listing and picking. Contains
     * no secrets — only what a UI needs to label the entry.
     *
     * @param id          stable identifier; pass back to {@link #getCredential(UUID)}
     * @param displayName primary label (account name or key name)
     * @param subtitle    secondary label — username for accounts, fingerprint for keys
     * @param kind        what kind of credential this is
     */
    record CredentialDescriptor(
        @NotNull UUID id,
        @NotNull String displayName,
        @NotNull String subtitle,
        @NotNull Kind kind
    ) {}

    /**
     * Category of a credential.
     *
     * <ul>
     *   <li>{@link #ACCOUNT_PASSWORD} — username + password</li>
     *   <li>{@link #ACCOUNT_KEY} — username + SSH key (referenced by path)</li>
     *   <li>{@link #ACCOUNT_KEY_AND_PASSWORD} — username + key + password</li>
     *   <li>{@link #API_TOKEN} — token identifier + secret</li>
     *   <li>{@link #SECURE_NOTE} — encrypted note text</li>
     *   <li>{@link #SSH_KEY} — an SSH key with no associated username;
     *       the consumer prompts the user for a username at use time.</li>
     * </ul>
     */
    enum Kind {
        ACCOUNT_PASSWORD,
        ACCOUNT_KEY,
        ACCOUNT_KEY_AND_PASSWORD,
        API_TOKEN,
        SECURE_NOTE,
        SSH_KEY
    }

    /**
     * Full credential data. Consumers must {@link #destroy()} the
     * {@code char[]} fields after use.
     *
     * <p>{@code username} is {@code null} when the credential is a standalone
     * {@link Kind#SSH_KEY} that the consumer should prompt for separately.
     */
    record Credential(
        @NotNull UUID accountId,
        @NotNull String displayName,
        @Nullable String username,
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

    enum AuthMethod { PASSWORD, KEY, KEY_AND_PASSWORD, API_TOKEN, SECURE_NOTE }
}
