package com.termlab.ssh.model;

import org.jetbrains.annotations.NotNull;

/**
 * Authenticate with a private key file on disk. The path is persisted
 * (it's not a secret — same treatment {@code ~/.ssh/config} gives
 * {@code IdentityFile}); the passphrase is prompted every connect via
 * {@code InlineCredentialPromptDialog.promptPassphrase} and never saved.
 *
 * @param keyFilePath absolute path to the private key. Validated by
 *                    {@code HostEditDialog.doValidate} at save time.
 */
public record KeyFileAuth(@NotNull String keyFilePath) implements SshAuth {
}
