package com.termlab.core.filepicker;

import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Extension point interface that contributes file sources to the
 * unified file picker. Providers may return a dynamic list of
 * sources (e.g., one source per configured SFTP host). The dialog
 * flat-maps {@code listSources()} across every registered provider
 * to build its source dropdown.
 */
public interface FileSourceProvider {

    ExtensionPointName<FileSourceProvider> EP_NAME =
        ExtensionPointName.create("com.termlab.core.fileSourceProvider");

    @NotNull List<FileSource> listSources();
}
