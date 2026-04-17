package com.termlab.search;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.codeStyle.MinusculeMatcher;
import com.intellij.psi.codeStyle.NameUtil;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.Processor;
import com.intellij.util.text.matching.MatchingMode;
import com.termlab.sftp.client.SshSftpSession;
import com.termlab.sftp.model.LocalFileEntry;
import com.termlab.sftp.model.RemoteFileEntry;
import com.termlab.sftp.search.FileListCache;
import com.termlab.sftp.spi.LocalFileOpener;
import com.termlab.sftp.spi.RemoteFileOpener;
import com.termlab.sftp.toolwindow.LocalFilePane;
import com.termlab.sftp.toolwindow.RemoteFilePane;
import com.termlab.sftp.toolwindow.SftpToolWindow;
import com.termlab.sftp.toolwindow.SftpToolWindowFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class FileSearchContributor implements SearchEverywhereContributor<FileHit> {

    private static final String PROVIDER_ID = "TermLabFiles";
    private static final String GROUP_NAME = "Files";
    private static final String NOTIFICATION_GROUP = "TermLab File Search";
    private static final int LIVE_QUERY_THRESHOLD = 3;

    private final Project project;

    public FileSearchContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getSearchProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public @NotNull String getGroupName() {
        return GROUP_NAME;
    }

    @Override
    public int getSortWeight() {
        return 47;
    }

    @Override
    public boolean showInFindResults() {
        return false;
    }

    @Override
    public boolean isShownInSeparateTab() {
        return true;
    }

    @Override
    public boolean isEmptyPatternSupported() {
        return true;
    }

    @Override
    public int getElementPriority(@NotNull FileHit element, @NotNull String searchPattern) {
        return element.priority();
    }

    @Override
    public @Nullable String getAdvertisement() {
        return "Search filenames in the active local and remote SFTP panes";
    }

    @Override
    public @NotNull List<AnAction> getActions(@NotNull Runnable onChanged) {
        return List.of(
            new FilterAction(onChanged),
            new RefreshAction(onChanged)
        );
    }

    @Override
    public void fetchElements(
        @NotNull String pattern,
        @NotNull ProgressIndicator progressIndicator,
        @NotNull Processor<? super FileHit> consumer
    ) {
        SearchContext context = SearchContext.fromProject(project);
        if (context == null) {
            consumer.process(FileHit.message("Open an SFTP tool window to search files.", Integer.MAX_VALUE));
            return;
        }

        FileSearchFilter filter = FileSearchFilter.getInstance();
        String regexError = filter.regexError();
        if (regexError != null) {
            consumer.process(FileHit.message("Regex invalid: " + regexError + ".", Integer.MAX_VALUE));
            return;
        }

        maybeStartLocalBuild(context.localPane(), filter);
        maybeStartRemoteBuild(context.remotePane(), filter);

        FileListCache.Snapshot localSnapshot = context.localPane().fileListCache().snapshot();
        FileListCache.Snapshot remoteSnapshot = context.remotePane().fileListCache().snapshot();

        List<FileHit> hits = new ArrayList<>();
        collectMessages(localSnapshot, "Local", hits);
        if (context.remoteAvailable()) {
            collectMessages(remoteSnapshot, "Remote", hits);
        }

        MinusculeMatcher matcher = buildMatcher(pattern);
        Set<String> seen = new HashSet<>();
        addCachedLocalHits(localSnapshot, matcher, hits, seen, filter);
        if (context.remoteAvailable()) {
            addCachedRemoteHits(context, remoteSnapshot, matcher, hits, seen, filter);
        }

        if (hits.stream().noneMatch(hit -> hit.kind() != FileHit.Kind.MESSAGE)
            && (localSnapshot.state() == FileListCache.State.BUILDING
            || remoteSnapshot.state() == FileListCache.State.BUILDING
            || localSnapshot.state() == FileListCache.State.EMPTY
            || remoteSnapshot.state() == FileListCache.State.EMPTY)) {
            hits.add(FileHit.message("Indexing… (progress in status bar)", Integer.MAX_VALUE - 1));
        }

        if (pattern.trim().length() >= 3 && countResults(hits) < LIVE_QUERY_THRESHOLD) {
            addLiveHits(context, pattern.trim(), progressIndicator, hits, seen, matcher, filter);
        }

        if (hits.stream().noneMatch(hit -> hit.kind() != FileHit.Kind.MESSAGE)) {
            hits.add(FileHit.message(
                pattern.isBlank() ? "No files available in the active SFTP panes." : "No files matching '" + pattern + "'.",
                Integer.MAX_VALUE - 2));
        }

        hits.stream()
            .sorted(Comparator.comparingInt(FileHit::priority).reversed().thenComparing(FileHit::displayName, String.CASE_INSENSITIVE_ORDER))
            .forEach(hit -> {
                if (!progressIndicator.isCanceled()) {
                    consumer.process(hit);
                }
            });
    }

    @Override
    public boolean processSelectedItem(@NotNull FileHit selected, int modifiers, @NotNull String searchText) {
        if (selected.kind() == FileHit.Kind.MESSAGE) {
            return true;
        }

        SearchContext context = SearchContext.fromProject(project);
        if (context == null) return true;

        try {
            if (selected.kind() == FileHit.Kind.LOCAL && selected.localPath() != null) {
                List<LocalFileOpener> openers = LocalFileOpener.EP_NAME.getExtensionList();
                if (openers.isEmpty()) return true;
                openers.getFirst().open(project, LocalFileEntry.of(selected.localPath()));
                return true;
            }

            if (selected.kind() == FileHit.Kind.REMOTE && selected.remotePath() != null) {
                RemoteFilePane remotePane = context.remotePane();
                SshSftpSession session = remotePane.activeSession();
                if (session == null || remotePane.currentHost() == null) return true;
                var attrs = session.client().stat(selected.remotePath());
                String name = selected.displayName();
                RemoteFileEntry entry = new RemoteFileEntry(
                    name,
                    attrs.getSize(),
                    attrs.getModifyTime() == null ? null : Instant.ofEpochMilli(attrs.getModifyTime().toMillis()),
                    attrs.isDirectory(),
                    false,
                    ""
                );
                List<RemoteFileOpener> openers = RemoteFileOpener.EP_NAME.getExtensionList();
                if (openers.isEmpty()) return true;
                openers.getFirst().open(project, remotePane.currentHost(), session, selected.remotePath(), entry);
            }
        } catch (IOException e) {
            Messages.showErrorDialog(project, e.getMessage(), "File Search");
        }
        return true;
    }

    @Override
    public @NotNull ListCellRenderer<? super FileHit> getElementsRenderer() {
        return new Renderer();
    }

    @Override
    public @Nullable String getItemDescription(@NotNull FileHit element) {
        return element.description();
    }

    @Override
    public void dispose() {
    }

    private void maybeStartLocalBuild(@NotNull LocalFilePane pane, @NotNull FileSearchFilter filter) {
        Path root = pane.currentDirectory();
        if (root == null) return;
        FileListCache cache = pane.fileListCache();
        long token = cache.beginBuild(root.toString());
        if (token < 0) return;

        new FileSearchStatusProgress(
            project,
            FileSearchStatusProgress.localTitle(root.toString()),
            indicator -> {
                try {
                    FileLister.ListingResult result = FileLister.listLocal(root, filter, indicator);
                    cache.complete(token, result.tool().name(), result.paths());
                    maybeNotifySlowTool("local", result.tool(), "local");
                } catch (Exception e) {
                    cache.fail(token, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                if (indicator.isCanceled()) {
                    cache.cancel(token);
                }
            }
        ).queue();
    }

    private void maybeStartRemoteBuild(@NotNull RemoteFilePane pane, @NotNull FileSearchFilter filter) {
        SshSftpSession session = pane.activeSession();
        String root = pane.currentRemotePath();
        if (session == null || root == null) return;
        FileListCache cache = pane.fileListCache();
        long token = cache.beginBuild(root);
        if (token < 0) return;

        String hostLabel = pane.currentHost() == null ? "remote host" : pane.currentHost().label();
        new FileSearchStatusProgress(
            project,
            FileSearchStatusProgress.remoteTitle(hostLabel),
            indicator -> {
                try {
                    FileLister.ListingResult result = FileLister.listRemote(session, root, filter, indicator);
                    cache.complete(token, result.tool().name(), result.paths());
                    maybeNotifySlowTool(hostLabel, result.tool(), hostLabel);
                } catch (Exception e) {
                    cache.fail(token, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                if (indicator.isCanceled()) {
                    cache.cancel(token);
                }
            }
        ).queue();
    }

    private void collectMessages(
        @NotNull FileListCache.Snapshot snapshot,
        @NotNull String side,
        @NotNull List<FileHit> hits
    ) {
        if (snapshot.state() == FileListCache.State.FAILED && snapshot.failureMessage() != null) {
            hits.add(FileHit.message(side + " search failed: " + snapshot.failureMessage(), Integer.MAX_VALUE - 10));
        }
        if (snapshot.truncated()) {
            hits.add(FileHit.message(
                "Listing truncated at 200k — narrow the SFTP root to search more.",
                Integer.MAX_VALUE - 11));
        }
    }

    private void addCachedLocalHits(
        @NotNull FileListCache.Snapshot snapshot,
        @Nullable MinusculeMatcher matcher,
        @NotNull List<FileHit> hits,
        @NotNull Set<String> seen,
        @NotNull FileSearchFilter filter
    ) {
        if (snapshot.state() != FileListCache.State.READY) return;
        for (String path : snapshot.paths()) {
            if (!filter.matchesPath(path)) continue;
            Path nio = Path.of(path);
            String display = nio.getFileName() == null ? path : nio.getFileName().toString();
            int priority = priority(matcher, display, path);
            if (priority == Integer.MIN_VALUE) continue;
            String key = "L:" + path;
            if (seen.add(key)) {
                hits.add(FileHit.local(nio, "local", priority));
            }
        }
    }

    private void addCachedRemoteHits(
        @NotNull SearchContext context,
        @NotNull FileListCache.Snapshot snapshot,
        @Nullable MinusculeMatcher matcher,
        @NotNull List<FileHit> hits,
        @NotNull Set<String> seen,
        @NotNull FileSearchFilter filter
    ) {
        if (snapshot.state() != FileListCache.State.READY) return;
        String hostLabel = context.remotePane().currentHost() == null
            ? "remote"
            : context.remotePane().currentHost().label();
        for (String path : snapshot.paths()) {
            if (!filter.matchesPath(path)) continue;
            int slash = path.lastIndexOf('/');
            String display = slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
            int priority = priority(matcher, display, path);
            if (priority == Integer.MIN_VALUE) continue;
            String key = "R:" + path;
            if (seen.add(key)) {
                hits.add(FileHit.remote(path, hostLabel, priority));
            }
        }
    }

    private void addLiveHits(
        @NotNull SearchContext context,
        @NotNull String pattern,
        @NotNull ProgressIndicator progressIndicator,
        @NotNull List<FileHit> hits,
        @NotNull Set<String> seen,
        @Nullable MinusculeMatcher matcher,
        @NotNull FileSearchFilter filter
    ) {
        try {
            Path localRoot = context.localPane().currentDirectory();
            if (localRoot != null) {
                FileLister.ListingResult result = FileLister.queryLocal(localRoot, pattern, filter, progressIndicator);
                for (String path : result.paths()) {
                    if (progressIndicator.isCanceled()) return;
                    String key = "L:" + path;
                    if (!seen.add(key) || !filter.matchesPath(path)) continue;
                    Path nio = Path.of(path);
                    String display = nio.getFileName() == null ? path : nio.getFileName().toString();
                    int priority = priority(matcher, display, path);
                    if (priority != Integer.MIN_VALUE) {
                        hits.add(FileHit.local(nio, "local", priority - 1));
                    }
                }
            }

            SshSftpSession session = context.remotePane().activeSession();
            String remoteRoot = context.remotePane().currentRemotePath();
            String hostLabel = context.remotePane().currentHost() == null ? "remote" : context.remotePane().currentHost().label();
            if (session != null && remoteRoot != null) {
                FileLister.ListingResult result = FileLister.queryRemote(session, remoteRoot, pattern, filter, progressIndicator);
                for (String path : result.paths()) {
                    if (progressIndicator.isCanceled()) return;
                    String key = "R:" + path;
                    if (!seen.add(key) || !filter.matchesPath(path)) continue;
                    int slash = path.lastIndexOf('/');
                    String display = slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
                    int priority = priority(matcher, display, path);
                    if (priority != Integer.MIN_VALUE) {
                        hits.add(FileHit.remote(path, hostLabel, priority - 1));
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void maybeNotifySlowTool(
        @NotNull String hintKey,
        @NotNull FileLister.Tool tool,
        @NotNull String label
    ) {
        if (!(tool == FileLister.Tool.FIND || tool == FileLister.Tool.WALK || tool == FileLister.Tool.SFTP_WALK)) {
            return;
        }

        FileSearchFilter filter = FileSearchFilter.getInstance();
        if (filter.isHintDismissed(hintKey)) return;

        Notification notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "TermLab file search",
                "Using " + tool.name().toLowerCase() + " for file search on " + label
                    + ". Install ripgrep for much faster indexing.",
                NotificationType.INFORMATION);
        notification.addAction(NotificationAction.createSimple("Don't show again", () -> filter.dismissHint(hintKey)));
        notification.notify(project);
    }

    private static int countResults(@NotNull List<FileHit> hits) {
        int count = 0;
        for (FileHit hit : hits) {
            if (hit.kind() != FileHit.Kind.MESSAGE) count++;
        }
        return count;
    }

    private static @Nullable MinusculeMatcher buildMatcher(@NotNull String pattern) {
        if (pattern.isBlank()) return null;
        return NameUtil.buildMatcher("*" + pattern.trim(), MatchingMode.IGNORE_CASE);
    }

    private static int priority(
        @Nullable MinusculeMatcher matcher,
        @NotNull String display,
        @NotNull String path
    ) {
        if (matcher == null) {
            return 0 - path.length();
        }
        if (!matcher.matches(display) && !matcher.matches(path)) {
            return Integer.MIN_VALUE;
        }
        int displayScore = matcher.matches(display) ? matcher.matchingDegree(display) : Integer.MIN_VALUE / 4;
        int pathScore = matcher.matches(path) ? matcher.matchingDegree(path) - 10 : Integer.MIN_VALUE / 4;
        return Math.max(displayScore, pathScore) - path.length();
    }

    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
        ) {
            JPanel panel = new JPanel(new BorderLayout(8, 0));
            panel.setOpaque(true);
            panel.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());

            SimpleColoredComponent left = new SimpleColoredComponent();
            left.setOpaque(false);
            SimpleColoredComponent right = new SimpleColoredComponent();
            right.setOpaque(false);

            if (value instanceof FileHit hit) {
                if (hit.kind() == FileHit.Kind.MESSAGE) {
                    left.append(hit.displayName(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                } else {
                    left.setIcon(hit.kind() == FileHit.Kind.LOCAL ? AllIcons.Nodes.Folder : AllIcons.Actions.Download);
                    left.append(hit.displayName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    if (hit.description() != null) {
                        left.append("  " + hit.description(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                    }
                    right.append(hit.sourceChip(), SimpleTextAttributes.GRAY_ATTRIBUTES);
                }
            }

            panel.add(left, BorderLayout.CENTER);
            panel.add(right, BorderLayout.EAST);
            return panel;
        }
    }

    private final class FilterAction extends AnAction {
        private final Runnable onChanged;

        private FilterAction(@NotNull Runnable onChanged) {
            super("Filter", "Configure file-search exclusions", AllIcons.General.Filter);
            this.onChanged = onChanged;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            FileSearchFilter filter = FileSearchFilter.getInstance();
            FileSearchFilter.State original = filter.copyState();
            FilterDialog dialog = new FilterDialog(project, original);
            if (!dialog.showAndGet()) {
                if (dialog.refreshRequested()) {
                    refreshCaches();
                    onChanged.run();
                }
                return;
            }

            FileSearchFilter.State updated = dialog.updatedState();
            String regexError = validateRegex(updated.excludeRegex);
            if (regexError != null) {
                Messages.showErrorDialog(project, "Regex invalid: " + regexError, "File Search Filter");
                return;
            }

            boolean rebuildNeeded = differsForListing(original, updated);
            filter.replace(updated);
            if (rebuildNeeded || dialog.refreshRequested()) {
                refreshCaches();
            }
            onChanged.run();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class RefreshAction extends AnAction {
        private final Runnable onChanged;

        private RefreshAction(@NotNull Runnable onChanged) {
            super("Refresh", "Refresh cached file listings", AllIcons.Actions.Refresh);
            this.onChanged = onChanged;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            refreshCaches();
            onChanged.run();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private void refreshCaches() {
        SearchContext context = SearchContext.fromProject(project);
        if (context == null) return;
        context.localPane().fileListCache().invalidate();
        context.remotePane().fileListCache().invalidate();
    }

    private static boolean differsForListing(
        @NotNull FileSearchFilter.State before,
        @NotNull FileSearchFilter.State after
    ) {
        return before.excludeGit != after.excludeGit
            || before.excludeNodeModules != after.excludeNodeModules
            || before.excludeIdea != after.excludeIdea
            || before.excludeBuild != after.excludeBuild
            || before.excludeCache != after.excludeCache
            || before.excludeHiddenDirectories != after.excludeHiddenDirectories
            || before.excludeDsStore != after.excludeDsStore
            || !Objects.equals(normalizePatterns(before.customExcludes), normalizePatterns(after.customExcludes));
    }

    private static @NotNull List<String> normalizePatterns(@Nullable List<String> patterns) {
        if (patterns == null) return List.of();
        return patterns.stream()
            .map(pattern -> pattern == null ? "" : pattern.trim())
            .filter(pattern -> !pattern.isEmpty())
            .distinct()
            .toList();
    }

    private static @Nullable String validateRegex(@Nullable String regex) {
        if (regex == null || regex.isBlank()) return null;
        try {
            java.util.regex.Pattern.compile(regex.trim());
            return null;
        } catch (java.util.regex.PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    private static final class SearchContext {
        private final SftpToolWindow toolWindow;

        private SearchContext(@NotNull SftpToolWindow toolWindow) {
            this.toolWindow = toolWindow;
        }

        static @Nullable SearchContext fromProject(@NotNull Project project) {
            SftpToolWindow window = SftpToolWindowFactory.find(project);
            return window == null ? null : new SearchContext(window);
        }

        @NotNull LocalFilePane localPane() {
            return toolWindow.localPane();
        }

        @NotNull RemoteFilePane remotePane() {
            return toolWindow.remotePane();
        }

        boolean remoteAvailable() {
            return remotePane().activeSession() != null && remotePane().currentRemotePath() != null;
        }
    }

    private static final class FilterDialog extends DialogWrapper {
        private final JCheckBox excludeGit;
        private final JCheckBox excludeNodeModules;
        private final JCheckBox excludeIdea;
        private final JCheckBox excludeBuild;
        private final JCheckBox excludeCache;
        private final JCheckBox excludeHiddenDirectories;
        private final JCheckBox excludeDsStore;
        private final JTextArea customPatterns;
        private final JTextField regexField;
        private boolean refreshRequested;

        private FilterDialog(@NotNull Project project, @NotNull FileSearchFilter.State initial) {
            super(project);
            setTitle("File Search Filters");
            excludeGit = new JCheckBox("Exclude .git / .svn / .hg", initial.excludeGit);
            excludeNodeModules = new JCheckBox("Exclude node_modules", initial.excludeNodeModules);
            excludeIdea = new JCheckBox("Exclude .idea / .vscode", initial.excludeIdea);
            excludeBuild = new JCheckBox("Exclude build / dist / target / out", initial.excludeBuild);
            excludeCache = new JCheckBox(
                "Exclude .cache / __pycache__ / .gradle / .nodenv / .rbenv / .pyenv / .asdf",
                initial.excludeCache);
            excludeHiddenDirectories = new JCheckBox("Exclude hidden directories (.foo/), but keep dotfiles", initial.excludeHiddenDirectories);
            excludeDsStore = new JCheckBox("Exclude .DS_Store / Thumbs.db", initial.excludeDsStore);
            customPatterns = new JTextArea(String.join("\n", normalizePatterns(initial.customExcludes)), 6, 40);
            regexField = new JTextField(initial.excludeRegex == null ? "" : initial.excludeRegex);
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            Box checks = Box.createVerticalBox();
            checks.add(excludeGit);
            checks.add(excludeNodeModules);
            checks.add(excludeIdea);
            checks.add(excludeBuild);
            checks.add(excludeCache);
            checks.add(excludeHiddenDirectories);
            checks.add(excludeDsStore);
            panel.add(checks, BorderLayout.NORTH);

            JPanel center = new JPanel(new BorderLayout(0, 6));
            center.add(new JLabel("Custom patterns (one per line):"), BorderLayout.NORTH);
            center.add(new JBScrollPane(customPatterns), BorderLayout.CENTER);

            JPanel south = new JPanel(new BorderLayout(0, 4));
            south.add(new JLabel("Custom regex:"), BorderLayout.NORTH);
            south.add(regexField, BorderLayout.CENTER);
            center.add(south, BorderLayout.SOUTH);

            panel.add(center, BorderLayout.CENTER);
            return panel;
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction(), new AbstractAction("Refresh Listing") {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    refreshRequested = true;
                    close(CANCEL_EXIT_CODE);
                }
            }, new AbstractAction("Reset to Defaults") {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    excludeGit.setSelected(true);
                    excludeNodeModules.setSelected(true);
                    excludeIdea.setSelected(true);
                    excludeBuild.setSelected(true);
                    excludeCache.setSelected(true);
                    excludeHiddenDirectories.setSelected(true);
                    excludeDsStore.setSelected(true);
                    customPatterns.setText("");
                    regexField.setText("");
                }
            }, getCancelAction()};
        }

        boolean refreshRequested() {
            return refreshRequested;
        }

        @NotNull FileSearchFilter.State updatedState() {
            FileSearchFilter.State state = new FileSearchFilter.State();
            state.excludeGit = excludeGit.isSelected();
            state.excludeNodeModules = excludeNodeModules.isSelected();
            state.excludeIdea = excludeIdea.isSelected();
            state.excludeBuild = excludeBuild.isSelected();
            state.excludeCache = excludeCache.isSelected();
            state.excludeHiddenDirectories = excludeHiddenDirectories.isSelected();
            state.excludeDsStore = excludeDsStore.isSelected();
            state.customExcludes = customPatterns.getText().lines().toList();
            state.excludeRegex = regexField.getText() == null ? "" : regexField.getText().trim();
            return state;
        }
    }

    public static final class Factory implements SearchEverywhereContributorFactory<FileHit> {
        @Override
        public @NotNull SearchEverywhereContributor<FileHit> createContributor(@NotNull AnActionEvent initEvent) {
            Project project = Objects.requireNonNull(
                initEvent.getProject(),
                "Project required for FileSearchContributor");
            return new FileSearchContributor(project);
        }
    }
}
