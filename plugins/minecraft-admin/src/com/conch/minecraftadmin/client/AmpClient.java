package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
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

    private static final Logger LOG = Logger.getInstance(AmpClient.class);

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
        LOG.info("Conch Minecraft: AMP login attempt baseUrl=" + baseUrl
            + " username=" + pair.username()
            + " password.length=" + pair.password().length);
        JsonObject body = new JsonObject();
        body.addProperty("username", pair.username());
        body.addProperty("password", new String(pair.password()));
        body.addProperty("token", "");
        body.addProperty("rememberMe", false);

        JsonObject response = expectObject(postRaw(baseUrl, "Core/Login", body), "Core/Login");
        String sessionField = response.has("sessionID") ? response.get("sessionID").getAsString() : null;
        String sessionFingerprint = sessionField == null ? "<null>"
            : sessionField.substring(0, Math.min(8, sessionField.length())) + "…";
        boolean success = response.has("success") && response.get("success").getAsBoolean();
        LOG.info("Conch Minecraft: AMP login response success=" + success + " sessionId=" + sessionFingerprint);
        if (!success) {
            LOG.warn("Conch Minecraft: AMP login rejected baseUrl=" + baseUrl + " fullResponse=" + response.toString());
            throw new AmpAuthException("AMP login rejected");
        }
        return new AmpSession(baseUrl, sessionField);
    }

    public @NotNull InstanceStatus getInstanceStatus(
        @NotNull AmpSession session,
        @NotNull String instanceName
    ) throws IOException {
        LOG.debug("Conch Minecraft: AMP getInstanceStatus instanceName=" + instanceName);
        JsonObject body = new JsonObject();
        JsonElement response = post(session, "ADSModule/GetInstances", body);
        JsonArray groups;
        if (response.isJsonArray()) {
            groups = response.getAsJsonArray();
        } else if (response.isJsonObject() && response.getAsJsonObject().has("result")) {
            groups = response.getAsJsonObject().getAsJsonArray("result");
        } else {
            LOG.warn("Conch Minecraft: ADSModule/GetInstances returned unexpected shape: " + response);
            return InstanceStatus.unknown();
        }
        if (groups == null) {
            LOG.warn("Conch Minecraft: ADSModule/GetInstances returned no groups");
            return InstanceStatus.unknown();
        }

        int groupCount = groups.size();
        int totalInstances = 0;
        for (JsonElement g : groups) {
            JsonArray av = g.getAsJsonObject().getAsJsonArray("AvailableInstances");
            if (av != null) totalInstances += av.size();
        }
        LOG.debug("Conch Minecraft: AMP GetInstances returned " + groupCount
            + " group(s), " + totalInstances + " instance(s)");

        List<String> visibleLabels = new ArrayList<>();
        for (JsonElement group : groups) {
            JsonArray instances = group.getAsJsonObject().getAsJsonArray("AvailableInstances");
            if (instances == null) continue;
            for (JsonElement e : instances) {
                JsonObject inst = e.getAsJsonObject();
                String friendly = inst.has("FriendlyName") ? inst.get("FriendlyName").getAsString() : "";
                String id = inst.has("InstanceName") ? inst.get("InstanceName").getAsString() : "";
                boolean running = inst.has("Running") && inst.get("Running").getAsBoolean();
                int appState = inst.has("AppState") ? inst.get("AppState").getAsInt() : -1;
                LOG.debug("Conch Minecraft: AMP instance seen: FriendlyName=" + friendly
                    + " InstanceName=" + id + " Running=" + running + " AppState=" + appState);
                visibleLabels.add(friendly + " (" + id + ")");
                if (!instanceName.equalsIgnoreCase(friendly) && !instanceName.equalsIgnoreCase(id)) continue;
                InstanceStatus mapped = toStatus(inst);
                LOG.debug("Conch Minecraft: AMP instance '" + instanceName
                    + "' mapped to status=" + mapped);
                return mapped;
            }
        }
        LOG.warn("Conch Minecraft: AMP instance '" + instanceName + "' not found. AMP knows about these instances: "
            + visibleLabels);
        return InstanceStatus.unknown();
    }

    public void startInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        LOG.info("Conch Minecraft: AMP startInstance " + instanceName + " baseUrl=" + s.baseUrl());
        instanceLifecycle(s, instanceName, "Start");
        LOG.info("Conch Minecraft: AMP startInstance " + instanceName + " succeeded");
    }

    public void stopInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        LOG.info("Conch Minecraft: AMP stopInstance " + instanceName + " baseUrl=" + s.baseUrl());
        instanceLifecycle(s, instanceName, "Stop");
        LOG.info("Conch Minecraft: AMP stopInstance " + instanceName + " succeeded");
    }

    public void restartInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        LOG.info("Conch Minecraft: AMP restartInstance " + instanceName + " baseUrl=" + s.baseUrl());
        instanceLifecycle(s, instanceName, "Restart");
        LOG.info("Conch Minecraft: AMP restartInstance " + instanceName + " succeeded");
    }

    public void backupInstance(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        LOG.info("Conch Minecraft: AMP backupInstance " + instanceName + " baseUrl=" + s.baseUrl());
        JsonObject body = new JsonObject();
        body.addProperty("InstanceName", instanceName);
        body.addProperty("BackupTitle", "Conch manual backup");
        body.addProperty("BackupDescription", "Triggered from the Conch Minecraft Admin plugin");
        body.addProperty("Sticky", false);
        post(s, "InstanceManagementPlugin/TakeBackup", body);
        LOG.info("Conch Minecraft: AMP backupInstance " + instanceName + " succeeded");
    }

    public @NotNull ConsoleUpdate getConsoleUpdates(@NotNull AmpSession s, @NotNull String instanceName) throws IOException {
        LOG.debug("Conch Minecraft: AMP getConsoleUpdates instanceName=" + instanceName);
        JsonObject body = new JsonObject();
        body.addProperty("InstanceName", instanceName);
        JsonObject response = expectObject(post(s, "Core/GetUpdates", body), "Core/GetUpdates");
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
        LOG.debug("Conch Minecraft: AMP getConsoleUpdates returned " + lines.size() + " line(s)");
        return new ConsoleUpdate(lines);
    }

    // -- internals ------------------------------------------------------------

    private static String redactSecrets(JsonObject body) {
        JsonObject copy = body.deepCopy();
        if (copy.has("password")) copy.addProperty("password", "<redacted>");
        if (copy.has("SESSIONID")) copy.addProperty("SESSIONID", "<redacted>");
        return copy.toString();
    }

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
        int playersOnline = 0;
        int playersMax = 0;
        double tps = Double.NaN;
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
            if (metrics.has("Active Users") && metrics.get("Active Users").isJsonObject()) {
                JsonObject usersObj = metrics.getAsJsonObject("Active Users");
                if (usersObj.has("RawValue")) playersOnline = usersObj.get("RawValue").getAsInt();
                if (usersObj.has("MaxValue")) playersMax = usersObj.get("MaxValue").getAsInt();
            }
            if (metrics.has("TPS") && metrics.get("TPS").isJsonObject()) {
                JsonObject tpsObj = metrics.getAsJsonObject("TPS");
                if (tpsObj.has("RawValue")) tps = tpsObj.get("RawValue").getAsDouble();
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

        return new InstanceStatus(status, cpu, ramUsed, ramMax, uptime, playersOnline, playersMax, tps);
    }

    /**
     * Maps AMP's Running flag and AppState enum to this plugin's McServerStatus.
     *
     * <p>Canonical AMP AppState table (AMP 2.7, confirmed empirically):
     * <pre>
     *   0  = Undefined          -> UNKNOWN
     *   5  = PreStart           -> STARTING
     *   7  = Configuring        -> STARTING
     *  10  = Starting           -> STARTING
     *  20  = Ready              -> RUNNING
     *  25  = Restarting         -> STOPPING
     *  30  = Stopping           -> STOPPING
     *  40  = PreparingForSleep  -> STOPPING
     *  45  = Sleeping           -> STOPPED
     *  50  = Waiting            -> STOPPED
     *  60  = Installing         -> STARTING
     *  70  = Updating           -> STARTING
     *  75  = AwaitingUserInput  -> STARTING
     *  80  = Failed             -> CRASHED
     * 100  = Suspended          -> STOPPED
     * 200  = Maintenance        -> STOPPED
     * 250  = Indeterminate      -> UNKNOWN
     * </pre>
     * If Running == false: prefer STOPPED unless AppState == 80 (CRASHED).
     * Any AppState value not in the table -> UNKNOWN.
     */
    static McServerStatus mapAppState(boolean running, int appState) {
        if (!running) {
            return appState == 80 ? McServerStatus.CRASHED : McServerStatus.STOPPED;
        }
        return switch (appState) {
            case 0, 250 -> McServerStatus.UNKNOWN;
            case 5, 7, 10, 60, 70, 75 -> McServerStatus.STARTING;
            case 20 -> McServerStatus.RUNNING;
            case 25, 30, 40 -> McServerStatus.STOPPING;
            case 45, 50, 100, 200 -> McServerStatus.STOPPED;
            case 80 -> McServerStatus.CRASHED;
            default -> McServerStatus.UNKNOWN;
        };
    }

    private static JsonObject expectObject(JsonElement element, String apiCall) throws IOException {
        if (!element.isJsonObject()) {
            throw new IOException("AMP " + apiCall + " returned "
                + (element.isJsonArray() ? "an array" : "a primitive") + " but expected an object");
        }
        return element.getAsJsonObject();
    }

    private JsonElement post(AmpSession session, String apiCall, JsonObject body) throws IOException {
        body.addProperty("SESSIONID", session.sessionId());
        try {
            return postRaw(session.baseUrl(), apiCall, body);
        } catch (AmpAuthException e) {
            // Session expired — re-login once and retry.
            LOG.info("Conch Minecraft: AMP session expired, re-logging in baseUrl="
                + session.baseUrl() + " apiCall=" + apiCall);
            LoginPair pair = credentials.lookup(session.baseUrl());
            JsonObject loginBody = new JsonObject();
            loginBody.addProperty("username", pair.username());
            loginBody.addProperty("password", new String(pair.password()));
            loginBody.addProperty("token", "");
            loginBody.addProperty("rememberMe", false);
            JsonObject loginResponse = expectObject(postRaw(session.baseUrl(), "Core/Login", loginBody), "Core/Login");
            if (!loginResponse.has("success") || !loginResponse.get("success").getAsBoolean()) {
                LOG.warn("Conch Minecraft: AMP re-login rejected baseUrl=" + session.baseUrl());
                throw new AmpAuthException("AMP re-login after 401 rejected");
            }
            session.rotate(loginResponse.get("sessionID").getAsString());
            String newSessionId = session.sessionId();
            String redactedNewSession = newSessionId.substring(0, Math.min(8, newSessionId.length())) + "…";
            LOG.info("Conch Minecraft: AMP re-login success, retrying " + apiCall
                + " newSessionId=" + redactedNewSession);
            body.addProperty("SESSIONID", session.sessionId());
            return postRaw(session.baseUrl(), apiCall, body);
        }
    }

    private JsonElement postRaw(String baseUrl, String apiCall, JsonObject body) throws IOException {
        String url = trimTrailingSlash(baseUrl) + "/API/" + apiCall;
        LOG.info("Conch Minecraft: AMP POST " + apiCall + " url=" + url
            + " body=" + redactSecrets(body));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
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
            LOG.warn("Conch Minecraft: AMP POST " + apiCall + " interrupted", e);
            throw new IOException("AMP request interrupted", e);
        }
        int status = response.statusCode();
        LOG.info("Conch Minecraft: AMP " + apiCall + " response status=" + status
            + " bodyLength=" + response.body().length());
        if (status == 401) {
            LOG.warn("Conch Minecraft: AMP POST " + apiCall + " failed status=401");
            throw new AmpAuthException("AMP returned 401 for " + apiCall);
        }
        if (status < 200 || status >= 300) {
            LOG.warn("Conch Minecraft: AMP POST " + apiCall
                + " failed status=" + status + " fullBody=" + response.body());
            throw new IOException("AMP " + apiCall + " returned HTTP " + status);
        }
        // Log response body preview at debug level — INFO already has status + length above
        if (apiCall.equals("Core/Login")) {
            LOG.debug("Conch Minecraft: AMP POST " + apiCall
                + " status=" + status + " bodyPreview=<login response: length=" + response.body().length() + ">");
        } else {
            String preview = response.body().substring(0, Math.min(500, response.body().length()));
            LOG.debug("Conch Minecraft: AMP POST " + apiCall
                + " status=" + status + " bodyPreview=" + preview);
        }
        try {
            return JsonParser.parseString(response.body());
        } catch (com.google.gson.JsonSyntaxException e) {
            LOG.warn("Conch Minecraft: AMP POST " + apiCall + " returned malformed JSON", e);
            throw new IOException("AMP returned malformed JSON: " + e.getMessage(), e);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
