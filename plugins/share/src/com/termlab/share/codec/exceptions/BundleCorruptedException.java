package com.termlab.share.codec.exceptions;

public class BundleCorruptedException extends Exception {
    public BundleCorruptedException(String message) {
        super(message);
    }

    public BundleCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
