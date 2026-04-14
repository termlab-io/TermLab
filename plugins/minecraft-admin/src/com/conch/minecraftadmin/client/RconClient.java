package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Thin wrapper around a single RCON TCP connection. Stateless between
 * calls — caller owns the {@link RconSession} lifecycle.
 *
 * <p>Thread-safety: not thread-safe. Each {@code RconSession} is meant
 * to be used from one thread at a time (typically {@code ServerPoller}'s
 * executor). For parallel access, open multiple sessions.
 */
public final class RconClient {

    /** Connect timeout (TCP) in milliseconds. */
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    /** Read timeout (per response) in milliseconds. */
    public static final int READ_TIMEOUT_MS = 10_000;

    public @NotNull RconSession connect(
        @NotNull String host,
        int port,
        @NotNull char[] password
    ) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        RconSession session = new RconSession(socket);
        try {
            authenticate(session, new String(password));
        } catch (IOException e) {
            safeClose(session);
            throw e;
        }
        return session;
    }

    public @NotNull String command(@NotNull RconSession session, @NotNull String cmd) throws IOException {
        int id = session.nextId();
        OutputStream out = session.socket().getOutputStream();
        InputStream in = session.socket().getInputStream();
        RconPacketCodec.writePacket(out, id, RconPacketCodec.TYPE_COMMAND, cmd);
        RconPacketCodec.Packet reply = RconPacketCodec.readPacket(in);
        if (reply.id() != id) {
            throw new IOException("rcon response id mismatch: sent " + id + ", got " + reply.id());
        }
        return reply.body();
    }

    public void close(@NotNull RconSession session) {
        safeClose(session);
    }

    private void authenticate(RconSession session, String password) throws IOException {
        int id = session.nextId();
        OutputStream out = session.socket().getOutputStream();
        InputStream in = session.socket().getInputStream();
        RconPacketCodec.writePacket(out, id, RconPacketCodec.TYPE_AUTH, password);

        // The Minecraft server sends a throwaway empty TYPE_RESPONSE_VALUE
        // frame before the real auth response. Skip it if it shows up.
        RconPacketCodec.Packet first = RconPacketCodec.readPacket(in);
        RconPacketCodec.Packet authResponse = first.type() == RconPacketCodec.TYPE_AUTH_RESPONSE
            ? first
            : RconPacketCodec.readPacket(in);

        if (authResponse.id() == -1) {
            throw new RconAuthException("rcon auth rejected");
        }
        if (authResponse.id() != id) {
            throw new IOException("rcon auth response id mismatch: sent " + id
                + ", got " + authResponse.id());
        }
    }

    private static void safeClose(RconSession session) {
        try { session.close(); } catch (IOException ignored) {}
    }
}
