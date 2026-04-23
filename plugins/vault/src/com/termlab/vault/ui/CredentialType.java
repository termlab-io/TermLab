package com.termlab.vault.ui;

import com.termlab.vault.model.AuthMethod;
import org.jetbrains.annotations.NotNull;

enum CredentialType {
    LOGIN("Login"),
    API_KEY("API Key"),
    SECURE_NOTE("Secure Note");

    private final String label;

    CredentialType(String label) {
        this.label = label;
    }

    static @NotNull CredentialType fromAuth(@NotNull AuthMethod auth) {
        return switch (auth) {
            case AuthMethod.Password ignored -> LOGIN;
            case AuthMethod.Key ignored -> LOGIN;
            case AuthMethod.KeyAndPassword ignored -> LOGIN;
            case AuthMethod.ApiToken ignored -> API_KEY;
            case AuthMethod.SecureNote ignored -> SECURE_NOTE;
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
