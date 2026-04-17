package com.termlab.editor.ui;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * TermLab doesn't support breakpoints or other gutter icon workflows, so keep
 * the light editor gutter as narrow as possible while preserving line numbers.
 */
public final class LightEditorGutterCompactor implements EditorFactoryListener {

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        if (editor.getEditorKind() != EditorKind.MAIN_EDITOR) return;
        if (editor.isViewer()) return;
        if (editor.getProject() == null || editor.getProject().isDefault()) return;

        VirtualFile file = editor.getVirtualFile();
        if (file == null) return;

        EditorSettings settings = editor.getSettings();
        settings.setGutterIconsShown(false);
        settings.setLineMarkerAreaShown(false);
        settings.setLineNumbersShown(true);
    }
}
