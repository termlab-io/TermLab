package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around AMP's REST/JSON API. All calls are synchronous.
 * Auth is via a session token from {@code Core/Login}; a 401 response
 * triggers exactly one automatic re-login before the original call
 * is retried.
 *
 * <p>Not thread-safe — one caller per {@code AmpSession}, typically the
 * owning {@link ServerPoller}.
 */
public final class AmpClient {

    /** Network timeout for one request, in seconds. */
    public static final int TIMEOUT_SECONDS = 10;

    private final HttpClient http;
    private final Credentials credentials;

    public AmpClient(@NotNull Credentials credentials) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();
        this.credentials = credentials;
    }

    /** Re-login lookup. Production usage stores creds outside AmpClient and hands them back here. */
    @FunctionalInterface
    public interface Credentials {
        @NotNull LoginPair lookup(@NotNull String baseUrl);
    }

    public record LoginPair(@NotNull String username, @NotNull char[] password) {}

    public @NotNull AmpSession login(@NotNull String baseUrl) throws IOException {
        LoginPair pair = credentials.lookup(baseUrl);
        JsonObject body = new JsonObject();
        body.addProperty("username", pair.username());
        body.addProperty("password", new String(pair.password()));
        body.addProperty("token", "");
        body.addProperty("rememberMe", false);

        JsonObject response = postRaw(baseUrl, "Core/Login", body);
        if (!response.has("success") || !response.get("success").getAsBoolean()) {
            throw new AmpAuthException("AMP login rejected");
        }
        return new AmpSession(baseUrl, response.get("sessionID").getAsString());
    }

    public @NotNull InstanceStatus getInstanceStatus(
        @NotNull AmpSession session,
        @NotNull String instanceName
    ) throws IOException {
        JsonObject body = new JsonObject();
        JsonObject response = post(session, "Core/GetInstances", body);
        JsonArray groups = response.getAsJsonArray("result");
        if (groups == null) return InstanceStatus.unknown();
        for (JsonElement group : groups) {
            JsonArray instances = group.getAsJsonObject().getAsJsonArray("AvailableInstances");
            if (instances == null) continue;
            for (JsonElement e : instances) {
                JsonObject inst = e.getAsJsonObject();
                String friendly = inst.has("FriendlyName") ? inst.get("FriendlyName").getAsString() : "";
                String id = inst.has("InstanceName") ? inst.get("InstanceName").getAsString() : "";
                if (!instanceName.equals(friendly) && !instanceName.equals(id)) continue;
                return toStatus(inst);
            }
        }
        return InstanceStatus.unknown();
    }

    public void startInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        instanceLifecycle(s, instanceName, "Start");
    }

    public void stopInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        instanceLifecycle(s, instanceName, "Stop");
    }

    public void restartInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        instanceLifecycle(s, instanceName, "Restart");
    }

    public void backupInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("InstanceName", instanceName);
        body.addProperty("BackupTitle", "Conch manual backup");
        body.addProperty("BackupDescription", "Triggered from the Conch Minecraft Admin plugin");
        body.addProperty("Sticky", false);
        post(s, "InstanceManagementPlugin/TakeBackup", body);
    }

    public @NotNull ConsoleUpdate getConsoleUpdates(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("InstanceName", instanceName);
        JsonObject response = post(s, "Core/GetUpdates", body);
        JsonElement resultEl = response.get("result");
        if (resultEl == null || !resultEl.isJsonObject()) return ConsoleUpdate.empty();
        JsonArray entries = resultEl.getAsJsonObject().getAsJsonArray("ConsoleEntries");
        if (entries == null) return ConsoleUpdate.empty();
        List<String> lines = new ArrayList<>();
        for (JsonElement e : entries) {
            JsonObject entry = e.getAsJsonObject();
            String contents = entry.has("Contents") ? entry.get("Contents").getAsString() : "";
            lines.add(contents);
        }
        return new ConsoleUpdate(lines);
    }

    // -- internals ------------------------------------------------------------

    private void instanceLifecycle(AmpSession s, String instanceName, String action) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("InstanceName", instanceName);
        post(s, "ADSModule/" + action + "Instance", body);
    }

    private InstanceStatus toStatus(JsonObject inst) {
        boolean running = inst.has("Running") && inst.get("Running").getAsBoolean();
        int appState = inst.has("AppState") ? inst.get("AppState").getAsInt() : -1;
        McServerStatus status = mapAppState(running, appState);

        double cpu = Double.NaN;
        long ramUsed = 0;
        long ramMax = 0;
        if (inst.has("Metrics") && inst.get("Metrics").isJsonObject()) {
            JsonObject metrics = inst.getAsJsonObject("Metrics");
            if (metrics.has("CPU Usage") && metrics.get("CPU Usage").isJsonObject()) {
                JsonObject cpuObj = metrics.getAsJsonObject("CPU Usage");
                if (cpuObj.has("RawValue")) cpu = cpuObj.get("RawValue").getAsDouble();
            }
            if (metrics.has("Memory Usage") && metrics.get("Memory Usage").isJsonObject()) {
                JsonObject memObj = metrics.getAsJsonObject("Memory Usage");
                if (memObj.has("RawValue")) ramUsed = memObj.get("RawValue").getAsLong();
                if (memObj.has("MaxValue")) ramMax = memObj.get("MaxValue").getAsLong();
            }
        }

        Duration uptime = Duration.ZERO;
        if (inst.has("TimeStarted")) {
            try {
                Instant started = Instant.parse(inst.get("TimeStarted").getAsString());
                uptime = Duration.between(started, Instant.now());
                if (uptime.isNegative()) uptime = Duration.ZERO;
            } catch (Exception ignored) {}
        }

        return new InstanceStatus(status, cpu, ramUsed, ramMax, uptime);
    }

    private static McServerStatus mapAppState(boolean running, int appState) {
        // AMP AppState values: 0=Undefined, 5=PreStart, 10=Ready, 20=Starting,
        // 30=Started, 40=Stopping, 45=Restarting, 50=Stopped, 60=Failed, 70=Suspended.
        if (!running) return switch (appState) {
            case 50 -> McServerStatus.STOPPED;
            case 60 -> McServerStatus.CRASHED;
            default -> McServerStatus.STOPPED;
        };
        return switch (appState) {
            case 30 -> McServerStatus.RUNNING;
            case 20, 5, 10 -> McServerStatus.STARTING;
            case 40, 45 -> McServerStatus.STOPPING;
            case 60 -> McServerStatus.CRASHED;
            default -> McServerStatus.UNKNOWN;
        };
    }

    private JsonObject post(AmpSession session, String apiCall, JsonObject body) throws IOException {
        body.addProperty("SESSIONID", session.sessionId());
        try {
            return postRaw(session.baseUrl(), apiCall, body);
        } catch (AmpAuthException e) {
            // Session expired — re-login once and retry.
            LoginPair pair = credentials.lookup(session.baseUrl());
            JsonObject loginBody = new JsonObject();
            loginBody.addProperty("username", pair.username());
            loginBody.addProperty("password", new String(pair.password()));
            loginBody.addProperty("token", "");
            loginBody.addProperty("rememberMe", false);
            JsonObject loginResponse = postRaw(session.baseUrl(), "Core/Login", loginBody);
            if (!loginResponse.has("success") || !loginResponse.get("success").getAsBoolean()) {
                throw new AmpAuthException("AMP re-login after 401 rejected");
            }
            session.rotate(loginResponse.get("sessionID").getAsString());
            body.addProperty("SESSIONID", session.sessionId());
            return postRaw(session.baseUrl(), apiCall, body);
        }
    }

    private JsonObject postRaw(String baseUrl, String apiCall, JsonObject body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(trimTrailingSlash(baseUrl) + "/API/" + apiCall))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("AMP request interrupted", e);
        }
        int status = response.statusCode();
        if (status == 401) {
            throw new AmpAuthException("AMP returned 401 for " + apiCall);
        }
        if (status < 200 || status >= 300) {
            throw new IOException("AMP " + apiCall + " returned HTTP " + status);
        }
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) throw new IOException("AMP returned non-object JSON");
            return parsed.getAsJsonObject();
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IOException("AMP returned malformed JSON: " + e.getMessage(), e);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
