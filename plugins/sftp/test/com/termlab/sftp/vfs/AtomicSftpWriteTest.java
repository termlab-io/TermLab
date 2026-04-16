package com.termlab.sftp.vfs;

import org.apache.sshd.sftp.client.SftpClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicSftpWriteTest {

    /**
     * Minimal SftpClient test double that records rename/remove calls
     * and holds file content in a map. We only stub the four methods
     * AtomicSftpWrite actually uses: write, rename, remove.
     *
     * <p>SftpClient is an interface with ~50 methods; we use a
     * reflection Proxy so we only have to implement the four we care
     * about.
     */
    private static final class FakeClient {
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> events = new ArrayList<>();

        /** Configures behavior for specific operations. */
        boolean renameFailsForExisting = false;
        boolean finalRenameAlwaysFails = false;
        boolean restoreFails = false;
        boolean writeFails = false;

        SftpClient asProxy() {
            return (SftpClient) java.lang.reflect.Proxy.newProxyInstance(
                SftpClient.class.getClassLoader(),
                new Class<?>[]{SftpClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "write" -> handleWrite((String) args[0]);
                    case "rename" -> { handleRename((String) args[0], (String) args[1]); yield null; }
                    case "remove" -> { handleRemove((String) args[0]); yield null; }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }

        private OutputStream handleWrite(String path) throws IOException {
            if (writeFails) throw new IOException("simulated write failure");
            events.add("write:" + path);
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    files.put(path, this.toByteArray());
                }
            };
        }

        private void handleRename(String from, String to) throws IOException {
            events.add("rename:" + from + "->" + to);
            if (renameFailsForExisting && files.containsKey(to)) {
                throw new IOException("rename over existing not supported");
            }
            if (finalRenameAlwaysFails && !from.endsWith(".bak")) {
                throw new IOException("simulated final rename failure");
            }
            if (restoreFails && from.endsWith(".bak")) {
                throw new IOException("simulated restore failure");
            }
            if (!files.containsKey(from)) {
                throw new IOException("source does not exist: " + from);
            }
            files.put(to, files.remove(from));
        }

        private void handleRemove(String path) {
            events.add("remove:" + path);
            files.remove(path);
        }
    }

    @Test
    void happyPathWritesAndRenamesSuccessfully() throws IOException {
        FakeClient fake = new FakeClient();
        fake.files.put("/etc/foo.conf", "original".getBytes());
        AtomicSftpWrite.writeAtomically(fake.asProxy(), "/etc/foo.conf", "new content".getBytes());
        assertArrayEquals("new content".getBytes(), fake.files.get("/etc/foo.conf"));
    }

    @Test
    void writeFailureLeavesNoOrphanedTemp() {
        FakeClient fake = new FakeClient();
        fake.writeFails = true;
        fake.files.put("/etc/foo.conf", "original".getBytes());
        assertThrows(IOException.class, () ->
            AtomicSftpWrite.writeAtomically(fake.asProxy(), "/etc/foo.conf", "new".getBytes()));
        // Original should still exist
        assertArrayEquals("original".getBytes(), fake.files.get("/etc/foo.conf"));
        // No orphan temp files
        assertFalse(fake.files.keySet().stream().anyMatch(k -> k.contains(".tmp")));
    }

    @Test
    void fallbackPathBackupsAndRenames() throws IOException {
        FakeClient fake = new FakeClient();
        fake.renameFailsForExisting = true;
        fake.files.put("/etc/foo.conf", "original".getBytes());
        AtomicSftpWrite.writeAtomically(fake.asProxy(), "/etc/foo.conf", "new content".getBytes());
        assertArrayEquals("new content".getBytes(), fake.files.get("/etc/foo.conf"));
        // Backup should have been cleaned up
        assertFalse(fake.files.keySet().stream().anyMatch(k -> k.contains(".bak")));
    }

    @Test
    void fallbackRestoresOriginalOnFinalRenameFailure() {
        FakeClient fake = new FakeClient();
        fake.renameFailsForExisting = true;
        fake.finalRenameAlwaysFails = true;
        fake.files.put("/etc/foo.conf", "original".getBytes());
        assertThrows(IOException.class, () ->
            AtomicSftpWrite.writeAtomically(fake.asProxy(), "/etc/foo.conf", "new".getBytes()));
        // Original should be restored
        assertArrayEquals("original".getBytes(), fake.files.get("/etc/foo.conf"));
    }
}
