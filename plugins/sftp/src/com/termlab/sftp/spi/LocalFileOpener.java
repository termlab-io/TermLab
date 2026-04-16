package com.termlab.sftp.spi;

import com.termlab.sftp.model.LocalFileEntry;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Extension point consumed by the SFTP local pane to delegate
 * double-click-to-open behavior. Zero extensions registered (the
 * default) means "do nothing on double-click," which preserves the
 * pane's behavior when the opt-in editor plugin is disabled.
 */
public interface LocalFileOpener {

    ExtensionPointName<LocalFileOpener> EP_NAME =
        ExtensionPointName.create("com.termlab.sftp.localFileOpener");

    /**
     * Open the given local file in whatever editor the caller
     * provides. Errors should be surfaced as UI notifications; this
     * method must not throw.
     */
    void open(@NotNull Project project, @NotNull LocalFileEntry entry);
}
