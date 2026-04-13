package com.conch.sftp.palette;

import com.conch.sftp.toolwindow.RemoteFilePane;
import com.conch.sftp.toolwindow.SftpToolWindow;
import com.conch.sftp.toolwindow.SftpToolWindowFactory;
import com.conch.ssh.model.HostStore;
import com.conch.ssh.model.SshHost;
import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Search Everywhere "SFTP" tab — exposes a row for opening the SFTP
 * tool window plus one row per saved {@link SshHost} for connecting
 * SFTP directly to that host.
 *
 * <p>Selecting the "open" row simply activates the tool window.
 * Selecting a host row activates the tool window and then kicks off
 * a connect through {@link RemoteFilePane#connectTo(UUID)}.
 */
public final class SftpSearchEverywhereContributor
    implements SearchEverywhereContributor<SftpSearchEverywhereContributor.Entry> {

    private final Project project;

    public SftpSearchEverywhereContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override public @NotNull String getSearchProviderId() { return "ConchSftp"; }
    @Override public @NotNull String getGroupName() { return "SFTP"; }
    @Override public int getSortWeight() { return 46; }
    @Override public boolean showInFindResults() { return false; }
    @Override public boolean isShownInSeparateTab() { return true; }
    @Override public boolean isEmptyPatternSupported() { return true; }

    @Override
    public void fetchElements(@NotNull String pattern,
                              @NotNull ProgressIndicator progressIndicator,
                              @NotNull Processor<? super Entry> consumer) {
        String q = pattern.toLowerCase();
        List<Entry> hits = new ArrayList<>();

        Entry openWindow = Entry.openWindow();
        if (q.isEmpty() || openWindow.label.toLowerCase().contains(q)) {
            hits.add(openWindow);
        }

        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store != null) {
            store.getHosts().stream()
                .sorted(Comparator.comparing(SshHost::label, String.CASE_INSENSITIVE_ORDER))
                .forEach(host -> {
                    Entry entry = Entry.connectHost(host);
                    if (q.isEmpty() || entry.label.toLowerCase().contains(q)) {
                        hits.add(entry);
                    }
                });
        }

        for (Entry entry : hits) {
            if (progressIndicator.isCanceled()) return;
            if (!consumer.process(entry)) return;
        }
    }

    @Override
    public boolean processSelectedItem(@NotNull Entry selected, int modifiers, @NotNull String searchText) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SftpToolWindowFactory.ID);
        if (toolWindow == null) return true;
        toolWindow.show(() -> {
            if (selected.hostId == null) return;
            Content content = toolWindow.getContentManager().getSelectedContent();
            if (content == null) return;
            if (content.getComponent() instanceof SftpToolWindow panel) {
                panel.remotePane().connectTo(selected.hostId);
            }
        });
        return true;
    }

    @Override
    public @NotNull ListCellRenderer<? super Entry> getElementsRenderer() {
        return new EntryRenderer();
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull Entry element, @NotNull String dataId) {
        return null;
    }

    public record Entry(@NotNull String label, @Nullable UUID hostId) {
        static @NotNull Entry openWindow() {
            return new Entry("Open SFTP tool window", null);
        }

        static @NotNull Entry connectHost(@NotNull SshHost host) {
            return new Entry("Connect SFTP to " + host.label()
                + " (" + host.host() + ":" + host.port() + ")", host.id());
        }
    }

    private static final class EntryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
            JList<?> list, Object value, int index,
            boolean isSelected, boolean cellHasFocus
        ) {
            Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Entry entry) {
                setText(entry.label);
                setIcon(entry.hostId == null
                    ? AllIcons.Nodes.ExtractedFolder
                    : AllIcons.Webreferences.Server);
            }
            return c;
        }
    }

    public static final class Factory implements SearchEverywhereContributorFactory<Entry> {
        @Override
        public @NotNull SearchEverywhereContributor<Entry> createContributor(@NotNull AnActionEvent initEvent) {
            Project project = Objects.requireNonNull(
                initEvent.getProject(),
                "Project required for SftpSearchEverywhereContributor");
            return new SftpSearchEverywhereContributor(project);
        }
    }
}
