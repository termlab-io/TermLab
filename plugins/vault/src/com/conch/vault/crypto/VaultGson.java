package com.conch.vault.crypto;

import com.conch.vault.model.AuthMethod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;

/**
 * Central Gson configuration for vault serialization. Registers two custom
 * adapters that are needed because:
 *
 * <ol>
 *   <li><b>Instant</b> — JDK 21's module system blocks Gson's default
 *       reflection into {@code java.time.Instant}'s private fields. We
 *       serialize as ISO-8601 strings.</li>
 *   <li><b>AuthMethod</b> — Gson can't instantiate sealed interfaces, so we
 *       emit a discriminator-tagged object: {@code {"type": "password",
 *       "password": "..."}} and route parsing to the right record based on
 *       the tag.</li>
 * </ol>
 */
final class VaultGson {

    static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .registerTypeAdapter(AuthMethod.class, new AuthMethodSerializer())
        .registerTypeAdapter(AuthMethod.class, new AuthMethodDeserializer())
        .create();

    private VaultGson() {}

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

    private static final class AuthMethodSerializer implements JsonSerializer<AuthMethod> {
        @Override
        public JsonElement serialize(AuthMethod src, java.lang.reflect.Type typeOfSrc,
                                     com.google.gson.JsonSerializationContext ctx) {
            JsonObject obj = new JsonObject();
            switch (src) {
                case AuthMethod.Password p -> {
                    obj.addProperty("type", "password");
                    obj.addProperty("password", p.password());
                }
                case AuthMethod.Key k -> {
                    obj.addProperty("type", "key");
                    obj.addProperty("keyPath", k.keyPath());
                    if (k.passphrase() != null) obj.addProperty("passphrase", k.passphrase());
                }
                case AuthMethod.KeyAndPassword kp -> {
                    obj.addProperty("type", "keyAndPassword");
                    obj.addProperty("keyPath", kp.keyPath());
                    if (kp.passphrase() != null) obj.addProperty("passphrase", kp.passphrase());
                    obj.addProperty("password", kp.password());
                }
            }
            return obj;
        }
    }

    private static final class AuthMethodDeserializer implements JsonDeserializer<AuthMethod> {
        @Override
        public AuthMethod deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                      com.google.gson.JsonDeserializationContext ctx) {
            JsonObject obj = json.getAsJsonObject();
            String type = obj.get("type").getAsString();
            return switch (type) {
                case "password" -> new AuthMethod.Password(obj.get("password").getAsString());
                case "key" -> new AuthMethod.Key(
                    obj.get("keyPath").getAsString(),
                    obj.has("passphrase") ? obj.get("passphrase").getAsString() : null
                );
                case "keyAndPassword" -> new AuthMethod.KeyAndPassword(
                    obj.get("keyPath").getAsString(),
                    obj.has("passphrase") ? obj.get("passphrase").getAsString() : null,
                    obj.get("password").getAsString()
                );
                default -> throw new com.google.gson.JsonParseException("unknown AuthMethod type: " + type);
            };
        }
    }
}
