package com.termlab.share.codec;

import com.termlab.ssh.model.KeyFileAuth;
import com.termlab.ssh.model.PromptPasswordAuth;
import com.termlab.ssh.model.SshAuth;
import com.termlab.ssh.model.VaultAuth;
import com.termlab.tunnels.model.InternalHost;
import com.termlab.tunnels.model.SshConfigHost;
import com.termlab.tunnels.model.TunnelHost;
import com.termlab.vault.model.AuthMethod;
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

public final class ShareBundleGson {

    public static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .registerTypeAdapter(SshAuth.class, new SshAuthSerializer())
        .registerTypeAdapter(SshAuth.class, new SshAuthDeserializer())
        .registerTypeAdapter(TunnelHost.class, new TunnelHostSerializer())
        .registerTypeAdapter(TunnelHost.class, new TunnelHostDeserializer())
        .registerTypeAdapter(AuthMethod.class, new AuthMethodSerializer())
        .registerTypeAdapter(AuthMethod.class, new AuthMethodDeserializer())
        .create();

    private ShareBundleGson() {}

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
                                     com.google.gson.JsonSerializationContext context) {
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
                                   com.google.gson.JsonDeserializationContext context) {
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

    private static final class TunnelHostSerializer implements JsonSerializer<TunnelHost> {
        @Override
        public JsonElement serialize(TunnelHost src, java.lang.reflect.Type typeOfSrc,
                                     com.google.gson.JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            switch (src) {
                case InternalHost h -> {
                    obj.addProperty("type", "internal");
                    obj.addProperty("hostId", h.hostId().toString());
                }
                case SshConfigHost h -> {
                    obj.addProperty("type", "ssh_config");
                    obj.addProperty("alias", h.alias());
                }
            }
            return obj;
        }
    }

    private static final class TunnelHostDeserializer implements JsonDeserializer<TunnelHost> {
        @Override
        public TunnelHost deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                      com.google.gson.JsonDeserializationContext context) {
            JsonObject obj = json.getAsJsonObject();
            String type = obj.get("type").getAsString();
            return switch (type) {
                case "internal" -> new InternalHost(UUID.fromString(obj.get("hostId").getAsString()));
                case "ssh_config" -> new SshConfigHost(obj.get("alias").getAsString());
                default -> throw new JsonParseException("unknown TunnelHost type: " + type);
            };
        }
    }

    private static final class AuthMethodSerializer implements JsonSerializer<AuthMethod> {
        @Override
        public JsonElement serialize(AuthMethod src, java.lang.reflect.Type typeOfSrc,
                                     com.google.gson.JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            switch (src) {
                case AuthMethod.Password p -> {
                    obj.addProperty("type", "password");
                    obj.addProperty("password", p.password());
                }
                case AuthMethod.Key k -> {
                    obj.addProperty("type", "key");
                    obj.addProperty("keyPath", k.keyPath());
                    if (k.passphrase() != null) {
                        obj.addProperty("passphrase", k.passphrase());
                    }
                }
                case AuthMethod.KeyAndPassword kp -> {
                    obj.addProperty("type", "keyAndPassword");
                    obj.addProperty("keyPath", kp.keyPath());
                    if (kp.passphrase() != null) {
                        obj.addProperty("passphrase", kp.passphrase());
                    }
                    obj.addProperty("password", kp.password());
                }
            }
            return obj;
        }
    }

    private static final class AuthMethodDeserializer implements JsonDeserializer<AuthMethod> {
        @Override
        public AuthMethod deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                      com.google.gson.JsonDeserializationContext context) {
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
                default -> throw new JsonParseException("unknown AuthMethod type: " + type);
            };
        }
    }
}
