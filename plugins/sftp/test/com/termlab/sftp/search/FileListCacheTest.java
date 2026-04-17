package com.termlab.sftp.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FileListCacheTest {

    @Test
    void beginsBuildAndStoresReadySnapshot() {
        FileListCache cache = new FileListCache();

        long token = cache.beginBuild("/tmp/demo");
        cache.complete(token, "RG", List.of("/tmp/demo/a.txt", "/tmp/demo/b.txt"));

        FileListCache.Snapshot snapshot = cache.snapshot();
        assertEquals(FileListCache.State.READY, snapshot.state());
        assertEquals("/tmp/demo", snapshot.root());
        assertEquals("RG", snapshot.toolId());
        assertEquals(List.of("/tmp/demo/a.txt", "/tmp/demo/b.txt"), snapshot.paths());
        assertFalse(snapshot.truncated());
        assertNull(snapshot.failureMessage());
    }

    @Test
    void staleCompletionIsIgnoredAfterInvalidate() {
        FileListCache cache = new FileListCache();

        long oldToken = cache.beginBuild("/tmp/one");
        cache.invalidate();
        long newToken = cache.beginBuild("/tmp/two");

        cache.complete(oldToken, "RG", List.of("/tmp/one/ignored.txt"));
        cache.complete(newToken, "FIND", List.of("/tmp/two/kept.txt"));

        FileListCache.Snapshot snapshot = cache.snapshot();
        assertEquals(FileListCache.State.READY, snapshot.state());
        assertEquals("/tmp/two", snapshot.root());
        assertEquals(List.of("/tmp/two/kept.txt"), snapshot.paths());
        assertEquals("FIND", snapshot.toolId());
    }

    @Test
    void truncatesOversizedListingsAtCap() {
        FileListCache cache = new FileListCache();
        long token = cache.beginBuild("/tmp/huge");

        List<String> huge = new ArrayList<>(FileListCache.MAX_PATHS + 50);
        for (int i = 0; i < FileListCache.MAX_PATHS + 50; i++) {
            huge.add("/tmp/huge/file-" + i);
        }

        cache.complete(token, "RG", huge);

        FileListCache.Snapshot snapshot = cache.snapshot();
        assertEquals(FileListCache.State.READY, snapshot.state());
        assertEquals(FileListCache.MAX_PATHS, snapshot.paths().size());
        assertTrue(snapshot.truncated());
    }

    @Test
    void recordsFailureMessage() {
        FileListCache cache = new FileListCache();
        long token = cache.beginBuild("/tmp/bad");

        cache.fail(token, "permission denied");

        FileListCache.Snapshot snapshot = cache.snapshot();
        assertEquals(FileListCache.State.FAILED, snapshot.state());
        assertEquals("permission denied", snapshot.failureMessage());
        assertTrue(snapshot.paths().isEmpty());
    }
}
