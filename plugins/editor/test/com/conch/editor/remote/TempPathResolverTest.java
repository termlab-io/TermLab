package com.conch.editor.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempPathResolverTest {

    @Test
    void preservesBasenameWithExtension(@TempDir Path root) {
        Path result = TempPathResolver.resolve(root, "user@host:22", "/etc/nginx/nginx.conf");
        assertEquals("nginx.conf", result.getFileName().toString());
    }

    @Test
    void preservesDotfileBasename(@TempDir Path root) {
        Path result = TempPathResolver.resolve(root, "user@host:22", "/home/me/.bashrc");
        assertEquals(".bashrc", result.getFileName().toString());
    }

    @Test
    void preservesMultiDotBasename(@TempDir Path root) {
        Path result = TempPathResolver.resolve(root, "user@host:22", "/a/b/c/backup.tar.gz");
        assertEquals("backup.tar.gz", result.getFileName().toString());
    }

    @Test
    void differentHostsResolveToDifferentDirectories(@TempDir Path root) {
        Path a = TempPathResolver.resolve(root, "alice@host-a:22", "/tmp/file");
        Path b = TempPathResolver.resolve(root, "bob@host-b:22", "/tmp/file");
        assertNotEquals(a.getParent().getParent(), b.getParent().getParent());
    }

    @Test
    void samePathSameHostResolvesStably(@TempDir Path root) {
        Path a = TempPathResolver.resolve(root, "user@host:22", "/var/log/app.log");
        Path b = TempPathResolver.resolve(root, "user@host:22", "/var/log/app.log");
        assertEquals(a, b);
    }

    @Test
    void samePathDifferentHostsResolveDifferently(@TempDir Path root) {
        Path a = TempPathResolver.resolve(root, "user@host-a:22", "/tmp/f");
        Path b = TempPathResolver.resolve(root, "user@host-b:22", "/tmp/f");
        assertNotEquals(a, b);
    }

    @Test
    void differentPathsSameHostResolveDifferently(@TempDir Path root) {
        Path a = TempPathResolver.resolve(root, "user@host:22", "/tmp/a");
        Path b = TempPathResolver.resolve(root, "user@host:22", "/tmp/b");
        assertNotEquals(a, b);
    }

    @Test
    void resolvedPathIsBeneathRoot(@TempDir Path root) {
        Path result = TempPathResolver.resolve(root, "user@host:22", "/etc/passwd");
        assertTrue(result.startsWith(root));
    }
}
