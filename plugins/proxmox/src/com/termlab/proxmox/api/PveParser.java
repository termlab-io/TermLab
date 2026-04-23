package com.termlab.proxmox.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.termlab.proxmox.model.PveGuest;
import com.termlab.proxmox.model.PveGuestStatus;
import com.termlab.proxmox.model.PveGuestType;
import com.termlab.proxmox.model.PveTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PveParser {
    private static final Logger LOG = Logger.getInstance(PveParser.class);

    private PveParser() {}

    public static @NotNull List<PveGuest> parseGuests(@NotNull String json) throws PveApiException {
        JsonElement data = data(json);
        if (!data.isJsonArray()) throw new PveApiException("Proxmox returned a non-list guest response");
        List<PveGuest> guests = new ArrayList<>();
        int totalRows = data.getAsJsonArray().size();
        int skippedNonGuestRows = 0;
        int skippedMalformedGuestRows = 0;
        for (JsonElement element : data.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                skippedMalformedGuestRows++;
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String type = string(obj, "type", "");
            if (!"qemu".equals(type) && !"lxc".equals(type)) {
                skippedNonGuestRows++;
                continue;
            }
            int vmid = (int) number(obj, "vmid", -1);
            String node = string(obj, "node", "");
            if (vmid < 0 || node.isBlank()) {
                skippedMalformedGuestRows++;
                LOG.warn("TermLab Proxmox: skipping guest resource with missing vmid/node: " + obj);
                continue;
            }
            String name = string(obj, "name", string(obj, "id", type + "/" + vmid));
            guests.add(new PveGuest(
                vmid,
                name,
                node,
                PveGuestType.fromApiName(type),
                PveGuestStatus.fromApiName(string(obj, "status", "")),
                number(obj, "cpu", 0.0) * 100.0,
                (int) number(obj, "maxcpu", 0),
                (long) number(obj, "mem", 0),
                (long) number(obj, "maxmem", 0),
                (long) number(obj, "disk", 0),
                (long) number(obj, "maxdisk", 0),
                (long) number(obj, "uptime", 0),
                stringOrNull(obj, "template")
            ));
        }
        LOG.info("TermLab Proxmox: guest parser rows=" + totalRows
            + " guests=" + guests.size()
            + " nonGuestRows=" + skippedNonGuestRows
            + " malformedGuestRows=" + skippedMalformedGuestRows);
        return guests;
    }

    public static @NotNull Map<String, String> parseConfig(@NotNull String json) throws PveApiException {
        JsonElement data = data(json);
        if (!data.isJsonObject()) throw new PveApiException("Proxmox returned a non-object config response");
        Map<String, String> config = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            config.put(entry.getKey(), value == null || value.isJsonNull() ? "" : value.getAsString());
        }
        return config;
    }

    public static @NotNull String parseTaskUpid(@NotNull String json) throws PveApiException {
        JsonElement data = data(json);
        if (data.isJsonPrimitive()) return data.getAsString();
        throw new PveApiException("Proxmox action response did not include a task id");
    }

    public static @NotNull PveTask parseTask(@NotNull String node, @NotNull String upid, @NotNull String json)
        throws PveApiException {
        JsonElement data = data(json);
        if (!data.isJsonObject()) throw new PveApiException("Proxmox returned a non-object task response");
        JsonObject obj = data.getAsJsonObject();
        return new PveTask(
            upid,
            node,
            string(obj, "status", "unknown"),
            stringOrNull(obj, "exitstatus")
        );
    }

    static @NotNull JsonElement data(@NotNull String json) throws PveApiException {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) throw new PveApiException("Malformed Proxmox JSON response");
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("data") || obj.get("data").isJsonNull()) throw new PveApiException("Missing Proxmox data field");
            return obj.get("data");
        } catch (PveApiException e) {
            throw e;
        } catch (Exception e) {
            throw new PveApiException("Malformed Proxmox JSON response: " + e.getMessage());
        }
    }

    private static double number(@NotNull JsonObject obj, @NotNull String key, double fallback) {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        try {
            return value.getAsDouble();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static @NotNull String string(@NotNull JsonObject obj, @NotNull String key, @NotNull String fallback) {
        String value = stringOrNull(obj, key);
        return value == null ? fallback : value;
    }

    private static String stringOrNull(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull()) return null;
        try {
            return value.getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
