package com.termlab.editor.scratch;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.*;
import java.util.List;

/**
 * Opens an empty scratch buffer as a {@link LightVirtualFile} in
 * the main editor area. Pops up a curated file-type picker first.
 * First save triggers a Save-As dialog via {@link SaveAsHelper}.
 */
public final class NewScratchAction extends AnAction {

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
        String filename = "scratch-" + n + option.extension();

        // Two-arg LightVirtualFile: leaves myFileType=null so getFileType()
        // falls through to FileTypeManager.getFileTypeByFile(vfile), which
        // runs FileTypeIdentifiableByVirtualFile.isMyFileType on every
        // registered impl — including TextMate's. For a filename like
        // "scratch-1.java" with TextMate bundles loaded, TextMate claims
        // the file and applies its Java grammar for highlighting.
        LightVirtualFile file = new LightVirtualFile(filename, "");
        file.putUserData(ScratchMarker.KEY, Boolean.TRUE);

        FileEditorManager.getInstance(project).openFile(file, true);
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
