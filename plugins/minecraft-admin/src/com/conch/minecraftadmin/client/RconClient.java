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
        LOG.info("Conch Minecraft: RCON connect host=" + host + " port=" + port + " " + PasswordFingerprint.of(password));
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
        OutputStream out = session.socket().getOutputStream();
        InputStream in = session.socket().getInputStream();

        byte[] passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        LOG.info("Conch Minecraft: RCON authenticate sending TYPE_AUTH id=" + id
            + " passwordCharLength=" + password.length()
            + " passwordUtf8Bytes=" + passwordBytes.length);

        RconPacketCodec.writePacket(out, id, RconPacketCodec.TYPE_AUTH, password);

        RconPacketCodec.Packet first = RconPacketCodec.readPacket(in);
        LOG.info("Conch Minecraft: RCON authenticate first reply id=" + first.id()
            + " type=" + first.type()
            + " bodyLength=" + first.body().length()
            + " bodyHex=" + toHex(first.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        RconPacketCodec.Packet authResponse = first.type() == RconPacketCodec.TYPE_AUTH_RESPONSE
            ? first
            : RconPacketCodec.readPacket(in);

        if (first.type() != RconPacketCodec.TYPE_AUTH_RESPONSE) {
            LOG.info("Conch Minecraft: RCON authenticate reading second reply (first was type=" + first.type()
                + " which is not AUTH_RESPONSE; Minecraft emits a throwaway RESPONSE_VALUE first)");
            LOG.info("Conch Minecraft: RCON authenticate auth reply id=" + authResponse.id()
                + " type=" + authResponse.type()
                + " bodyLength=" + authResponse.body().length());
        }

        if (authResponse.id() == -1) {
            LOG.warn("Conch Minecraft: RCON auth rejected (id=-1). Server did not accept the password. "
                + "Check server.properties 'rcon.password' matches the vault credential, "
                + "and confirm the Minecraft server has been restarted since the password was changed.");
            throw new RconAuthException("rcon auth rejected");
        }
        if (authResponse.id() != id) {
            LOG.warn("Conch Minecraft: RCON auth id mismatch sent=" + id + " got=" + authResponse.id());
            throw new IOException("rcon auth response id mismatch: sent " + id
                + ", got " + authResponse.id());
        }
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "<empty>";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", bytes[i] & 0xff));
        }
        return sb.toString();
    }

    private static void safeClose(RconSession session) {
        try { session.close(); } catch (IOException ignored) {}
    }
}
