package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Pure encode/decode of Minecraft RCON packets. No sockets, no state.
 *
 * <p>Packet layout (little-endian):
 * <pre>
 *   int32  length    (bytes after this field)
 *   int32  request id
 *   int32  type
 *   bytes  payload   (UTF-8)
 *   byte   0x00      (payload terminator)
 *   byte   0x00      (packet terminator)
 * </pre>
 *
 * <p>Types used: {@link #TYPE_AUTH} = 3, {@link #TYPE_COMMAND} = 2,
 * {@link #TYPE_AUTH_RESPONSE} = 2, {@link #TYPE_RESPONSE_VALUE} = 0.
 * Note that TYPE_COMMAND and TYPE_AUTH_RESPONSE share id 2; they're
 * disambiguated by direction (client sends one, server sends the other).
 *
 * <p>Length cap: 4096 bytes per packet payload, consistent with
 * Minecraft's server implementation. Larger payloads are rejected with
 * an {@link IOException} to protect us from a malicious or misbehaving
 * peer.
 */
public final class RconPacketCodec {

    public static final int TYPE_RESPONSE_VALUE = 0;
    public static final int TYPE_COMMAND = 2;
    public static final int TYPE_AUTH_RESPONSE = 2;
    public static final int TYPE_AUTH = 3;

    /** Max payload length we're willing to read. Minecraft uses 4096. */
    public static final int MAX_PAYLOAD_BYTES = 4096;

    private RconPacketCodec() {}

    public record Packet(int id, int type, @NotNull String body) {}

    public static void writePacket(
        @NotNull OutputStream out,
        int id,
        int type,
        @NotNull String body
    ) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + bodyBytes.length + 2;  // id + type + body + two nulls
        if (length > MAX_PAYLOAD_BYTES + 10) {
            throw new IOException("rcon packet body too large: " + bodyBytes.length);
        }
        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(length);
        header.putInt(id);
        header.putInt(type);
        out.write(header.array());
        out.write(bodyBytes);
        out.write(0);  // payload terminator
        out.write(0);  // packet terminator
        out.flush();
    }

    public static @NotNull Packet readPacket(@NotNull InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);

        byte[] lenBytes = new byte[4];
        readFully(dis, lenBytes);
        int length = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 10 || length > MAX_PAYLOAD_BYTES + 10) {
            throw new IOException("invalid rcon packet length: " + length);
        }

        byte[] payload = new byte[length];
        readFully(dis, payload);

        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int id = buf.getInt();
        int type = buf.getInt();
        int bodyLen = length - 4 - 4 - 2;  // minus id, type, two nulls
        byte[] bodyBytes = new byte[bodyLen];
        buf.get(bodyBytes);
        // Skip the two terminating nulls.
        return new Packet(id, type, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static void readFully(DataInputStream in, byte[] target) throws IOException {
        int read = 0;
        while (read < target.length) {
            int n = in.read(target, read, target.length - read);
            if (n < 0) throw new EOFException("unexpected end of RCON stream");
            read += n;
        }
    }
}
