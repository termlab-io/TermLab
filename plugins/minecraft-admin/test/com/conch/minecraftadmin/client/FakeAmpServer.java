package com.conch.minecraftadmin.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process AMP fake for AmpClient tests. Hosts a {@link HttpServer} on
 * an ephemeral port and serves canned JSON for a configurable set of
 * API paths. Each handler receives the parsed request body so tests
 * can assert on the incoming payload.
 */
final class FakeAmpServer implements AutoCloseable {

    private final HttpServer server;
    private final ConcurrentHashMap<String, Responder> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean rejectNextLogin = new AtomicBoolean(false);
    private volatile int force401Remaining = 0;

    @FunctionalInterface
    interface Responder {
        JsonObject respond(JsonObject requestBody);
    }

    FakeAmpServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new DispatchingHandler());
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void handle(String apiCall, Responder responder) {
        handlers.put(apiCall, responder);
    }

    /** Make the next {@code Core/Login} call fail with success=false. */
    void rejectNextLogin() {
        rejectNextLogin.set(true);
    }

    /** Make the next N non-login calls return HTTP 401. */
    void return401(int times) {
        this.force401Remaining = times;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private final class DispatchingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String apiCall = path.startsWith("/API/") ? path.substring("/API/".length()) : path;
            String bodyText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject requestBody;
            try {
                JsonElement parsed = JsonParser.parseString(bodyText.isEmpty() ? "{}" : bodyText);
                requestBody = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            } catch (Exception e) {
                requestBody = new JsonObject();
            }

            if (force401Remaining > 0 && !apiCall.equals("Core/Login")) {
                force401Remaining--;
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }

            if (apiCall.equals("Core/Login") && rejectNextLogin.compareAndSet(true, false)) {
                JsonObject r = new JsonObject();
                r.addProperty("success", false);
                writeJson(exchange, r);
                return;
            }

            Responder responder = handlers.get(apiCall);
            JsonObject reply = responder != null ? responder.respond(requestBody) : new JsonObject();
            writeJson(exchange, reply);
        }
    }

    private static void writeJson(HttpExchange exchange, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
