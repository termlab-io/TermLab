package com.termlab.editor.scratch;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/** Marker key used to distinguish TermLab scratch tabs from normal editor files. */
public final class ScratchMarker {

    public static final Key<Boolean> KEY = Key.create("termlab.editor.scratchMarker");
    public static final Key<Boolean> SKIP_CLOSE_CONFIRMATION_KEY =
        Key.create("termlab.editor.skipCloseConfirmation");

    private ScratchMarker() {}

    public static void mark(@Nullable VirtualFile file) {
        if (file != null) {
            file.putUserData(KEY, Boolean.TRUE);
        }
    }

    public static boolean isMarkedScratch(@Nullable VirtualFile file) {
        return file != null && file.getUserData(KEY) == Boolean.TRUE;
    }

    public static void deleteMarkedScratchFile(@Nullable VirtualFile file, @Nullable Object requestor) {
        if (!isMarkedScratch(file) || file == null || !file.isValid()) {
            return;
        }
        try {
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    file.delete(requestor != null ? requestor : ScratchMarker.class);
                } catch (IOException ignored) {
                }
            });
        } catch (RuntimeException ignored) {
        }
    }
}
