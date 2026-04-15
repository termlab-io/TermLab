package com.conch.core.filepicker;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Built-in provider that always contributes the {@link LocalFileSource}
 * to the unified file picker.
 */
public final class LocalFileSourceProvider implements FileSourceProvider {

    private static final LocalFileSource SINGLETON = new LocalFileSource();

    @Override
    public @NotNull List<FileSource> listSources() {
        return List.of(SINGLETON);
    }
}
