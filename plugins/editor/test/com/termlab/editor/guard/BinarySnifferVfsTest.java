package com.termlab.editor.guard;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinarySnifferVfsTest {

    @Test
    void plainTextLightVfileIsNotBinary() {
        VirtualFile file = bytes("test.txt", "hello world\nlorem ipsum\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(BinarySniffer.isBinaryByContent(file));
    }

    @Test
    void emptyLightVfileIsNotBinary() {
        VirtualFile file = bytes("empty", new byte[0]);
        assertFalse(BinarySniffer.isBinaryByContent(file));
    }

    @Test
    void lightVfileWithNullByteIsBinary() {
        VirtualFile file = bytes("bin", new byte[]{'a', 'b', 0x00, 'c'});
        assertTrue(BinarySniffer.isBinaryByContent(file));
    }

    // ---------------------------------------------------------------------------
    // Minimal byte-array VirtualFile stub — no platform bootstrap required
    // ---------------------------------------------------------------------------

    private static VirtualFile bytes(String name, byte[] content) {
        return new StubVirtualFile(name, content);
    }

    private static final class StubVirtualFile extends VirtualFile {
        private final String myName;
        private final byte[] myContent;

        StubVirtualFile(String name, byte[] content) {
            myName = name;
            myContent = content;
        }

        @Override public @NotNull String getName() { return myName; }
        @Override public @NotNull VirtualFileSystem getFileSystem() { throw new UnsupportedOperationException(); }
        @Override public @NotNull String getPath() { return "/" + myName; }
        @Override public boolean isWritable() { return false; }
        @Override public boolean isDirectory() { return false; }
        @Override public boolean isValid() { return true; }
        @Override public VirtualFile getParent() { return null; }
        @Override public VirtualFile[] getChildren() { return VirtualFile.EMPTY_ARRAY; }
        @Override public @NotNull OutputStream getOutputStream(Object requestor, long newModificationStamp, long newTimeStamp) { throw new UnsupportedOperationException(); }
        @Override public byte @NotNull [] contentsToByteArray() { return myContent.clone(); }
        @Override public long getTimeStamp() { return 0; }
        @Override public long getLength() { return myContent.length; }
        @Override public void refresh(boolean asynchronous, boolean recursive, Runnable postRunnable) {}
        @Override public @NotNull InputStream getInputStream() throws IOException { return new ByteArrayInputStream(myContent); }
    }
}
