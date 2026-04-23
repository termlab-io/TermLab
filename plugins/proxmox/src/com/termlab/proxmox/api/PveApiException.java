package com.termlab.proxmox.api;

public class PveApiException extends Exception {
    private final int statusCode;

    public PveApiException(String message) {
        this(-1, message);
    }

    public PveApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
