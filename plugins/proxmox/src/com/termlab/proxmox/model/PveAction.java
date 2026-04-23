package com.termlab.proxmox.model;

public enum PveAction {
    START("start", "Start"),
    SHUTDOWN("shutdown", "Shutdown"),
    STOP("stop", "Stop"),
    REBOOT("reboot", "Reboot");

    private final String apiName;
    private final String displayName;

    PveAction(String apiName, String displayName) {
        this.apiName = apiName;
        this.displayName = displayName;
    }

    public String apiName() {
        return apiName;
    }

    public String displayName() {
        return displayName;
    }
}
