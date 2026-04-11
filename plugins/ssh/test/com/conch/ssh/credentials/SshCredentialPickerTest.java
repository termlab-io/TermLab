package com.conch.ssh.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.ssh.client.SshResolvedCredential;
import com.conch.ssh.model.SshHost;
import com.conch.ssh.model.VaultAuth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class SshCredentialPickerTest {

    @Test
    void pick_returnsNullWhenNoProvidersRegistered() {
        SshHost host = SshHost.create("h", "example.com", 22, "u", new VaultAuth(null));
        SshCredentialPicker picker = new SshCredentialPicker(List.of());
        assertNull(picker.pick(host));
    }

    @Test
    void pick_returnsNullWhenProviderReturnsNull() {
        SshHost host = SshHost.create("h", "example.com", 22, "u", new VaultAuth(null));
        FakeProvider provider = new FakeProvider(() -> null);
        SshCredentialPicker picker = new SshCredentialPicker(List.of(provider));
        assertNull(picker.pick(host));
    }

    @Test
    void pick_returnsResolvedCredentialWhenProviderReturnsAccount() {
        SshHost host = SshHost.create("h", "example.com", 22, "fallback", new VaultAuth(null));

        FakeProvider provider = new FakeProvider(() -> new CredentialProvider.Credential(
            UUID.randomUUID(),
            "Prod DB",
            "dbadmin",
            CredentialProvider.AuthMethod.PASSWORD,
            "secret".toCharArray(),
            null,
            null));

        SshCredentialPicker picker = new SshCredentialPicker(List.of(provider));
        try (SshResolvedCredential cred = picker.pick(host)) {
            assertNotNull(cred);
            assertEquals("dbadmin", cred.username());
            assertEquals(SshResolvedCredential.Mode.PASSWORD, cred.mode());
            assertArrayEquals("secret".toCharArray(), cred.password());
        }
    }

    @Test
    void pick_standaloneKey_injectsHostUsername() {
        SshHost host = SshHost.create("h", "example.com", 22, "fallback-user", new VaultAuth(null));

        FakeProvider provider = new FakeProvider(() -> new CredentialProvider.Credential(
            UUID.randomUUID(),
            "Work laptop key",
            null,  // standalone SSH key has no username
            CredentialProvider.AuthMethod.KEY,
            null,
            "/keys/work",
            null));

        SshCredentialPicker picker = new SshCredentialPicker(List.of(provider));
        try (SshResolvedCredential cred = picker.pick(host)) {
            assertNotNull(cred);
            assertEquals("fallback-user", cred.username());
            assertEquals("/keys/work", cred.keyPath().toString());
        }
    }

    @Test
    void pick_sdkCredentialIsDestroyedAfterCopy() {
        SshHost host = SshHost.create("h", "example.com", 22, "u", new VaultAuth(null));

        char[] pw = "secret".toCharArray();
        CredentialProvider.Credential sdkCred = new CredentialProvider.Credential(
            UUID.randomUUID(), "Prod", "admin",
            CredentialProvider.AuthMethod.PASSWORD,
            pw, null, null);

        FakeProvider provider = new FakeProvider(() -> sdkCred);
        SshCredentialPicker picker = new SshCredentialPicker(List.of(provider));

        try (SshResolvedCredential cred = picker.pick(host)) {
            assertNotNull(cred);
            assertArrayEquals(new char[]{0, 0, 0, 0, 0, 0}, pw);
            assertArrayEquals("secret".toCharArray(), cred.password());
        }
    }

    @Test
    void pick_skipsProviderThatReturnsNull_triesNext() {
        SshHost host = SshHost.create("h", "example.com", 22, "u", new VaultAuth(null));

        FakeProvider empty = new FakeProvider(() -> null);
        FakeProvider answering = new FakeProvider(() -> new CredentialProvider.Credential(
            UUID.randomUUID(), "Second", "second-user",
            CredentialProvider.AuthMethod.PASSWORD,
            "pw".toCharArray(), null, null));

        SshCredentialPicker picker = new SshCredentialPicker(List.of(empty, answering));
        try (SshResolvedCredential cred = picker.pick(host)) {
            assertNotNull(cred);
            assertEquals("second-user", cred.username());
        }
    }

    // -- fake provider --------------------------------------------------------

    private static final class FakeProvider implements CredentialProvider {
        private final Supplier<CredentialProvider.Credential> promptSupplier;

        FakeProvider(@NotNull Supplier<CredentialProvider.Credential> promptSupplier) {
            this.promptSupplier = promptSupplier;
        }

        @Override
        public @NotNull String getDisplayName() { return "Fake"; }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public @NotNull List<CredentialDescriptor> listCredentials() {
            return Collections.emptyList();
        }

        @Override
        public @Nullable Credential getCredential(@NotNull UUID credentialId) { return null; }

        @Override
        public @Nullable Credential promptForCredential() {
            return promptSupplier.get();
        }
    }
}
