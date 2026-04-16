package com.termlab.share.conversion;

import com.termlab.share.model.BundledKeyMaterial;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

public final class KeyFileImporter {

    public sealed interface Result permits Result.Ok, Result.NeedsPassphrase, Result.Warning {
        record Ok(@NotNull BundledKeyMaterial material) implements Result {}
        record NeedsPassphrase(@NotNull Path path) implements Result {}
        record Warning(@NotNull String message) implements Result {}
    }

    private KeyFileImporter() {}

    public static @NotNull Result read(@NotNull Path keyPath, @Nullable String passphrase) {
        if (!Files.isRegularFile(keyPath)) {
            return new Result.Warning("Key file not found: " + keyPath);
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(keyPath);
        } catch (IOException e) {
            return new Result.Warning("Can't read key file: " + keyPath + " (" + e.getMessage() + ")");
        }

        String text = new String(bytes, StandardCharsets.US_ASCII);
        boolean looksLikePrivateKey =
            text.contains("-----BEGIN OPENSSH PRIVATE KEY-----")
                || text.contains("-----BEGIN RSA PRIVATE KEY-----")
                || text.contains("-----BEGIN EC PRIVATE KEY-----")
                || text.contains("-----BEGIN DSA PRIVATE KEY-----")
                || text.contains("-----BEGIN PRIVATE KEY-----");
        if (!looksLikePrivateKey) {
            return new Result.Warning("File does not look like a private key: " + keyPath);
        }

        boolean encrypted =
            text.contains("Proc-Type: 4,ENCRYPTED")
                || text.contains("BEGIN ENCRYPTED PRIVATE KEY");
        if (encrypted && (passphrase == null || passphrase.isEmpty())) {
            return new Result.NeedsPassphrase(keyPath);
        }

        BundledKeyMaterial material = new BundledKeyMaterial(
            UUID.randomUUID(),
            Base64.getEncoder().encodeToString(bytes),
            null,
            keyPath.getFileName() != null ? keyPath.getFileName().toString() : null
        );
        return new Result.Ok(material);
    }
}
