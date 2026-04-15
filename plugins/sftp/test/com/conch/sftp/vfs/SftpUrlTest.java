package com.conch.sftp.vfs;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SftpUrlTest {

    private static final UUID UUID_A = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID UUID_B = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void composeBuildsExpectedUrl() {
        String url = SftpUrl.compose(UUID_A, "/etc/nginx/nginx.conf");
        assertEquals("sftp://550e8400-e29b-41d4-a716-446655440000//etc/nginx/nginx.conf", url);
    }

    @Test
    void composeWithRootPath() {
        String url = SftpUrl.compose(UUID_A, "/");
        assertEquals("sftp://550e8400-e29b-41d4-a716-446655440000//", url);
    }

    @Test
    void parseRoundTrips() {
        String url = "sftp://550e8400-e29b-41d4-a716-446655440000//etc/nginx/nginx.conf";
        SftpUrl parsed = SftpUrl.parse(url);
        assertNotNull(parsed);
        assertEquals(UUID_A, parsed.hostId());
        assertEquals("/etc/nginx/nginx.conf", parsed.remotePath());
        assertEquals(url, SftpUrl.compose(parsed.hostId(), parsed.remotePath()));
    }

    @Test
    void parseWithRootPath() {
        SftpUrl parsed = SftpUrl.parse("sftp://550e8400-e29b-41d4-a716-446655440000//");
        assertNotNull(parsed);
        assertEquals(UUID_A, parsed.hostId());
        assertEquals("/", parsed.remotePath());
    }

    @Test
    void parseWithSpacesInPath() {
        SftpUrl parsed = SftpUrl.parse("sftp://550e8400-e29b-41d4-a716-446655440000//tmp/with space/file.txt");
        assertNotNull(parsed);
        assertEquals("/tmp/with space/file.txt", parsed.remotePath());
    }

    @Test
    void parseRejectsMissingProtocol() {
        assertNull(SftpUrl.parse("550e8400-e29b-41d4-a716-446655440000//etc/foo"));
    }

    @Test
    void parseRejectsWrongProtocol() {
        assertNull(SftpUrl.parse("file:///etc/foo"));
    }

    @Test
    void parseRejectsMissingDoubleSlash() {
        // hostId without the double-slash separator is malformed
        assertNull(SftpUrl.parse("sftp://550e8400-e29b-41d4-a716-446655440000/etc/foo"));
    }

    @Test
    void parseRejectsMissingHostId() {
        assertNull(SftpUrl.parse("sftp:////etc/foo"));
    }

    @Test
    void parseRejectsInvalidUuid() {
        assertNull(SftpUrl.parse("sftp://not-a-uuid//etc/foo"));
    }

    @Test
    void differentHostsCompareUnequal() {
        SftpUrl a = new SftpUrl(UUID_A, "/etc/foo");
        SftpUrl b = new SftpUrl(UUID_B, "/etc/foo");
        assertEquals(false, a.equals(b));
    }
}
