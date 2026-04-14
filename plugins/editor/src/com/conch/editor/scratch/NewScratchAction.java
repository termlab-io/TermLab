package com.conch.editor.scratch;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;

/**
 * Opens an empty scratch buffer as a {@link LightVirtualFile} in
 * the main editor area. Pops up a curated file-type picker first.
 * First save triggers a Save-As dialog via {@link ScratchSaveListener}.
 */
public final class NewScratchAction extends AnAction {

    record ScratchOption(String label, String extension) {}

    private static final List<ScratchOption> OPTIONS = List.of(
        new ScratchOption("Plain Text",  ".txt"),
        new ScratchOption("Markdown",    ".md"),
        new ScratchOption("JSON",        ".json"),
        new ScratchOption("YAML",        ".yaml"),
        new ScratchOption("TOML",        ".toml"),
        new ScratchOption("XML",         ".xml"),
        new ScratchOption("INI",         ".ini"),
        new ScratchOption("Shell",       ".sh"),
        new ScratchOption("Python",      ".py"),
        new ScratchOption("Ruby",        ".rb"),
        new ScratchOption("JavaScript",  ".js"),
        new ScratchOption("TypeScript",  ".ts"),
        new ScratchOption("HTML",        ".html"),
        new ScratchOption("CSS",         ".css"),
        new ScratchOption("SQL",         ".sql"),
        new ScratchOption("Java",        ".java"),
        new ScratchOption("Kotlin",      ".kt"),
        new ScratchOption("Go",          ".go"),
        new ScratchOption("Rust",        ".rs"),
        new ScratchOption("C",           ".c"),
        new ScratchOption("C++",         ".cpp"),
        new ScratchOption("Lua",         ".lua"),
        new ScratchOption("PHP",         ".php"),
        new ScratchOption("Dockerfile",  ".dockerfile"),
        new ScratchOption("Makefile",    ".makefile")
    );

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        if (project == null) return;

        var step = new BaseListPopupStep<ScratchOption>("New Scratch File", OPTIONS) {
            @Override
            public @NotNull String getTextFor(ScratchOption value) {
                return value.label();
            }

            @Override
            public @Nullable Icon getIconFor(ScratchOption value) {
                String probe = "scratch" + value.extension();
                FileType ft = FileTypeManager.getInstance().getFileTypeByFileName(probe);
                return ft.getIcon();
            }

            @Override
            public boolean isSpeedSearchEnabled() {
                return true;
            }

            @Override
            public PopupStep<?> onChosen(ScratchOption selectedValue, boolean finalChoice) {
                return doFinalStep(() -> createAndOpen(project, selectedValue));
            }
        };

        JBPopupFactory.getInstance()
            .createListPopup(step)
            .showCenteredInCurrentWindow(project);
    }

    private static void createAndOpen(@NotNull Project project, @NotNull ScratchOption option) {
        int n = ScratchCounter.next();
        String filename = "scratch-" + n + option.extension();
        FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(filename);
        LightVirtualFile file = new LightVirtualFile(filename, fileType, "");
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
}
