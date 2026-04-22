package com.termlab.editor.scratch;

import com.intellij.icons.AllIcons;
import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.*;
import java.io.IOException;
import java.util.List;

/**
 * Opens an empty scratch buffer from the platform scratch filesystem in the
 * main editor area. Using a real scratch {@link VirtualFile} keeps editor
 * plugins such as IdeaVim on their normal file-editor path.
 */
public final class NewScratchAction extends AnAction implements DumbAware {

    private record ScratchOption(String label, String extension, Icon icon) {}

    private static final List<ScratchOption> OPTIONS = List.of(
        new ScratchOption("Plain Text",  ".txt",        AllIcons.FileTypes.Text),
        new ScratchOption("Markdown",    ".md",         AllIcons.FileTypes.Text),
        new ScratchOption("JSON",        ".json",       AllIcons.FileTypes.Json),
        new ScratchOption("YAML",        ".yaml",       AllIcons.FileTypes.Yaml),
        new ScratchOption("TOML",        ".toml",       AllIcons.FileTypes.Config),
        new ScratchOption("XML",         ".xml",        AllIcons.FileTypes.Xml),
        new ScratchOption("INI",         ".ini",        AllIcons.FileTypes.Config),
        new ScratchOption("Shell",       ".sh",         AllIcons.FileTypes.Text),
        new ScratchOption("Python",      ".py",         AllIcons.FileTypes.Text),
        new ScratchOption("Ruby",        ".rb",         AllIcons.FileTypes.Text),
        new ScratchOption("JavaScript",  ".js",         AllIcons.FileTypes.JavaScript),
        new ScratchOption("TypeScript",  ".ts",         AllIcons.FileTypes.JavaScript),
        new ScratchOption("HTML",        ".html",       AllIcons.FileTypes.Html),
        new ScratchOption("CSS",         ".css",        AllIcons.FileTypes.Css),
        new ScratchOption("SQL",         ".sql",        AllIcons.FileTypes.Text),
        new ScratchOption("Java",        ".java",       AllIcons.FileTypes.Java),
        new ScratchOption("Kotlin",      ".kt",         AllIcons.FileTypes.Text),
        new ScratchOption("Go",          ".go",         AllIcons.FileTypes.Text),
        new ScratchOption("Rust",        ".rs",         AllIcons.FileTypes.Text),
        new ScratchOption("C",           ".c",          AllIcons.FileTypes.Text),
        new ScratchOption("C++",         ".cpp",        AllIcons.FileTypes.Text),
        new ScratchOption("Lua",         ".lua",        AllIcons.FileTypes.Text),
        new ScratchOption("PHP",         ".php",        AllIcons.FileTypes.Text),
        new ScratchOption("Dockerfile",  ".dockerfile", AllIcons.FileTypes.Config),
        new ScratchOption("Makefile",    ".makefile",   AllIcons.FileTypes.Text)
    );

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        if (project == null) return;

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(OPTIONS)
            .setTitle("New Scratch File")
            .setVisibleRowCount(5)
            .setNamerForFiltering(ScratchOption::label)
            .setRenderer(new ScratchOptionCellRenderer())
            .setItemChosenCallback(option -> createAndOpen(project, option))
            .setMovable(false)
            .setResizable(false)
            .createPopup()
            .showCenteredInCurrentWindow(project);
    }

    private static void createAndOpen(@NotNull Project project, @NotNull ScratchOption option) {
        int n = ScratchCounter.next();
        String filename = "untitled-" + n + option.extension();
        VirtualFile file = createScratchFile(project, filename);
        if (file == null) {
            return;
        }

        ScratchMarker.mark(file);
        FileEditorManager.getInstance(project).openFile(file, true);
    }

    private static @Nullable VirtualFile createScratchFile(@NotNull Project project, @NotNull String filename) {
        try {
            return ScratchFileService.getInstance().findFile(
                ScratchRootType.getInstance(),
                filename,
                ScratchFileService.Option.create_new_always);
        } catch (IOException ignored) {
            return null;
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static final class ScratchOptionCellRenderer
            extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(
            javax.swing.JList<?> list, Object value, int index,
            boolean isSelected, boolean cellHasFocus
        ) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ScratchOption option) {
                setText(option.label());
                setIcon(option.icon());
            }
            return this;
        }
    }
}
