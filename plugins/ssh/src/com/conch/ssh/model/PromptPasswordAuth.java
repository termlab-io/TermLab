package com.conch.ssh.model;

/**
 * Prompt the user for a password at every connect. The host carries no
 * credential material — only the fact that this is the chosen mode.
 * {@code InlineCredentialPromptDialog.promptPassword} is the connect-time
 * entry point.
 */
public record PromptPasswordAuth() implements SshAuth {
}
