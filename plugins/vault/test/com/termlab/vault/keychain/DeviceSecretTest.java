package com.termlab.vault.keychain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DeviceSecretTest {

    @Test
    void getOrCreate_generatesFreshSecretWhenEmpty() {
        DeviceSecret ds = new DeviceSecret(new InMemoryDeviceSecretStore());
        byte[] secret = ds.getOrCreate();
        assertEquals(32, secret.length);
        // Not all-zero (vanishingly unlikely with SecureRandom).
        byte[] zeros = new byte[32];
        assertFalse(Arrays.equals(zeros, secret));
    }

    @Test
    void getOrCreate_isIdempotent() {
        DeviceSecret ds = new DeviceSecret(new InMemoryDeviceSecretStore());
        byte[] first = ds.getOrCreate();
        byte[] second = ds.getOrCreate();
        assertArrayEquals(first, second);
    }

    @Test
    void getOrCreate_returnsDefensiveCopies() {
        InMemoryDeviceSecretStore store = new InMemoryDeviceSecretStore();
        DeviceSecret ds = new DeviceSecret(store);
        byte[] first = ds.getOrCreate();
        Arrays.fill(first, (byte) 0xFF);  // caller mutates the returned array
        byte[] second = ds.getOrCreate();
        // Second call should still return the original stored bytes.
        assertNotEquals((byte) 0xFF, second[0]);
    }

    @Test
    void delete_forgetsSecret_nextGetOrCreateIsNew() {
        DeviceSecret ds = new DeviceSecret(new InMemoryDeviceSecretStore());
        byte[] first = ds.getOrCreate();
        ds.delete();
        byte[] second = ds.getOrCreate();
        // Both are 32 random bytes; vanishingly unlikely to match.
        assertFalse(Arrays.equals(first, second));
    }

    @Test
    void inMemoryStore_rejectsWrongLength() {
        InMemoryDeviceSecretStore store = new InMemoryDeviceSecretStore();
        assertThrows(IllegalArgumentException.class, () -> store.write(new byte[31]));
    }

    @Test
    void inMemoryStore_readBeforeWrite_returnsNull() {
        InMemoryDeviceSecretStore store = new InMemoryDeviceSecretStore();
        assertNull(store.read());
    }

    @Test
    void inMemoryStore_deleteWhenEmpty_isSafe() {
        InMemoryDeviceSecretStore store = new InMemoryDeviceSecretStore();
        assertDoesNotThrow(store::delete);
    }
}
