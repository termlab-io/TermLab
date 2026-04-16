package com.termlab.ssh.palette;

import com.termlab.ssh.actions.ConnectToHostAction;
import com.termlab.ssh.model.HostStore;
import com.termlab.ssh.model.SshHost;
import com.termlab.ssh.toolwindow.HostCellRenderer;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Exposes saved {@link SshHost} entries in the TermLab command palette
 * (Cmd+Shift+P → Hosts tab). Selecting a host dispatches
 * {@link ConnectToHostAction#run(Project, SshHost)}, which opens a new
 * editor tab connected to the host — the same path the tool window's
 * double-click uses.
 *
 * <p>Structured like {@code TerminalPaletteContributor}: the contributor
 * itself holds the project reference and reads {@link HostStore} fresh
 * on every {@code fetchElements} call, so tool-window adds/removes show
 * up in the palette immediately.
 */
public final class HostsSearchEverywhereContributor implements SearchEverywhereContributor<SshHost> {

    private final Project project;

    public HostsSearchEverywhereContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override public @NotNull String getSearchProviderId() { return "TermLabHosts"; }
    @Override public @NotNull String getGroupName() { return "Hosts"; }
    @Override public int getSortWeight() { return 50; }
    @Override public boolean showInFindResults() { return false; }
    @Override public boolean isShownInSeparateTab() { return true; }
    @Override public boolean isEmptyPatternSupported() { return true; }

    @Override
    public void fetchElements(@NotNull String pattern,
                               @NotNull ProgressIndicator progressIndicator,
                               @NotNull Processor<? super SshHost> consumer) {
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return;

        String q = pattern.toLowerCase();
        List<SshHost> hits = store.getHosts().stream()
            .filter(h -> q.isEmpty() || matches(h, q))
            .sorted(Comparator.comparing(SshHost::label, String.CASE_INSENSITIVE_ORDER))
            .toList();

        for (SshHost host : hits) {
            if (progressIndicator.isCanceled()) return;
            if (!consumer.process(host)) return;
        }
    }

    private static boolean matches(@NotNull SshHost host, @NotNull String q) {
        return host.label().toLowerCase().contains(q)
            || host.host().toLowerCase().contains(q)
            || host.username().toLowerCase().contains(q);
    }

    @Override
    public boolean processSelectedItem(@NotNull SshHost selected, int modifiers, @NotNull String searchText) {
        ConnectToHostAction.run(project, selected);
        return true;
    }

    @Override
    public @NotNull ListCellRenderer<? super SshHost> getElementsRenderer() {
        return new HostCellRenderer();
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull SshHost element, @NotNull String dataId) {
        return Objects.equals(dataId, "com.termlab.ssh.host") ? element : null;
    }

    /**
     * Factory registered under {@code <searchEverywhereContributor>} in
     * the SSH plugin's {@code plugin.xml}. IntelliJ calls this once per
     * Search Everywhere invocation with a fresh {@link AnActionEvent}
     * that carries the current project.
     */
    public static final class Factory implements SearchEverywhereContributorFactory<SshHost> {
        @Override
        public @NotNull SearchEverywhereContributor<SshHost> createContributor(@NotNull AnActionEvent initEvent) {
            Project project = Objects.requireNonNull(
                initEvent.getProject(),
                "Project required for HostsSearchEverywhereContributor");
            return new HostsSearchEverywhereContributor(project);
        }
    }
}
