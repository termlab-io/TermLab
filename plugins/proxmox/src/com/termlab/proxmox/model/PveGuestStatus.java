package com.termlab.proxmox.model;

public enum PveGuestStatus {
    RUNNING,
    STOPPED,
    PAUSED,
    UNKNOWN;

    public static PveGuestStatus fromApiName(String value) {
        if ("running".equalsIgnoreCase(value)) return RUNNING;
        if ("stopped".equalsIgnoreCase(value)) return STOPPED;
        if ("paused".equalsIgnoreCase(value)) return PAUSED;
        return UNKNOWN;
    }

    public String displayName() {
        return switch (this) {
            case RUNNING -> "running";
            case STOPPED -> "stopped";
            case PAUSED -> "paused";
            case UNKNOWN -> "unknown";
        };
    }
}
