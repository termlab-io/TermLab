package com.termlab.editor.ui;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.EditorMarkupModel;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * TermLab doesn't support breakpoints or other gutter icon workflows, so keep
 * the light editor gutter as narrow as possible while disabling IDE code
 * analysis UI that doesn't apply to a terminal-first product.
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

        if (editor.getMarkupModel() instanceof EditorMarkupModel markupModel) {
            markupModel.setErrorStripeVisible(false);
            markupModel.setTrafficLightIconVisible(false);
        }

        stripInspectionStatusComponent(editor);

        PsiFile psiFile = PsiManager.getInstance(editor.getProject()).findFile(file);
        if (psiFile != null) {
            DaemonCodeAnalyzer analyzer = DaemonCodeAnalyzer.getInstance(editor.getProject());
            analyzer.setHighlightingEnabled(psiFile, false);
            analyzer.setImportHintsEnabled(psiFile, false);
        }
    }

    private static void stripInspectionStatusComponent(@NotNull Editor editor) {
        if (!(editor instanceof EditorEx editorEx)) return;
        if (!(editorEx.getScrollPane() instanceof JBScrollPane scrollPane)) return;

        PropertyChangeListener listener = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent event) {
                if (!"statusComponent".equals(event.getPropertyName())) return;
                if (event.getNewValue() != null) {
                    scrollPane.setStatusComponent(null);
                }
            }
        };

        scrollPane.addPropertyChangeListener(listener);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!editor.isDisposed() && scrollPane.getStatusComponent() != null) {
                scrollPane.setStatusComponent(null);
            }
        });
    }
}
