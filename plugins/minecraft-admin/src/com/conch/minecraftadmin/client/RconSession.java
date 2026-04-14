package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Open RCON connection to one Minecraft server. Holds the socket and
 * an id counter so each request gets a unique id for correlation.
 * Close via {@link #close} or the enclosing try-with-resources.
 */
public final class RconSession implements Closeable {

    private final Socket socket;
    private final AtomicInteger idCounter = new AtomicInteger(1);

    RconSession(@NotNull Socket socket) {
        this.socket = socket;
    }

    @NotNull Socket socket() { return socket; }
    int nextId() { return idCounter.getAndIncrement(); }

    public boolean isClosed() {
        return socket.isClosed() || !socket.isConnected();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
