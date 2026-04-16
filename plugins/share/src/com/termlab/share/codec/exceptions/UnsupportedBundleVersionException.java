package com.termlab.share.codec.exceptions;

public class UnsupportedBundleVersionException extends Exception {
    private final int version;

    public UnsupportedBundleVersionException(int version) {
        super("unsupported bundle version: " + version);
        this.version = version;
    }

    public int version() {
        return version;
    }
}
