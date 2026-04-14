package com.conch.editor.scratch;

import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

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

        Logger log = Logger.getInstance(NewScratchAction.class);
        log.warn("CONCH_EDITOR_DEBUG: createAndOpen for filename=" + filename);

        FileTypeManager ftm = FileTypeManager.getInstance();
        log.warn("CONCH_EDITOR_DEBUG: FileTypeManager class=" + ftm.getClass().getName());

        FileType resolved = ftm.getFileTypeByFileName(filename);
        log.warn("CONCH_EDITOR_DEBUG: resolved fileType class=" + resolved.getClass().getName()
            + " name=" + resolved.getName()
            + " description=" + resolved.getDescription()
            + " isBinary=" + resolved.isBinary());

        if (resolved instanceof LanguageFileType lft) {
            log.warn("CONCH_EDITOR_DEBUG: is LanguageFileType; language=" + lft.getLanguage().getID()
                + " languageClass=" + lft.getLanguage().getClass().getName());
        } else {
            log.warn("CONCH_EDITOR_DEBUG: NOT a LanguageFileType — will fall back to PlainTextLanguage");
        }

        // Dump ALL registered file types that mention "java" or "text" to see if TextMate registered anything
        int count = 0;
        for (FileType ft : ftm.getRegisteredFileTypes()) {
            String fn = ft.getName().toLowerCase();
            String cn = ft.getClass().getName().toLowerCase();
            if (fn.contains("java") || fn.contains("text") || cn.contains("textmate")) {
                log.warn("CONCH_EDITOR_DEBUG: registered fileType name=" + ft.getName()
                    + " class=" + ft.getClass().getName());
                if (++count > 15) break;
            }
        }

        // Check whether TextMateServiceImpl is even on the classpath
        try {
            Class<?> tmService = Class.forName(
                "org.jetbrains.plugins.textmate.TextMateService");
            log.warn("CONCH_EDITOR_DEBUG: TextMateService class found: " + tmService.getName());
            Object svc = ApplicationManager.getApplication().getService(tmService);
            log.warn("CONCH_EDITOR_DEBUG: TextMateService instance=" + (svc == null ? "NULL" : svc.getClass().getName()));
        } catch (ClassNotFoundException e) {
            log.warn("CONCH_EDITOR_DEBUG: TextMateService class NOT FOUND on classpath: " + e.getMessage());
        } catch (Throwable t) {
            log.warn("CONCH_EDITOR_DEBUG: TextMateService lookup threw: " + t.getClass().getName() + ": " + t.getMessage());
        }

        // Check the bundles directory that TextMateServiceImpl expects
        try {
            String home = com.intellij.openapi.application.PathManager.getHomePath();
            log.warn("CONCH_EDITOR_DEBUG: PathManager.getHomePath=" + home);
            java.nio.file.Path bundlesPath = java.nio.file.Paths.get(home, "plugins", "textmate", "lib", "bundles");
            log.warn("CONCH_EDITOR_DEBUG: computed bundles path=" + bundlesPath + " exists=" + java.nio.file.Files.exists(bundlesPath));
            if (java.nio.file.Files.exists(bundlesPath)) {
                try (java.util.stream.Stream<java.nio.file.Path> list = java.nio.file.Files.list(bundlesPath)) {
                    long n2 = list.count();
                    log.warn("CONCH_EDITOR_DEBUG: bundles path contains " + n2 + " entries");
                }
            }
        } catch (Throwable t) {
            log.warn("CONCH_EDITOR_DEBUG: bundles path check failed: " + t.getMessage());
        }

        Language language = (resolved instanceof LanguageFileType lft)
                ? lft.getLanguage()
                : PlainTextLanguage.INSTANCE;

        VirtualFile file = ScratchRootType.getInstance().createScratchFile(
            project, filename, language, "", ScratchFileService.Option.create_new_always);
        if (file == null) return;
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
                String probe = "scratch" + option.extension();
                setIcon(FileTypeManager.getInstance().getFileTypeByFileName(probe).getIcon());
            }
            return this;
        }
    }
}
