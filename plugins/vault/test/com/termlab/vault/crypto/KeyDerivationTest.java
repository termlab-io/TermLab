package com.termlab.vault.crypto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class KeyDerivationTest {

    @Test
    void deriveKey_isDeterministicForSameInput() {
        byte[] password = "correct horse battery staple".getBytes();
        byte[] salt = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        byte[] key1 = KeyDerivation.deriveKey(password, salt);
        byte[] key2 = KeyDerivation.deriveKey(password, salt);

        assertArrayEquals(key1, key2);
        assertEquals(KeyDerivation.KEY_LEN, key1.length);
    }

    @Test
    void deriveKey_differsForDifferentSalt() {
        byte[] password = "password".getBytes();
        byte[] salt1 = new byte[16];
        byte[] salt2 = new byte[16];
        salt2[0] = 1;

        byte[] key1 = KeyDerivation.deriveKey(password, salt1);
        byte[] key2 = KeyDerivation.deriveKey(password, salt2);

        assertFalse(Arrays.equals(key1, key2));
    }

    @Test
    void deriveKey_differsForDifferentPassword() {
        byte[] salt = new byte[16];
        byte[] key1 = KeyDerivation.deriveKey("password1".getBytes(), salt);
        byte[] key2 = KeyDerivation.deriveKey("password2".getBytes(), salt);
        assertFalse(Arrays.equals(key1, key2));
    }

    @Test
    void deriveKey_rejectsWrongSaltLength() {
        assertThrows(IllegalArgumentException.class,
            () -> KeyDerivation.deriveKey("pw".getBytes(), new byte[15]));
    }

    @Test
    void deriveKey_withDeviceSecret_differsFromPasswordAlone() {
        byte[] password = "pw".getBytes();
        byte[] salt = new byte[16];
        byte[] deviceSecret = new byte[32];
        Arrays.fill(deviceSecret, (byte) 0xA5);

        byte[] plain = KeyDerivation.deriveKey(password, salt);
        byte[] bound = KeyDerivation.deriveKey(password, deviceSecret, salt);

        assertFalse(Arrays.equals(plain, bound));
    }

    @Test
    void deriveKey_withDifferentDeviceSecret_producesDifferentKey() {
        byte[] password = "pw".getBytes();
        byte[] salt = new byte[16];
        byte[] ds1 = new byte[32];
        byte[] ds2 = new byte[32];
        ds2[0] = 1;

        byte[] key1 = KeyDerivation.deriveKey(password, ds1, salt);
        byte[] key2 = KeyDerivation.deriveKey(password, ds2, salt);

        assertFalse(Arrays.equals(key1, key2));
    }
}
