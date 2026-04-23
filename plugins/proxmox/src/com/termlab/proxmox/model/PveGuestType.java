package com.termlab.proxmox.model;

public enum PveGuestType {
    QEMU("qemu", "VM"),
    LXC("lxc", "LXC");

    private final String apiName;
    private final String displayName;

    PveGuestType(String apiName, String displayName) {
        this.apiName = apiName;
        this.displayName = displayName;
    }

    public String apiName() {
        return apiName;
    }

    public String displayName() {
        return displayName;
    }

    public static PveGuestType fromApiName(String value) {
        if ("qemu".equalsIgnoreCase(value)) return QEMU;
        if ("lxc".equalsIgnoreCase(value)) return LXC;
        throw new IllegalArgumentException("Unsupported Proxmox guest type: " + value);
    }
}
