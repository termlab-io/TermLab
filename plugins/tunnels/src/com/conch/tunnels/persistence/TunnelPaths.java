package com.conch.tunnels.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class TunnelPaths {
    private TunnelPaths() {}

    public static Path tunnelsFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "conch", "tunnels.json");
    }
}
