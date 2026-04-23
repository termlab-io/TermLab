package com.termlab.proxmox.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PvePaths {
    private PvePaths() {}

    public static Path clustersFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "termlab", "proxmox-clusters.json");
    }
}
