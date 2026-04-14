package com.conch.minecraftadmin.client;

import java.io.IOException;

/** RCON login was rejected (server returned id = -1). */
public final class RconAuthException extends IOException {
    public RconAuthException(String message) { super(message); }
}
