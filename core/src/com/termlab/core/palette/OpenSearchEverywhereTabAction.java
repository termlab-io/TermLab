package com.termlab.core.palette;

import com.intellij.ide.actions.SearchEverywhereBaseAction;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManagerImpl;
import com.intellij.ide.actions.searcheverywhere.statistics.SearchFieldStatisticsCollector;
import com.intellij.ide.lightEdit.LightEdit;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

/**
 * Dedicated Search Everywhere tab actions so each TermLab tab can have
 * its own keymap entry and default shortcut.
 */
public abstract class OpenSearchEverywhereTabAction extends SearchEverywhereBaseAction implements DumbAware {

    @Override
    public final void actionPerformed(@NotNull AnActionEvent e) {
        AnActionEvent event = SearchFieldStatisticsCollector.wrapEventWithActionStartData(e);
        if (LightEdit.owns(event.getProject())) return;
        ReadAction.runBlocking(() -> showInSearchEverywherePopup(tabId(), event, true, true));
    }

    protected abstract @NotNull String tabId();

    public static final class ActionsTab extends OpenSearchEverywhereTabAction {
        @Override
        protected @NotNull String tabId() {
            return "ActionSearchEverywhereContributor";
        }
    }

    public static final class TerminalsTab extends OpenSearchEverywhereTabAction {
        @Override
        protected @NotNull String tabId() {
            return "TermLabTerminals";
        }
    }

    public static final class HostsTab extends OpenSearchEverywhereTabAction {
        @Override
        protected @NotNull String tabId() {
            return "TermLabHosts";
        }
    }

    public static final class VaultTab extends OpenSearchEverywhereTabAction {
        @Override
        protected @NotNull String tabId() {
            return "TermLabVault";
        }
    }

    public static final class SftpTab extends OpenSearchEverywhereTabAction {
        @Override
        protected @NotNull String tabId() {
            return "TermLabSftp";
        }
    }

    public static final class TunnelsTab extends OpenSearchEverywhereTabAction {
        @Override
        protected @NotNull String tabId() {
            return "TermLabTunnels";
        }
    }

    public static final class FilesTab extends OpenSearchEverywhereTabAction {
        @Override
        protected @NotNull String tabId() {
            return "TermLabFiles";
        }
    }

    public static final class AllTab extends OpenSearchEverywhereTabAction {
        @Override
        protected @NotNull String tabId() {
            return SearchEverywhereManagerImpl.ALL_CONTRIBUTORS_GROUP_ID;
        }
    }
}
