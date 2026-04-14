package com.conch.minecraftadmin.client;

import java.io.IOException;

/** AMP rejected the username/password. */
public final class AmpAuthException extends IOException {
    public AmpAuthException(String message) { super(message); }
}
