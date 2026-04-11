package com.conch.ssh.persistence;

import com.conch.ssh.model.KeyFileAuth;
import com.conch.ssh.model.PromptPasswordAuth;
import com.conch.ssh.model.SshAuth;
import com.conch.ssh.model.VaultAuth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Gson configuration for the SSH plugin.
 *
 * <p>Registers three custom adapters:
 * <ol>
 *   <li><b>Instant</b> — JDK 21 modules block default reflection into
 *       {@code java.time.Instant}, so we serialize as ISO-8601 strings.</li>
 *   <li><b>SshAuth serializer</b> — emits a discriminator-tagged object
 *       for the sealed type. Pattern mirrored from the vault plugin's
 *       {@code AuthMethodSerializer}.</li>
 *   <li><b>SshAuth deserializer</b> — dispatches on the {@code "type"}
 *       field to pick a record constructor.</li>
 * </ol>
 */
public final class SshGson {

    public static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .registerTypeAdapter(SshAuth.class, new SshAuthSerializer())
        .registerTypeAdapter(SshAuth.class, new SshAuthDeserializer())
        .create();

    private SshGson() {}

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
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

    private static final class SshAuthSerializer implements JsonSerializer<SshAuth> {
        @Override
        public JsonElement serialize(SshAuth src, java.lang.reflect.Type typeOfSrc,
                                     com.google.gson.JsonSerializationContext ctx) {
            JsonObject obj = new JsonObject();
            switch (src) {
                case VaultAuth v -> {
                    obj.addProperty("type", "vault");
                    if (v.credentialId() != null) {
                        obj.addProperty("credentialId", v.credentialId().toString());
                    }
                }
                case PromptPasswordAuth p -> obj.addProperty("type", "prompt_password");
                case KeyFileAuth k -> {
                    obj.addProperty("type", "key_file");
                    obj.addProperty("keyFilePath", k.keyFilePath());
                }
            }
            return obj;
        }
    }

    private static final class SshAuthDeserializer implements JsonDeserializer<SshAuth> {
        @Override
        public SshAuth deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                   com.google.gson.JsonDeserializationContext ctx) {
            JsonObject obj = json.getAsJsonObject();
            String type = obj.get("type").getAsString();
            return switch (type) {
                case "vault" -> {
                    UUID id = null;
                    if (obj.has("credentialId") && !obj.get("credentialId").isJsonNull()) {
                        id = UUID.fromString(obj.get("credentialId").getAsString());
                    }
                    yield new VaultAuth(id);
                }
                case "prompt_password" -> new PromptPasswordAuth();
                case "key_file" -> {
                    if (!obj.has("keyFilePath")) {
                        throw new JsonParseException("key_file SshAuth missing required 'keyFilePath'");
                    }
                    yield new KeyFileAuth(obj.get("keyFilePath").getAsString());
                }
                default -> throw new JsonParseException("unknown SshAuth type: " + type);
            };
        }
    }
}
