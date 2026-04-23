package com.termlab.proxmox.credentials;

import com.termlab.sdk.CredentialProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PveCredentialResolverTest {
    @Test
    void resolvesOnlyApiTokenCredentials() {
        UUID tokenId = UUID.randomUUID();
        PveCredentialResolver resolver = new PveCredentialResolver(List.of(new FakeProvider(List.of(
            credential(tokenId, CredentialProvider.AuthMethod.API_TOKEN),
            credential(UUID.randomUUID(), CredentialProvider.AuthMethod.PASSWORD)
        ))));

        PveApiToken token = resolver.resolve(tokenId);

        assertNotNull(token);
        assertEquals("PVEAPIToken=root@pam!termlab=secret", token.authorizationValue());
        assertNull(resolver.resolve(UUID.randomUUID()));
    }

    @Test
    void listsOnlyApiTokenDescriptors() {
        UUID tokenId = UUID.randomUUID();
        PveCredentialResolver resolver = new PveCredentialResolver(List.of(new FakeProvider(List.of(
            credential(tokenId, CredentialProvider.AuthMethod.API_TOKEN),
            credential(UUID.randomUUID(), CredentialProvider.AuthMethod.PASSWORD)
        ))));

        assertEquals(List.of(tokenId), resolver.listUsableDescriptors().stream()
            .map(CredentialProvider.CredentialDescriptor::id)
            .toList());
    }

    private static CredentialProvider.Credential credential(UUID id, CredentialProvider.AuthMethod method) {
        return new CredentialProvider.Credential(
            id,
            method == CredentialProvider.AuthMethod.API_TOKEN ? "PVE" : "Password",
            "root@pam!termlab",
            method,
            "secret".toCharArray(),
            null,
            null
        );
    }

    private static final class FakeProvider implements CredentialProvider {
        private final List<Credential> credentials;

        private FakeProvider(List<Credential> credentials) {
            this.credentials = credentials;
        }

        @Override
        public String getDisplayName() {
            return "Fake";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<CredentialDescriptor> listCredentials() {
            return credentials.stream()
                .map(credential -> new CredentialDescriptor(
                    credential.accountId(),
                    credential.displayName(),
                    credential.username(),
                    credential.authMethod() == CredentialProvider.AuthMethod.API_TOKEN
                        ? Kind.API_TOKEN
                        : Kind.ACCOUNT_PASSWORD
                ))
                .toList();
        }

        @Override
        public Credential getCredential(UUID credentialId) {
            return credentials.stream()
                .filter(credential -> credential.accountId().equals(credentialId))
                .findFirst()
                .orElse(null);
        }

        @Override
        public Credential promptForCredential() {
            return null;
        }
    }
}
