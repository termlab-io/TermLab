package com.termlab.vault.crypto;

import com.termlab.vault.model.AuthMethod;
import com.termlab.vault.model.Vault;
import com.termlab.vault.model.VaultAccount;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultCryptoTest {

    @Test
    void roundTrip_smallVault() throws Exception {
        Vault original = new Vault();
        original.accounts.add(new VaultAccount(
            UUID.randomUUID(),
            "Production DB",
            "dbadmin",
            new AuthMethod.Password("hunter2"),
            Instant.now(),
            Instant.now()
        ));

        byte[] password = "master password".getBytes();
        byte[] encrypted = VaultCrypto.encrypt(original, password);

        // Encrypted bytes must not contain any cleartext.
        String blob = new String(encrypted);
        assertFalse(blob.contains("hunter2"));
        assertFalse(blob.contains("dbadmin"));
        assertFalse(blob.contains("Production DB"));

        Vault decrypted = VaultCrypto.decrypt(encrypted, password);
        assertEquals(1, decrypted.accounts.size());
        VaultAccount account = decrypted.accounts.get(0);
        assertEquals("Production DB", account.displayName());

        AuthMethod auth = account.auth();
        assertInstanceOf(AuthMethod.Password.class, auth);
        assertEquals("hunter2", ((AuthMethod.Password) auth).password());
    }

    @Test
    void roundTrip_apiToken() throws Exception {
        Vault original = new Vault();
        original.accounts.add(new VaultAccount(
            UUID.randomUUID(),
            "Proxmox Token",
            "root@pam!termlab",
            new AuthMethod.ApiToken("pve-secret"),
            Instant.now(),
            Instant.now()
        ));

        Vault decrypted = VaultCrypto.decrypt(
            VaultCrypto.encrypt(original, "master password".getBytes()),
            "master password".getBytes()
        );

        AuthMethod auth = decrypted.accounts.get(0).auth();
        assertInstanceOf(AuthMethod.ApiToken.class, auth);
        assertEquals("pve-secret", ((AuthMethod.ApiToken) auth).token());
    }

    @Test
    void roundTrip_secureNote() throws Exception {
        Vault original = new Vault();
        original.accounts.add(new VaultAccount(
            UUID.randomUUID(),
            "Recovery Codes",
            "",
            new AuthMethod.SecureNote("one\ntwo"),
            Instant.now(),
            Instant.now()
        ));

        Vault decrypted = VaultCrypto.decrypt(
            VaultCrypto.encrypt(original, "master password".getBytes()),
            "master password".getBytes()
        );

        AuthMethod auth = decrypted.accounts.get(0).auth();
        assertInstanceOf(AuthMethod.SecureNote.class, auth);
        assertEquals("one\ntwo", ((AuthMethod.SecureNote) auth).note());
    }

    @Test
    void decrypt_wrongPassword_throws() {
        Vault v = new Vault();
        byte[] encrypted = VaultCrypto.encrypt(v, "right password".getBytes());

        assertThrows(WrongPasswordException.class,
            () -> VaultCrypto.decrypt(encrypted, "wrong password".getBytes()));
    }

    @Test
    void encrypt_freshNonceEachCall() {
        Vault v = new Vault();
        byte[] password = "p".getBytes();
        byte[] e1 = VaultCrypto.encrypt(v, password);
        byte[] e2 = VaultCrypto.encrypt(v, password);
        assertFalse(Arrays.equals(e1, e2),
            "two encryptions of the same plaintext must produce different ciphertexts");
    }

    @Test
    void encrypt_withDeviceSecret_roundTrip() throws Exception {
        Vault v = new Vault();
        byte[] password = "pw".getBytes();
        byte[] deviceSecret = new byte[32];
        Arrays.fill(deviceSecret, (byte) 0x42);

        byte[] encrypted = VaultCrypto.encrypt(v, password, deviceSecret);
        Vault decrypted = VaultCrypto.decrypt(encrypted, password, deviceSecret);
        assertNotNull(decrypted);
    }

    @Test
    void decrypt_withWrongDeviceSecret_throwsWrongPassword() {
        Vault v = new Vault();
        byte[] password = "pw".getBytes();
        byte[] ds1 = new byte[32];
        byte[] ds2 = new byte[32];
        ds2[0] = 1;

        byte[] encrypted = VaultCrypto.encrypt(v, password, ds1);

        assertThrows(WrongPasswordException.class,
            () -> VaultCrypto.decrypt(encrypted, password, ds2));
    }
}
