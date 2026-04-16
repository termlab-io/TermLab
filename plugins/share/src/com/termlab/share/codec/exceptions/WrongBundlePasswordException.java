package com.termlab.share.codec.exceptions;

public class WrongBundlePasswordException extends Exception {
    public WrongBundlePasswordException() {
        super("incorrect bundle password");
    }
}
