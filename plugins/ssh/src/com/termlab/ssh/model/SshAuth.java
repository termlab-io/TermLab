package com.termlab.ssh.model;

/**
 * How an {@link SshHost} authenticates at connect time.
 *
 * <p>Three variants, each implementing this sealed interface:
 * <ul>
 *   <li>{@link VaultAuth} — the host points at a saved
 *       {@code CredentialProvider.CredentialDescriptor}. A null
 *       credential id means "no saved entry — run the vault picker at
 *       connect time" and keeps the existing
 *       "no credential" fallback in {@code HostEditDialog} working
 *       without a fourth variant.</li>
 *   <li>{@link PromptPasswordAuth} — the host never persists a password;
 *       the user types one into {@code InlineCredentialPromptDialog}
 *       every connect.</li>
 *   <li>{@link KeyFileAuth} — the host persists an SSH private key
 *       <em>path</em> (not the key, not the passphrase), same treatment
 *       {@code ~/.ssh/config}'s {@code IdentityFile} gets. Each connect
 *       reads the key from disk and prompts for an optional passphrase.</li>
 * </ul>
 *
 * <p>JSON: a single discriminator field {@code "type"} selects the
 * variant — {@code "vault"}, {@code "prompt_password"}, or
 * {@code "key_file"}. Serialization is handled by {@code SshGson}, not
 * by field reflection.
 */
public sealed interface SshAuth permits VaultAuth, PromptPasswordAuth, KeyFileAuth {
}
