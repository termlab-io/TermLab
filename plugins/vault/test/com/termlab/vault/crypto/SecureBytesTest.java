package com.termlab.vault.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecureBytesTest {

    @Test
    void close_zeroesCopiedContents() {
        SecureBytes bytes = SecureBytes.copyOf("password".getBytes());
        byte[] underlying = bytes.bytes();
        assertEquals('p', underlying[0]);
        bytes.close();
        for (byte b : underlying) {
            assertEquals(0, b);
        }
    }

    @Test
    void close_zeroesOwnedArray() {
        byte[] source = "secret".getBytes();
        SecureBytes bytes = SecureBytes.wrap(source);
        bytes.close();
        for (byte b : source) {
            assertEquals(0, b);
        }
    }

    @Test
    void readAfterClose_throws() {
        SecureBytes bytes = SecureBytes.copyOf(new byte[]{1, 2, 3});
        bytes.close();
        assertThrows(IllegalStateException.class, bytes::bytes);
        assertThrows(IllegalStateException.class, bytes::length);
    }

    @Test
    void doubleClose_isSafe() {
        SecureBytes bytes = SecureBytes.copyOf(new byte[]{1});
        bytes.close();
        assertDoesNotThrow(bytes::close);
    }

    @Test
    void copyOf_isDefensive_sourceNotMutated() {
        byte[] source = "hello".getBytes();
        SecureBytes bytes = SecureBytes.copyOf(source);
        bytes.close();
        assertEquals('h', source[0]);  // source untouched
    }
}
