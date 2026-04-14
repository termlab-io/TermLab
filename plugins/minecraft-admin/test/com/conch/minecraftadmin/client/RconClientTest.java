package com.conch.minecraftadmin.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RconClientTest {

    @Test
    void connect_happyPath_authenticates() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret")) {
            RconClient client = new RconClient();
            try (RconSession session = client.connect("127.0.0.1", server.port(), "secret".toCharArray())) {
                assertFalse(session.isClosed());
            }
        }
    }

    @Test
    void connect_wrongPassword_throwsRconAuthException() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret")) {
            RconClient client = new RconClient();
            assertThrows(RconAuthException.class,
                () -> client.connect("127.0.0.1", server.port(), "nope".toCharArray()));
        }
    }

    @Test
    void command_returnsHandlerBody() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret")) {
            server.onCommand("list", cmd -> "There are 2 of a max of 20 players online: alice, bob");
            RconClient client = new RconClient();
            try (RconSession session = client.connect("127.0.0.1", server.port(), "secret".toCharArray())) {
                String reply = client.command(session, "list");
                assertTrue(reply.contains("alice"));
                assertTrue(reply.contains("bob"));
            }
        }
    }

    @Test
    void command_multipleCommandsShareOneSession() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret")) {
            server.onCommand("list", cmd -> "list-reply");
            server.onCommand("tps", cmd -> "tps-reply");
            RconClient client = new RconClient();
            try (RconSession session = client.connect("127.0.0.1", server.port(), "secret".toCharArray())) {
                assertEquals("list-reply", client.command(session, "list"));
                assertEquals("tps-reply", client.command(session, "tps"));
                assertEquals("list-reply", client.command(session, "list"));
            }
        }
    }

    @Test
    void command_droppedResponse_throwsIOException() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret")) {
            server.onCommand("list", cmd -> "ignored");
            server.dropNextResponse();
            RconClient client = new RconClient();
            try (RconSession session = client.connect("127.0.0.1", server.port(), "secret".toCharArray())) {
                assertThrows(IOException.class, () -> client.command(session, "list"));
            }
        }
    }

    @Test
    void connect_unreachable_throwsIOException() {
        RconClient client = new RconClient();
        // Port 1 is ~always closed on a dev box.
        assertThrows(IOException.class,
            () -> client.connect("127.0.0.1", 1, "x".toCharArray()));
    }
}
