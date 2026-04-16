package com.termlab.editor.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinarySnifferTest {

    @Test
    void emptyFileIsNotBinary(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("empty");
        Files.createFile(f);
        assertFalse(BinarySniffer.isBinary(f));
    }

    @Test
    void plainTextIsNotBinary(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("text.txt");
        Files.writeString(f, "hello world\nlorem ipsum\n");
        assertFalse(BinarySniffer.isBinary(f));
    }

    @Test
    void fileWithNullByteAtStartIsBinary(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("bin");
        Files.write(f, new byte[]{0x00, 0x01, 0x02});
        assertTrue(BinarySniffer.isBinary(f));
    }

    @Test
    void fileWithNullByteInMiddleIsBinary(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("bin");
        byte[] bytes = new byte[]{'a', 'b', 'c', 0x00, 'd', 'e'};
        Files.write(f, bytes);
        assertTrue(BinarySniffer.isBinary(f));
    }

    @Test
    void nullByteAfterFirst8KBIsNotChecked(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("big");
        byte[] bytes = new byte[16 * 1024];
        // pure text in the first 8KB
        java.util.Arrays.fill(bytes, 0, 8 * 1024, (byte) 'a');
        // a null byte at position 10000 (outside the window)
        bytes[10_000] = 0x00;
        Files.write(f, bytes);
        assertFalse(BinarySniffer.isBinary(f));
    }

    @Test
    void nullByteAt8KBOneIsIgnored(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("edge");
        byte[] bytes = new byte[9 * 1024];
        java.util.Arrays.fill(bytes, (byte) 'a');
        bytes[8 * 1024] = 0x00; // first byte past the window
        Files.write(f, bytes);
        assertFalse(BinarySniffer.isBinary(f));
    }

    @Test
    void fileSmallerThan8KBWithNullIsBinary(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("small");
        byte[] bytes = new byte[100];
        java.util.Arrays.fill(bytes, (byte) 'a');
        bytes[50] = 0x00;
        Files.write(f, bytes);
        assertTrue(BinarySniffer.isBinary(f));
    }
}
