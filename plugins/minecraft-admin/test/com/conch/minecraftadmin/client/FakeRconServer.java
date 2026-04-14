package com.conch.minecraftadmin.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * In-process RCON server for testing the client. Implements the
 * handshake, checks the password, and responds to arbitrary commands
 * via a registered handler. One connection at a time; additional
 * clients are accepted serially.
 */
final class FakeRconServer implements AutoCloseable {

    private final ServerSocket server;
    private final String expectedPassword;
    private final ConcurrentHashMap<String, Function<String, String>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fake-rcon-server");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;
    private volatile boolean dropNextResponse = false;

    FakeRconServer(String expectedPassword) throws IOException {
        this.server = new ServerSocket(0);
        this.expectedPassword = expectedPassword;
        executor.submit(this::acceptLoop);
    }

    int port() { return server.getLocalPort(); }

    void onCommand(String command, Function<String, String> handler) {
        handlers.put(command, handler);
    }

    void dropNextResponse() {
        this.dropNextResponse = true;
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try (Socket client = server.accept();
                 InputStream in = client.getInputStream();
                 OutputStream out = client.getOutputStream()) {
                serve(in, out);
            } catch (IOException ignored) {
                // socket closed
            }
        }
    }

    private void serve(InputStream in, OutputStream out) throws IOException {
        // Auth first.
        RconPacketCodec.Packet authPacket = RconPacketCodec.readPacket(in);
        int authId = authPacket.id();
        if (authPacket.type() != RconPacketCodec.TYPE_AUTH) {
            return;  // misbehaving client
        }
        if (!expectedPassword.equals(authPacket.body())) {
            RconPacketCodec.writePacket(out, -1, RconPacketCodec.TYPE_AUTH_RESPONSE, "");
            return;
        }
        RconPacketCodec.writePacket(out, authId, RconPacketCodec.TYPE_AUTH_RESPONSE, "");

        // Command loop.
        while (true) {
            RconPacketCodec.Packet packet;
            try {
                packet = RconPacketCodec.readPacket(in);
            } catch (IOException eof) {
                return;
            }
            if (packet.type() != RconPacketCodec.TYPE_COMMAND) continue;

            if (dropNextResponse) {
                dropNextResponse = false;
                return;  // close without reply
            }

            String cmd = packet.body().trim();
            Function<String, String> handler = handlers.get(cmd);
            String body = handler != null ? handler.apply(cmd) : "";
            RconPacketCodec.writePacket(out, packet.id(), RconPacketCodec.TYPE_RESPONSE_VALUE, body);
        }
    }
}
