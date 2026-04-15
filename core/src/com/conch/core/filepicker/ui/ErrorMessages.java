package com.conch.core.filepicker.ui;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Translates common IOException messages into friendly sentences
 * for display in the unified file picker's error card.
 */
public final class ErrorMessages {

    private ErrorMessages() {}

    public static @NotNull String translate(@NotNull IOException e) {
        String raw = e.getMessage();
        if (raw == null) return "Error: " + e.getClass().getSimpleName();
        String msg = raw.toLowerCase();
        if (msg.contains("auth fail"))
            return "Permission denied. Check credentials in the vault.";
        if (msg.contains("permission denied"))
            return "Permission denied on the remote. Check folder permissions.";
        if (msg.contains("connection refused"))
            return "The host refused the connection. Is SSH running on the expected port?";
        if (msg.contains("unknown host") || msg.contains("no route to host"))
            return "Could not reach the host. Check the hostname and network connection.";
        if (msg.contains("timed out"))
            return "The connection timed out.";
        if (msg.contains("no such file") || msg.contains("not found"))
            return "File or directory not found.";
        return "Error: " + raw;
    }
}
