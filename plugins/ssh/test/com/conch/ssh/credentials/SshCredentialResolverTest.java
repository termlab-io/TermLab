package com.conch.ssh.credentials;

import com.conch.sdk.CredentialProvider;
import com.conch.ssh.client.SshResolvedCredential;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SshCredentialResolverTest {

    @Test
    void resolve_noProviderKnowsCredential_returnsNull() {
        UUID credentialId = UUID.randomUUID();
        SshCredentialResolver resolver = new SshCredentialResolver(List.of(new FakeProvider()));
        assertNull(resolver.resolve(credentialId, "u"));
    }

    @Test
    void resolve_passwordCredential_populatesResolved() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider provider = new FakeProvider();
        provider.add(new CredentialProvider.Credential(
            credentialId,
            "Prod DB",
            "dbadmin",
            CredentialProvider.AuthMethod.PASSWORD,
            "hunter2".toCharArray(),
            null,
            null));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(provider)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertEquals(SshResolvedCredential.Mode.PASSWORD, cred.mode());
            assertEquals("dbadmin", cred.username());
            assertArrayEquals("hunter2".toCharArray(), cred.password());
        }
    }

    @Test
    void resolve_sdkCredentialIsDestroyedAfterCopy() {
        UUID credentialId = UUID.randomUUID();

        char[] pw = "hunter2".toCharArray();
        CredentialProvider.Credential sdkCred = new CredentialProvider.Credential(
            credentialId, "Prod", "admin", CredentialProvider.AuthMethod.PASSWORD,
            pw, null, null);

        FakeProvider provider = new FakeProvider();
        provider.add(sdkCred);

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(provider)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertArrayEquals(new char[]{0, 0, 0, 0, 0, 0, 0}, pw);
            assertArrayEquals("hunter2".toCharArray(), cred.password());
        }
    }

    @Test
    void resolve_keyCredential_populatesResolved() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider provider = new FakeProvider();
        provider.add(new CredentialProvider.Credential(
            credentialId,
            "GitHub",
            "git",
            CredentialProvider.AuthMethod.KEY,
            null,
            "/home/me/.ssh/id_ed25519",
            "keypass".toCharArray()));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(provider)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertEquals(SshResolvedCredential.Mode.KEY, cred.mode());
            assertEquals("git", cred.username());
            assertEquals("/home/me/.ssh/id_ed25519", cred.keyPath().toString());
            assertArrayEquals("keypass".toCharArray(), cred.keyPassphrase());
        }
    }

    @Test
    void resolve_keyAndPasswordCredential_populatesResolved() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider provider = new FakeProvider();
        provider.add(new CredentialProvider.Credential(
            credentialId,
            "Bastion",
            "ops",
            CredentialProvider.AuthMethod.KEY_AND_PASSWORD,
            "server-pw".toCharArray(),
            "/keys/bastion",
            "key-pp".toCharArray()));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(provider)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertEquals(SshResolvedCredential.Mode.KEY_AND_PASSWORD, cred.mode());
            assertEquals("ops", cred.username());
            assertEquals("/keys/bastion", cred.keyPath().toString());
            assertArrayEquals("server-pw".toCharArray(), cred.password());
            assertArrayEquals("key-pp".toCharArray(), cred.keyPassphrase());
        }
    }

    @Test
    void resolve_standaloneKey_injectsFallbackUsername() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider provider = new FakeProvider();
        provider.add(new CredentialProvider.Credential(
            credentialId,
            "Work laptop key",
            null,  // standalone key, no username
            CredentialProvider.AuthMethod.KEY,
            null,
            "/keys/work",
            null));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(provider)).resolve(credentialId, "fallback-user")) {
            assertNotNull(cred);
            assertEquals("fallback-user", cred.username());
            assertEquals(SshResolvedCredential.Mode.KEY, cred.mode());
            assertEquals("/keys/work", cred.keyPath().toString());
        }
    }

    @Test
    void resolve_multipleProviders_firstHitWins() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider first = new FakeProvider();
        first.add(new CredentialProvider.Credential(
            credentialId, "A", "a-user", CredentialProvider.AuthMethod.PASSWORD,
            "a-pw".toCharArray(), null, null));

        FakeProvider second = new FakeProvider();
        second.add(new CredentialProvider.Credential(
            credentialId, "B", "b-user", CredentialProvider.AuthMethod.PASSWORD,
            "b-pw".toCharArray(), null, null));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(first, second)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertEquals("a-user", cred.username());
        }
    }

    @Test
    void resolve_unavailableProviderIsSkipped() {
        UUID credentialId = UUID.randomUUID();

        FakeProvider locked = new FakeProvider();
        locked.available = false;
        locked.add(new CredentialProvider.Credential(
            credentialId, "A", "locked-user", CredentialProvider.AuthMethod.PASSWORD,
            "pw".toCharArray(), null, null));

        FakeProvider unlocked = new FakeProvider();
        unlocked.add(new CredentialProvider.Credential(
            credentialId, "B", "unlocked-user", CredentialProvider.AuthMethod.PASSWORD,
            "pw".toCharArray(), null, null));

        try (SshResolvedCredential cred =
                 new SshCredentialResolver(List.of(locked, unlocked)).resolve(credentialId, "u")) {
            assertNotNull(cred);
            assertEquals("unlocked-user", cred.username());
        }
    }

    // -- fake provider --------------------------------------------------------

    private static final class FakeProvider implements CredentialProvider {
        private final List<Credential> store = new ArrayList<>();
        boolean available = true;

        void add(Credential credential) {
            store.add(credential);
        }

        @Override
        public @NotNull String getDisplayName() {
            return "Fake Provider";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public @NotNull List<CredentialDescriptor> listCredentials() {
            return Collections.emptyList();
        }

        @Override
        public @Nullable Credential getCredential(@NotNull UUID credentialId) {
            for (Credential c : store) {
                if (c.accountId().equals(credentialId)) return c;
            }
            return null;
        }

        @Override
        public @Nullable Credential promptForCredential() {
            return null;
        }
    }
}
