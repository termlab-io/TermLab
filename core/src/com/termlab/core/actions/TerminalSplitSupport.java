package com.termlab.core.actions;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.ui.Splitter;
import org.jetbrains.annotations.Nullable;

final class TerminalSplitSupport {
    private TerminalSplitSupport() {}

    static void normalizeNewSplit(@Nullable EditorWindow newWindow) {
        if (newWindow == null || newWindow.isDisposed()) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (newWindow.isDisposed()) {
                return;
            }
            if (newWindow.getTabbedPane().getComponent().getParent() instanceof Splitter splitter) {
                splitter.setHonorComponentsMinimumSize(false);
                splitter.setProportion(0.5f);
                splitter.revalidate();
                splitter.repaint();
            }
        });
    }
}
