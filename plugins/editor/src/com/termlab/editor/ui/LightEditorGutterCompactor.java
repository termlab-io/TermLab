package com.termlab.editor.ui;

import com.termlab.editor.scratch.ScratchMarker;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Keep TermLab scratch tabs visually compact without altering normal editor
 * tabs, so editor plugins still get the expected host UI on local and remote
 * files.
 */
public final class LightEditorGutterCompactor implements EditorFactoryListener {

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        if (editor.getEditorKind() != EditorKind.MAIN_EDITOR) return;
        if (editor.isViewer()) return;
        if (editor.getProject() == null || editor.getProject().isDefault()) return;

        VirtualFile file = editor.getVirtualFile();
        if (!ScratchMarker.isMarkedScratch(file)) return;

        EditorSettings settings = editor.getSettings();
        settings.setGutterIconsShown(false);
        settings.setLineMarkerAreaShown(false);
        settings.setLineNumbersShown(true);
    }
}
