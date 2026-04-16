package com.termlab.core.filepicker.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorMessagesTest {

    @Test
    void authFailTranslated() {
        String msg = ErrorMessages.translate(new IOException("Auth fail"));
        assertTrue(msg.contains("Permission denied") || msg.contains("credentials"));
    }

    @Test
    void permissionDeniedTranslated() {
        String msg = ErrorMessages.translate(new IOException("permission denied"));
        assertTrue(msg.toLowerCase().contains("permission denied"));
    }

    @Test
    void connectionRefusedTranslated() {
        String msg = ErrorMessages.translate(new IOException("Connection refused"));
        assertTrue(msg.toLowerCase().contains("refused"));
    }

    @Test
    void unknownHostTranslated() {
        String msg = ErrorMessages.translate(new IOException("Unknown host: example.com"));
        assertTrue(msg.toLowerCase().contains("reach"));
    }

    @Test
    void timedOutTranslated() {
        String msg = ErrorMessages.translate(new IOException("Connection timed out"));
        assertTrue(msg.toLowerCase().contains("timed out"));
    }

    @Test
    void noSuchFileTranslated() {
        String msg = ErrorMessages.translate(new IOException("No such file or directory"));
        assertTrue(msg.toLowerCase().contains("not found"));
    }

    @Test
    void unknownMessageFallsThrough() {
        String msg = ErrorMessages.translate(new IOException("some random error"));
        assertEquals("Error: some random error", msg);
    }

    @Test
    void nullMessageFallsThroughToClassName() {
        String msg = ErrorMessages.translate(new IOException());
        assertTrue(msg.contains("Error:"));
        assertTrue(msg.contains("IOException"));
    }
}
