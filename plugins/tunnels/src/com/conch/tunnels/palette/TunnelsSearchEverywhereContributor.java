package com.conch.tunnels.palette;

import com.conch.tunnels.model.SshTunnel;
import com.conch.tunnels.model.TunnelStore;
import com.conch.tunnels.toolwindow.TunnelCellRenderer;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Exposes saved {@link SshTunnel} entries in the Conch command palette
 * (Cmd+Shift+P → Tunnels tab). Selecting a tunnel opens and focuses the
 * Tunnels tool window. Full connect-from-SE is v2 scope.
 *
 * <p>Reads {@link TunnelStore} fresh on every {@code fetchElements} call,
 * so tool-window adds/removes show up in the palette immediately.
 */
public final class TunnelsSearchEverywhereContributor implements SearchEverywhereContributor<SshTunnel> {

    private final Project project;

    public TunnelsSearchEverywhereContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override public @NotNull String getSearchProviderId() { return "ConchTunnels"; }
    @Override public @NotNull String getGroupName() { return "Tunnels"; }
    @Override public int getSortWeight() { return 45; }
    @Override public boolean showInFindResults() { return false; }
    @Override public boolean isShownInSeparateTab() { return true; }
    @Override public boolean isEmptyPatternSupported() { return true; }

    @Override
    public void fetchElements(@NotNull String pattern,
                               @NotNull ProgressIndicator progressIndicator,
                               @NotNull Processor<? super SshTunnel> consumer) {
        TunnelStore store = ApplicationManager.getApplication().getService(TunnelStore.class);
        if (store == null) return;

        String q = pattern.toLowerCase();
        List<SshTunnel> hits = store.getTunnels().stream()
            .filter(t -> q.isEmpty() || matches(t, q))
            .sorted(Comparator.comparing(SshTunnel::label, String.CASE_INSENSITIVE_ORDER))
            .toList();

        for (SshTunnel tunnel : hits) {
            if (progressIndicator.isCanceled()) return;
            if (!consumer.process(tunnel)) return;
        }
    }

    private static boolean matches(@NotNull SshTunnel tunnel, @NotNull String q) {
        return tunnel.label().toLowerCase().contains(q)
            || tunnel.targetHost().toLowerCase().contains(q)
            || String.valueOf(tunnel.bindPort()).contains(q);
    }

    @Override
    public boolean processSelectedItem(@NotNull SshTunnel selected, int modifiers, @NotNull String searchText) {
        var toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Tunnels");
        if (toolWindow != null) {
            toolWindow.show();
        }
        return true;
    }

    @Override
    public @NotNull ListCellRenderer<? super SshTunnel> getElementsRenderer() {
        return new TunnelCellRenderer();
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull SshTunnel element, @NotNull String dataId) {
        return Objects.equals(dataId, "com.conch.tunnels.tunnel") ? element : null;
    }

    /**
     * Factory registered under {@code <searchEverywhereContributor>} in
     * the Tunnels plugin's {@code plugin.xml}. IntelliJ calls this once per
     * Search Everywhere invocation with a fresh {@link AnActionEvent}
     * that carries the current project.
     */
    public static final class Factory implements SearchEverywhereContributorFactory<SshTunnel> {
        @Override
        public @NotNull SearchEverywhereContributor<SshTunnel> createContributor(@NotNull AnActionEvent initEvent) {
            Project project = Objects.requireNonNull(
                initEvent.getProject(),
                "Project required for TunnelsSearchEverywhereContributor");
            return new TunnelsSearchEverywhereContributor(project);
        }
    }
}
