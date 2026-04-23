package com.termlab.proxmox.toolwindow;

public enum AutoRefreshMode {
    OFF("Auto refresh: off", 0),
    FIVE_SECONDS("Every 5s", 5000),
    FIFTEEN_SECONDS("Every 15s", 15000);

    private final String label;
    private final int millis;

    AutoRefreshMode(String label, int millis) {
        this.label = label;
        this.millis = millis;
    }

    public int millis() {
        return millis;
    }

    @Override
    public String toString() {
        return label;
    }
}
