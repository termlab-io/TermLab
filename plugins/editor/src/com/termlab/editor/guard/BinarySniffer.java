package com.termlab.editor.guard;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cheap binary-content heuristic matching git's strategy: a file
 * is considered binary if any of its first 8 KB contains a null
 * byte. Files shorter than 8 KB are fully scanned.
 */
public final class BinarySniffer {

    private static final int SNIFF_BYTES = 8 * 1024;

    private BinarySniffer() {}

    public static boolean isBinary(@NotNull Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = in.readNBytes(SNIFF_BYTES);
            for (byte b : buf) {
                if (b == 0) return true;
            }
            return false;
        }
    }

    public static boolean isBinaryByContent(@NotNull VirtualFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] buf = in.readNBytes(SNIFF_BYTES);
            for (byte b : buf) {
                if (b == 0) return true;
            }
            return false;
        } catch (IOException e) {
            // Treat read failures as binary so we err on the side of refusing
            return true;
        }
    }
}
