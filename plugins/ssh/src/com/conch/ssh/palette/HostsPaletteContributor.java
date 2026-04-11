package com.conch.ssh.palette;

import com.conch.sdk.CommandPaletteContributor;
import com.conch.sdk.PaletteItem;
import com.conch.ssh.actions.ConnectToHostAction;
import com.conch.ssh.model.HostStore;
import com.conch.ssh.model.SshHost;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Command palette contributor for saved SSH hosts. Shows every
 * {@link SshHost} in {@link HostStore} as a searchable entry. Selecting
 * one dispatches {@link ConnectToHostAction#run(Project, SshHost)},
 * which opens a new editor tab connected to the host.
 *
 * <p>Tab weight {@code 50} places this between Vault (60) and Terminals
 * (100), so a blank palette query lists SSH hosts first.
 *
 * <p>Search matches the query string (case-insensitive, substring)
 * against the host's label, hostname, and username — same rules as the
 * {@code Cmd+K} picker in {@code NewSshSessionAction}, just routed
 * through the palette UI.
 */
public final class HostsPaletteContributor implements CommandPaletteContributor {

    @Override
    public @NotNull String getTabName() {
        return "Hosts";
    }

    @Override
    public int getTabWeight() {
        return 50;
    }

    @Override
    public @NotNull List<PaletteItem> search(@NotNull String query) {
        HostStore store = ApplicationManager.getApplication().getService(HostStore.class);
        if (store == null) return List.of();

        String q = query.toLowerCase();
        List<PaletteItem> items = new ArrayList<>();
        store.getHosts().stream()
            .filter(h -> q.isEmpty() || matches(h, q))
            .sorted(Comparator.comparing(SshHost::label, String.CASE_INSENSITIVE_ORDER))
            .forEach(h -> items.add(hostItem(h)));
        return items;
    }

    private static boolean matches(@NotNull SshHost host, @NotNull String q) {
        return host.label().toLowerCase().contains(q)
            || host.host().toLowerCase().contains(q)
            || host.username().toLowerCase().contains(q);
    }

    private PaletteItem hostItem(@NotNull SshHost host) {
        String subtitle = host.host() + ":" + host.port() + "  ·  " + host.username();
        return new PaletteItem(
            "ssh.host." + host.id(),
            host.label(),
            subtitle,
            null,
            () -> ApplicationManager.getApplication().invokeLater(
                () -> ConnectToHostAction.run(defaultProject(), host)));
    }

    private static @NotNull Project defaultProject() {
        return ProjectManager.getInstance().getDefaultProject();
    }
}
