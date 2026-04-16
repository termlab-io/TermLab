package com.termlab.core.filepicker;

import org.jetbrains.annotations.NotNull;

/**
 * Return value of {@code UnifiedFilePickerDialog.showSaveDialog} and
 * {@code showOpenDialog}. The caller uses
 * {@code result.source().writeFile(result.absolutePath(), bytes)} for
 * save flows and {@code result.source().readFile(result.absolutePath())}
 * for open flows.
 */
public record FilePickerResult(
    @NotNull FileSource source,
    @NotNull String absolutePath
) {}
