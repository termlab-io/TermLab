package com.conch.core.filepicker;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Built-in {@link FileSource} for the local filesystem via
 * {@link java.nio.file.Files}. Always registered via
 * {@link LocalFileSourceProvider}.
 */
public final class LocalFileSource implements FileSource {

    @Override
    public @NotNull String id() {
        return "local";
    }

    @Override
    public @NotNull String label() {
        return "Local";
    }

    @Override
    public @NotNull Icon icon() {
        return AllIcons.Nodes.HomeFolder;
    }

    @Override
    public @NotNull String initialPath() {
        return System.getProperty("user.home");
    }

    @Override
    public void open(@NotNull Project project, @NotNull Object owner) {
        // No-op for local.
    }

    @Override
    public void close(@NotNull Object owner) {
        // No-op for local.
    }

    @Override
    public @NotNull List<FileEntry> list(@NotNull String absolutePath) throws IOException {
        Path dir = Paths.get(absolutePath);
        List<FileEntry> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                try {
                    result.add(LocalFileEntry.of(it.next()));
                } catch (IOException ignored) {
                    // Skip unreadable entries but keep listing.
                }
            }
        }
        return result;
    }

    @Override
    public boolean isDirectory(@NotNull String absolutePath) {
        return Files.isDirectory(Paths.get(absolutePath));
    }

    @Override
    public boolean exists(@NotNull String absolutePath) {
        return Files.exists(Paths.get(absolutePath));
    }

    @Override
    public @Nullable String parentOf(@NotNull String absolutePath) {
        Path parent = Paths.get(absolutePath).getParent();
        return parent == null ? null : parent.toString();
    }

    @Override
    public @NotNull String resolve(@NotNull String directoryPath, @NotNull String childName) {
        return Paths.get(directoryPath).resolve(childName).toString();
    }

    @Override
    public void writeFile(@NotNull String absolutePath, byte @NotNull [] content) throws IOException {
        Files.write(Paths.get(absolutePath), content,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public @NotNull InputStream readFile(@NotNull String absolutePath) throws IOException {
        return Files.newInputStream(Paths.get(absolutePath));
    }
}
