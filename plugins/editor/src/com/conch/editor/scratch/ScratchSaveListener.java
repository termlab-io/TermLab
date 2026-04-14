package com.conch.editor.scratch;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Intercepts saves of scratch {@link LightVirtualFile}s, shows a
 * Save-As dialog, writes the buffer to the chosen location, and
 * swaps the editor tab to point at the real file.
 */
public final class ScratchSaveListener implements FileDocumentManagerListener {

    @Override
    public void beforeDocumentSaving(@NotNull Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (!(file instanceof LightVirtualFile lvf)) return;
        if (lvf.getUserData(ScratchMarker.KEY) != Boolean.TRUE) return;

        // Scratch saves happen on the EDT (save All is EDT). Dialog
        // call is synchronous — this is the correct thread.
        Project project = firstOpenProject();
        if (project == null) return;

        FileSaverDescriptor descriptor = new FileSaverDescriptor(
            "Save Scratch As",
            "Choose a location for " + lvf.getName());
        FileSaverDialog dialog = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project);
        VirtualFileWrapper wrapper = dialog.save(lvf.getName());
        if (wrapper == null) return;

        try {
            java.nio.file.Path target = wrapper.getFile().toPath();
            java.nio.file.Files.writeString(target, document.getText(),
                java.nio.charset.StandardCharsets.UTF_8);
            VirtualFile saved = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
            if (saved == null) saved = VfsUtil.findFileByIoFile(wrapper.getFile(), true);

            // Close the scratch tab and open the new real file.
            FileEditorManager mgr = FileEditorManager.getInstance(project);
            mgr.closeFile(lvf);
            if (saved != null) {
                final VirtualFile toOpen = saved;
                ApplicationManager.getApplication().invokeLater(() -> mgr.openFile(toOpen, true));
            }
        } catch (IOException e) {
            // Leave the tab open; user can try again. A minimal
            // notification isn't critical here — the dialog error
            // surface is enough for MVP.
        }
    }

    private static Project firstOpenProject() {
        Project[] open = ProjectManager.getInstance().getOpenProjects();
        return open.length == 0 ? null : open[0];
    }
}
