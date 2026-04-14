package com.conch.minecraftadmin.client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RconPacketCodecTest {

    @Test
    void encode_commandPacket_matchesKnownLayout() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RconPacketCodec.writePacket(out, 1, RconPacketCodec.TYPE_COMMAND, "list");
        byte[] bytes = out.toByteArray();
        // length = 4(id) + 4(type) + payload + 2(two nulls) = 4 + 4 + 4 + 2 = 14
        // packet = 4-byte LE length(14) + 4-byte LE id(1) + 4-byte LE type(2) + "list" + 0x00 0x00
        assertEquals(18, bytes.length);
        assertEquals(14, bytes[0] & 0xff);
        assertEquals(0,  bytes[1] & 0xff);
        assertEquals(0,  bytes[2] & 0xff);
        assertEquals(0,  bytes[3] & 0xff);
        assertEquals(1,  bytes[4] & 0xff);  // id LE
        assertEquals(2,  bytes[8] & 0xff);  // TYPE_COMMAND LE
        assertEquals('l', bytes[12]);
        assertEquals('i', bytes[13]);
        assertEquals('s', bytes[14]);
        assertEquals('t', bytes[15]);
        assertEquals(0,  bytes[16]);
        assertEquals(0,  bytes[17]);
    }

    @Test
    void encodeThenDecode_roundTrips() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RconPacketCodec.writePacket(out, 42, RconPacketCodec.TYPE_COMMAND, "say hello");
        RconPacketCodec.Packet decoded = RconPacketCodec.readPacket(
            new ByteArrayInputStream(out.toByteArray()));
        assertEquals(42, decoded.id());
        assertEquals(RconPacketCodec.TYPE_COMMAND, decoded.type());
        assertEquals("say hello", decoded.body());
    }

    @Test
    void decode_authResponseSuccess() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RconPacketCodec.writePacket(out, 7, RconPacketCodec.TYPE_AUTH_RESPONSE, "");
        RconPacketCodec.Packet decoded = RconPacketCodec.readPacket(
            new ByteArrayInputStream(out.toByteArray()));
        assertEquals(7, decoded.id());
        assertEquals(RconPacketCodec.TYPE_AUTH_RESPONSE, decoded.type());
        assertEquals("", decoded.body());
    }

    @Test
    void decode_authFailureIdIsNegativeOne() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RconPacketCodec.writePacket(out, -1, RconPacketCodec.TYPE_AUTH_RESPONSE, "");
        RconPacketCodec.Packet decoded = RconPacketCodec.readPacket(
            new ByteArrayInputStream(out.toByteArray()));
        assertEquals(-1, decoded.id());
    }

    @Test
    void decode_truncatedPacket_throwsEOF() {
        byte[] truncated = { 14, 0, 0, 0, 1, 0 };  // length says 14 but we have 2
        assertThrows(EOFException.class,
            () -> RconPacketCodec.readPacket(new ByteArrayInputStream(truncated)));
    }

    @Test
    void decode_oversizedLength_throwsIOException() {
        byte[] oversized = { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f, 0, 0, 0, 0 };
        assertThrows(IOException.class,
            () -> RconPacketCodec.readPacket(new ByteArrayInputStream(oversized)));
    }

    @Test
    void decode_negativeLength_throwsIOException() {
        byte[] bad = { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0 };
        assertThrows(IOException.class,
            () -> RconPacketCodec.readPacket(new ByteArrayInputStream(bad)));
    }

    @Test
    void encode_payloadWithUtf8_encodesRoundTrips() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RconPacketCodec.writePacket(out, 1, RconPacketCodec.TYPE_COMMAND, "say héllo 🎮");
        RconPacketCodec.Packet decoded = RconPacketCodec.readPacket(
            new ByteArrayInputStream(out.toByteArray()));
        assertEquals("say héllo 🎮", decoded.body());
    }
}
