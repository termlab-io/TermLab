package com.termlab.vault.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VaultFileFormatTest {

    @Test
    void writeHeader_thenParse_roundTrips() throws Exception {
        byte[] salt = new byte[16];
        for (int i = 0; i < 16; i++) salt[i] = (byte) i;
        byte[] nonce = new byte[12];
        for (int i = 0; i < 12; i++) nonce[i] = (byte) (i + 100);
        byte[] ciphertext = "encrypted bytes here".getBytes();

        byte[] file = VaultFileFormat.assemble(salt, nonce, ciphertext);

        VaultFileFormat.Parsed parsed = VaultFileFormat.parse(file);
        assertArrayEquals(salt, parsed.salt());
        assertArrayEquals(nonce, parsed.nonce());
        assertArrayEquals(ciphertext, parsed.ciphertext());
        assertEquals(VaultFileFormat.VERSION, parsed.version());
    }

    @Test
    void parse_rejectsWrongMagic() {
        byte[] bogus = new byte[40];
        bogus[0] = 'X';
        VaultCorruptedException ex = assertThrows(VaultCorruptedException.class,
            () -> VaultFileFormat.parse(bogus));
        assertTrue(ex.getMessage().toLowerCase().contains("magic"));
    }

    @Test
    void parse_rejectsTooShort() {
        assertThrows(VaultCorruptedException.class,
            () -> VaultFileFormat.parse(new byte[10]));
    }

    @Test
    void parse_rejectsUnknownVersion() {
        byte[] file = VaultFileFormat.assemble(new byte[16], new byte[12], new byte[1]);
        file[8] = 99;  // overwrite version byte
        assertThrows(VaultCorruptedException.class, () -> VaultFileFormat.parse(file));
    }

    @Test
    void assemble_rejectsBadSaltLength() {
        assertThrows(IllegalArgumentException.class,
            () -> VaultFileFormat.assemble(new byte[15], new byte[12], new byte[0]));
    }

    @Test
    void assemble_rejectsBadNonceLength() {
        assertThrows(IllegalArgumentException.class,
            () -> VaultFileFormat.assemble(new byte[16], new byte[11], new byte[0]));
    }
}
