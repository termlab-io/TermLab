package com.conch.ssh.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;

/**
 * Gson configuration for the SSH plugin. Mirrors the vault's
 * {@code VaultGson}: registers a custom {@link Instant} adapter because
 * JDK 21's module system blocks Gson's default reflection into
 * {@code java.time.Instant} internals. Serializes Instants as ISO-8601
 * strings — human-readable and language-independent.
 *
 * <p>Intentionally kept as a private per-plugin holder (not shared with
 * the vault plugin) so the ssh plugin stays compilable in isolation.
 */
final class SshGson {

    static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .create();

    private SshGson() {}

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());  // ISO-8601, e.g. "2026-04-11T12:34:56Z"
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }
}
