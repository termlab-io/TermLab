package com.conch.minecraftadmin.client;

import com.intellij.openapi.diagnostic.Logger;
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

    private static final Logger LOG = Logger.getInstance(RconClient.class);

    /** Connect timeout (TCP) in milliseconds. */
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    /** Read timeout (per response) in milliseconds. */
    public static final int READ_TIMEOUT_MS = 10_000;

    public @NotNull RconSession connect(
        @NotNull String host,
        int port,
        @NotNull char[] password
    ) throws IOException {
        LOG.info("Conch Minecraft: RCON connect host=" + host + " port=" + port
            + " password.length=" + password.length);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            LOG.info("Conch Minecraft: RCON TCP connected host=" + host + " port=" + port
                + " localPort=" + socket.getLocalPort());
        } catch (IOException e) {
            LOG.warn("Conch Minecraft: RCON TCP connect failed host=" + host + " port=" + port
                + " error=" + e.getMessage());
            socket.close();
            throw e;
        }
        RconSession session = new RconSession(socket);
        try {
            authenticate(session, new String(password));
            LOG.info("Conch Minecraft: RCON auth success host=" + host + " port=" + port);
        } catch (IOException e) {
            LOG.warn("Conch Minecraft: RCON auth failed host=" + host + " port=" + port
                + " error=" + e.getMessage());
            safeClose(session);
            throw e;
        }
        return session;
    }

    public @NotNull String command(@NotNull RconSession session, @NotNull String cmd) throws IOException {
        int id = session.nextId();
        LOG.debug("Conch Minecraft: RCON command sending id=" + id + " cmd=" + cmd);
        OutputStream out = session.socket().getOutputStream();
        InputStream in = session.socket().getInputStream();
        RconPacketCodec.writePacket(out, id, RconPacketCodec.TYPE_COMMAND, cmd);
        RconPacketCodec.Packet reply = RconPacketCodec.readPacket(in);
        if (reply.id() != id) {
            LOG.warn("Conch Minecraft: RCON command id mismatch sent=" + id + " got=" + reply.id());
            throw new IOException("rcon response id mismatch: sent " + id + ", got " + reply.id());
        }
        LOG.debug("Conch Minecraft: RCON command reply id=" + reply.id()
            + " bodyLength=" + reply.body().length());
        return reply.body();
    }

    public void close(@NotNull RconSession session) {
        safeClose(session);
    }

    private void authenticate(RconSession session, String password) throws IOException {
        int id = session.nextId();
        LOG.debug("Conch Minecraft: RCON auth packet id=" + id + " password.length=" + password.length());
        OutputStream out = session.socket().getOutputStream();
        InputStream in = session.socket().getInputStream();
        RconPacketCodec.writePacket(out, id, RconPacketCodec.TYPE_AUTH, password);

        // The Minecraft server sends a throwaway empty TYPE_RESPONSE_VALUE
        // frame before the real auth response. Skip it if it shows up.
        RconPacketCodec.Packet first = RconPacketCodec.readPacket(in);
        LOG.debug("Conch Minecraft: RCON auth reply #1 id=" + first.id()
            + " type=" + first.type() + " bodyLength=" + first.body().length());
        RconPacketCodec.Packet authResponse;
        if (first.type() == RconPacketCodec.TYPE_AUTH_RESPONSE) {
            authResponse = first;
        } else {
            LOG.debug("Conch Minecraft: RCON skipping leading empty frame type=" + first.type()
                + ", reading real auth response");
            authResponse = RconPacketCodec.readPacket(in);
            LOG.debug("Conch Minecraft: RCON auth reply #2 id=" + authResponse.id()
                + " type=" + authResponse.type() + " bodyLength=" + authResponse.body().length());
        }

        if (authResponse.id() == -1) {
            LOG.warn("Conch Minecraft: RCON auth rejected (id=-1)");
            throw new RconAuthException("rcon auth rejected");
        }
        if (authResponse.id() != id) {
            LOG.warn("Conch Minecraft: RCON auth id mismatch sent=" + id
                + " got=" + authResponse.id());
            throw new IOException("rcon auth response id mismatch: sent " + id
                + ", got " + authResponse.id());
        }
    }

    private static void safeClose(RconSession session) {
        try { session.close(); } catch (IOException ignored) {}
    }
}
