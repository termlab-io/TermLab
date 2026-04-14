package com.conch.minecraftadmin.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved Minecraft server profile. Identifies one AMP instance plus
 * its RCON endpoint. Credentials are referenced by vault id — the
 * plaintext password never lives in this record or in the JSON file.
 *
 * @param id                 stable UUID, survives renames
 * @param label              user-facing name ("Survival")
 * @param ampUrl             AMP panel base URL (https://amp.example.com:8080)
 * @param ampInstanceName    friendly AMP instance name; resolved to numeric
 *                           id at connect time via {@code Core/GetInstances}
 * @param ampUsername        AMP panel username (plaintext, not a secret)
 * @param ampCredentialId    vault reference → AMP password
 * @param rconHost           RCON endpoint host (often same as ampUrl's host)
 * @param rconPort           RCON endpoint port, default 25575
 * @param rconCredentialId   vault reference → RCON password
 * @param createdAt          when the profile was created
 * @param updatedAt          when the profile was last edited
 */
public record ServerProfile(
    @NotNull UUID id,
    @NotNull String label,
    @NotNull String ampUrl,
    @NotNull String ampInstanceName,
    @NotNull String ampUsername,
    @NotNull UUID ampCredentialId,
    @NotNull String rconHost,
    int rconPort,
    @NotNull UUID rconCredentialId,
    @NotNull Instant createdAt,
    @NotNull Instant updatedAt
) {
    /** Default Minecraft RCON port. */
    public static final int DEFAULT_RCON_PORT = 25575;

    /** Factory for brand-new profiles. */
    public static @NotNull ServerProfile create(
        @NotNull String label,
        @NotNull String ampUrl,
        @NotNull String ampInstanceName,
        @NotNull String ampUsername,
        @NotNull UUID ampCredentialId,
        @NotNull String rconHost,
        int rconPort,
        @NotNull UUID rconCredentialId
    ) {
        Instant now = Instant.now();
        return new ServerProfile(
            UUID.randomUUID(),
            label,
            ampUrl,
            ampInstanceName,
            ampUsername,
            ampCredentialId,
            rconHost,
            rconPort,
            rconCredentialId,
            now,
            now);
    }

    /** @return a copy with a new label and bumped {@code updatedAt}. */
    public @NotNull ServerProfile withLabel(@NotNull String newLabel) {
        return new ServerProfile(
            id, newLabel, ampUrl, ampInstanceName, ampUsername, ampCredentialId,
            rconHost, rconPort, rconCredentialId, createdAt, Instant.now());
    }

    /** @return a copy with every editable field replaced. Used by the edit dialog's Save path. */
    public @NotNull ServerProfile withEdited(
        @NotNull String newLabel,
        @NotNull String newAmpUrl,
        @NotNull String newAmpInstanceName,
        @NotNull String newAmpUsername,
        @NotNull UUID newAmpCredentialId,
        @NotNull String newRconHost,
        int newRconPort,
        @NotNull UUID newRconCredentialId
    ) {
        return new ServerProfile(
            id, newLabel, newAmpUrl, newAmpInstanceName, newAmpUsername, newAmpCredentialId,
            newRconHost, newRconPort, newRconCredentialId, createdAt, Instant.now());
    }
}
