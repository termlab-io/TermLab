package com.conch.tunnels.persistence;

import com.conch.tunnels.model.InternalHost;
import com.conch.tunnels.model.SshConfigHost;
import com.conch.tunnels.model.TunnelHost;
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

public final class TunnelGson {

    public static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .registerTypeAdapter(TunnelHost.class, new TunnelHostSerializer())
        .registerTypeAdapter(TunnelHost.class, new TunnelHostDeserializer())
        .create();

    private TunnelGson() {}

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) { out.nullValue(); } else { out.value(value.toString()); }
        }
        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            return Instant.parse(in.nextString());
        }
    }

    private static final class TunnelHostSerializer implements JsonSerializer<TunnelHost> {
        @Override
        public JsonElement serialize(TunnelHost src, java.lang.reflect.Type typeOfSrc,
                                     com.google.gson.JsonSerializationContext ctx) {
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
                                      com.google.gson.JsonDeserializationContext ctx) {
            JsonObject obj = json.getAsJsonObject();
            String type = obj.get("type").getAsString();
            return switch (type) {
                case "internal" -> new InternalHost(UUID.fromString(obj.get("hostId").getAsString()));
                case "ssh_config" -> new SshConfigHost(obj.get("alias").getAsString());
                default -> throw new JsonParseException("unknown TunnelHost type: " + type);
            };
        }
    }
}
