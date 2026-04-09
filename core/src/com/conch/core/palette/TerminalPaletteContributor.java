package com.conch.core.palette;

import com.conch.core.terminal.ConchTerminalVirtualFile;
import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class TerminalPaletteContributor implements SearchEverywhereContributor<Object> {

    private final Project project;

    public TerminalPaletteContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override public @NotNull String getSearchProviderId() { return "ConchTerminals"; }
    @Override public @NotNull String getGroupName() { return "Terminals"; }
    @Override public int getSortWeight() { return 100; }
    @Override public boolean showInFindResults() { return false; }
    @Override public boolean isShownInSeparateTab() { return true; }

    @Override
    public boolean isEmptyPatternSupported() { return true; }

    @Override
    public void fetchElements(@NotNull String pattern,
                               @NotNull ProgressIndicator progressIndicator,
                               @NotNull Processor<? super Object> consumer) {
        FileEditorManager manager = FileEditorManager.getInstance(project);
        String lowerPattern = pattern.toLowerCase();

        for (VirtualFile file : manager.getOpenFiles()) {
            if (progressIndicator.isCanceled()) return;
            if (file instanceof ConchTerminalVirtualFile termFile) {
                String title = termFile.getName().toLowerCase();
                String cwd = termFile.getCurrentWorkingDirectory();
                String cwdLower = cwd != null ? cwd.toLowerCase() : "";

                if (lowerPattern.isEmpty()
                    || title.contains(lowerPattern)
                    || cwdLower.contains(lowerPattern)) {
                    consumer.process(termFile);
                }
            }
        }
    }

    @Override
    public boolean processSelectedItem(@NotNull Object selected, int modifiers, @NotNull String searchText) {
        if (selected instanceof ConchTerminalVirtualFile termFile) {
            FileEditorManager.getInstance(project).openFile(termFile, true);
            return true;
        }
        return false;
    }

    @Override
    public @NotNull ListCellRenderer<? super Object> getElementsRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof ConchTerminalVirtualFile termFile) {
                    String label = termFile.getName();
                    String cwd = termFile.getCurrentWorkingDirectory();
                    if (cwd != null) label += "  \u2014  " + cwd;
                    setText(label);
                    setIcon(AllIcons.Debugger.Console);
                }
                return this;
            }
        };
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull Object element, @NotNull String dataId) {
        return element;
    }

    public static final class Factory implements SearchEverywhereContributorFactory<Object> {
        @Override
        public @NotNull SearchEverywhereContributor<Object> createContributor(@NotNull AnActionEvent initEvent) {
            Project project = java.util.Objects.requireNonNull(
                initEvent.getProject(), "Project required for TerminalPaletteContributor");
            return new TerminalPaletteContributor(project);
        }
    }
}
