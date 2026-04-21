package com.termlab.core.filepicker.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.JBUI;
import com.termlab.core.filepicker.FileEntry;
import com.termlab.core.filepicker.FilePickerResult;
import com.termlab.core.filepicker.FileSource;
import com.termlab.core.filepicker.FileSourceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Unified file picker dialog. Supports two modes — Save and Open —
 * through the static entry points {@link #showSaveDialog} and
 * {@link #showOpenDialog}. See the spec document at
 * {@code docs/superpowers/specs/2026-04-15-unified-file-picker-design.md}
 * for the full design.
 */
public final class UnifiedFilePickerDialog extends DialogWrapper {

    enum Mode { SAVE, OPEN }

    private static final String CARD_LOADING = "loading";
    private static final String CARD_TABLE = "table";
    private static final String CARD_ERROR = "error";

    private final Project project;
    private final Mode mode;
    private final String suggestedFileName;
    private final String preferredSourceId;

    private final List<FileSource> availableSources = new ArrayList<>();
    private final JComboBox<FileSource> sourceCombo = new JComboBox<>();
    private final JComboBox<LocationChoice> locationCombo = new JComboBox<>();
    private final JTextField filenameField = new JTextField();
    private final JComboBox<String> fileTypeCombo = new JComboBox<>(new String[]{"All Files"});
    private final FileBrowserTable browser = new FileBrowserTable();
    private final JPanel fileListCard = new JPanel(new java.awt.CardLayout());
    private final JLabel errorLabel = new JLabel();
    private final JButton retryButton = new JButton("Retry");
    private final JButton upButton = toolbarButton(AllIcons.Actions.MoveUp, "Parent directory");
    private final JButton homeButton = toolbarButton(AllIcons.Nodes.HomeFolder, "Home directory");
    private final JButton refreshButton = toolbarButton(AllIcons.Actions.Refresh, "Refresh");

    private @Nullable FileSource currentSource;
    private @Nullable String currentPath;
    private @Nullable FilePickerResult result;
    private boolean suppressSourceComboEvents;
    private boolean suppressLocationComboEvents;
    private int navigationSerial;

    public static @Nullable FilePickerResult showSaveDialog(
        @NotNull Project project,
        @NotNull String title,
        @NotNull String suggestedFileName,
        @Nullable String preferredSourceId
    ) {
        UnifiedFilePickerDialog dialog = new UnifiedFilePickerDialog(
            project, Mode.SAVE, title, suggestedFileName, preferredSourceId);
        return dialog.showAndReturn();
    }

    public static @Nullable FilePickerResult showOpenDialog(
        @NotNull Project project,
        @NotNull String title,
        @Nullable String preferredSourceId
    ) {
        UnifiedFilePickerDialog dialog = new UnifiedFilePickerDialog(
            project, Mode.OPEN, title, "", preferredSourceId);
        return dialog.showAndReturn();
    }

    private UnifiedFilePickerDialog(
        @NotNull Project project,
        @NotNull Mode mode,
        @NotNull String title,
        @NotNull String suggestedFileName,
        @Nullable String preferredSourceId
    ) {
        super(project, true);
        this.project = project;
        this.mode = mode;
        this.suggestedFileName = suggestedFileName;
        this.preferredSourceId = preferredSourceId;
        setTitle(title);
        setOKButtonText(mode == Mode.SAVE ? "Save" : "Open");
        configureSourceCombo();
        configureLocationCombo();
        configureBrowser();
        configureToolbarButtons();
        init();
        populateSources();
        loadInitialSource();
    }

    private @Nullable FilePickerResult showAndReturn() {
        if (showAndGet()) return result;
        return null;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout());
        root.setPreferredSize(new Dimension(860, 560));
        root.add(buildNorthPanel(), BorderLayout.NORTH);
        root.add(buildCenterPanel(), BorderLayout.CENTER);
        if (mode == Mode.SAVE) {
            root.add(buildSouthPanel(), BorderLayout.SOUTH);
        }
        return root;
    }

    private @NotNull JComponent buildNorthPanel() {
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setBorder(JBUI.Borders.empty(8));

        JPanel sourceRow = new JPanel(new BorderLayout(8, 0));
        JLabel sourceLabel = new JLabel("Source:");
        sourceLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        sourceLabel.setPreferredSize(JBUI.size(72, 28));
        sourceRow.add(sourceLabel, BorderLayout.WEST);
        sourceRow.add(sourceCombo, BorderLayout.CENTER);
        north.add(sourceRow);

        north.add(Box.createVerticalStrut(6));

        JPanel lookInRow = new JPanel(new BorderLayout(8, 0));
        JLabel lookInLabel = new JLabel("Look in:");
        lookInLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        lookInLabel.setPreferredSize(JBUI.size(72, 28));
        lookInRow.add(lookInLabel, BorderLayout.WEST);
        lookInRow.add(locationCombo, BorderLayout.CENTER);

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(upButton);
        actions.add(Box.createHorizontalStrut(4));
        actions.add(homeButton);
        actions.add(Box.createHorizontalStrut(4));
        actions.add(refreshButton);
        lookInRow.add(actions, BorderLayout.EAST);

        north.add(lookInRow);
        return north;
    }

    private @NotNull JComponent buildCenterPanel() {
        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(JBUI.Borders.empty(0, 8, 4, 8));
        center.add(buildFileListCard(), BorderLayout.CENTER);
        return center;
    }

    private @NotNull JComponent buildFileListCard() {
        JPanel tableCard = new JPanel(new BorderLayout());
        tableCard.add(browser.getComponent(), BorderLayout.CENTER);

        JPanel loadingCard = new JPanel(new BorderLayout());
        JPanel loadingCenter = new JPanel();
        loadingCenter.setLayout(new BoxLayout(loadingCenter, BoxLayout.Y_AXIS));
        loadingCenter.add(Box.createVerticalGlue());
        JPanel loadingRow = new JPanel();
        loadingRow.add(new AsyncProcessIcon("Loading"));
        loadingRow.add(new JLabel("Loading…"));
        loadingCenter.add(loadingRow);
        loadingCenter.add(Box.createVerticalGlue());
        loadingCard.add(loadingCenter, BorderLayout.CENTER);

        JPanel errorCard = new JPanel(new BorderLayout());
        JPanel errorCenter = new JPanel();
        errorCenter.setLayout(new BoxLayout(errorCenter, BoxLayout.Y_AXIS));
        errorCenter.add(Box.createVerticalGlue());
        errorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        errorCenter.add(errorLabel);
        errorCenter.add(Box.createVerticalStrut(8));
        retryButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        errorCenter.add(retryButton);
        errorCenter.add(Box.createVerticalGlue());
        errorCard.add(errorCenter, BorderLayout.CENTER);

        fileListCard.add(tableCard, CARD_TABLE);
        fileListCard.add(loadingCard, CARD_LOADING);
        fileListCard.add(errorCard, CARD_ERROR);
        fileListCard.setBorder(BorderFactory.createEmptyBorder());
        showCard(CARD_LOADING);
        return fileListCard;
    }

    private @NotNull JComponent buildSouthPanel() {
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setBorder(JBUI.Borders.empty(4, 8, 8, 8));

        JPanel fileNameRow = new JPanel(new BorderLayout(8, 0));
        JLabel fileNameLabel = new JLabel("File name:");
        fileNameLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        fileNameLabel.setPreferredSize(JBUI.size(72, 28));
        fileNameRow.add(fileNameLabel, BorderLayout.WEST);
        filenameField.setText(suggestedFileName);
        fileNameRow.add(filenameField, BorderLayout.CENTER);
        south.add(fileNameRow);

        south.add(Box.createVerticalStrut(6));

        JPanel typeRow = new JPanel(new BorderLayout(8, 0));
        JLabel typeLabel = new JLabel("Files of type:");
        typeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        typeLabel.setPreferredSize(JBUI.size(72, 28));
        typeRow.add(typeLabel, BorderLayout.WEST);
        fileTypeCombo.setEnabled(false);
        typeRow.add(fileTypeCombo, BorderLayout.CENTER);
        south.add(typeRow);
        return south;
    }

    private void configureSourceCombo() {
        sourceCombo.setRenderer(new SourceComboRenderer());
        sourceCombo.addActionListener(e -> {
            if (suppressSourceComboEvents) return;
            Object selected = sourceCombo.getSelectedItem();
            if (selected instanceof FileSource source && source != currentSource) {
                navigateTo(source, null);
            }
        });
    }

    private void configureLocationCombo() {
        locationCombo.setRenderer(new LocationChoiceRenderer());
        locationCombo.addActionListener(e -> {
            if (suppressLocationComboEvents) return;
            Object selected = locationCombo.getSelectedItem();
            if (selected instanceof LocationChoice choice) {
                if (choice.source().equals(currentSource) && choice.path().equals(currentPath)) {
                    return;
                }
                navigateTo(choice.source(), choice.path());
            }
        });
    }

    private void configureBrowser() {
        browser.addDoubleClickListener(entry -> {
            FileSource source = currentSource;
            if (source == null || currentPath == null) return;
            if (entry.isDirectory()) {
                navigateTo(source, source.resolve(currentPath, entry.name()));
                return;
            }
            if (mode == Mode.SAVE) {
                filenameField.setText(entry.name());
            } else {
                result = new FilePickerResult(source, source.resolve(currentPath, entry.name()));
                close(OK_EXIT_CODE);
            }
        });

        browser.addSelectionListener(() -> {
            if (mode != Mode.SAVE) return;
            FileEntry selected = browser.getSelectedEntry();
            if (selected != null && !selected.isDirectory()) {
                filenameField.setText(selected.name());
            }
        });
    }

    private void configureToolbarButtons() {
        upButton.addActionListener(e -> {
            FileSource source = currentSource;
            if (source == null || currentPath == null) return;
            String parent = source.parentOf(currentPath);
            if (parent != null) {
                navigateTo(source, parent);
            }
        });

        homeButton.addActionListener(e -> {
            FileSource source = selectedSourceOrFallback();
            if (source != null) {
                navigateTo(source, null);
            }
        });

        refreshButton.addActionListener(e -> {
            FileSource source = selectedSourceOrFallback();
            if (source != null) {
                navigateTo(source, source.equals(currentSource) ? currentPath : null);
            }
        });

        retryButton.addActionListener(e -> {
            FileSource source = selectedSourceOrFallback();
            if (source != null) {
                navigateTo(source, source.equals(currentSource) ? currentPath : null);
            }
        });
    }

    private void populateSources() {
        availableSources.clear();
        availableSources.addAll(collectSources());

        suppressSourceComboEvents = true;
        DefaultComboBoxModel<FileSource> sourceModel = new DefaultComboBoxModel<>();
        for (FileSource source : availableSources) {
            sourceModel.addElement(source);
        }
        sourceCombo.setModel(sourceModel);
        suppressSourceComboEvents = false;
    }

    private @NotNull List<FileSource> collectSources() {
        List<FileSource> all = new ArrayList<>();
        for (FileSourceProvider provider : FileSourceProvider.EP_NAME.getExtensionList()) {
            all.addAll(provider.listSources());
        }
        all.sort(Comparator
            .<FileSource, Boolean>comparing(source -> !source.id().equals(preferredSourceId))
            .thenComparing(FileSource::label, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    private void loadInitialSource() {
        FileSource source = preferredOrFirstSource();
        if (source != null) {
            navigateTo(source, null);
        }
    }

    private @Nullable FileSource preferredOrFirstSource() {
        return availableSources.isEmpty() ? null : availableSources.get(0);
    }

    private @Nullable FileSource selectedSourceOrFallback() {
        Object selected = sourceCombo.getSelectedItem();
        if (selected instanceof FileSource source) {
            return source;
        }
        return currentSource != null ? currentSource : preferredOrFirstSource();
    }

    private void navigateTo(@NotNull FileSource source, @Nullable String requestedPath) {
        int serial = ++navigationSerial;
        FileSource previousSource = currentSource;
        boolean switchingSource = previousSource != source;

        setNavigationBusy(true);
        showCard(CARD_LOADING);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            IOException error = null;
            String resolvedPath = requestedPath;
            List<FileEntry> entries = List.of();
            List<LocationChoice> breadcrumbs = List.of();

            try {
                if (switchingSource) {
                    source.open(project, UnifiedFilePickerDialog.this);
                }
                if (resolvedPath == null || resolvedPath.isBlank()) {
                    resolvedPath = source.initialPath();
                }
                entries = source.list(resolvedPath);
                breadcrumbs = buildLocationChoices(source, resolvedPath);
            } catch (IOException e) {
                error = e;
            }

            IOException finalError = error;
            String finalPath = resolvedPath;
            List<FileEntry> finalEntries = entries;
            List<LocationChoice> finalBreadcrumbs = breadcrumbs;

            ApplicationManager.getApplication().invokeLater(() -> {
                if (serial != navigationSerial) {
                    if (switchingSource) {
                        source.close(UnifiedFilePickerDialog.this);
                    }
                    return;
                }

                if (finalError != null) {
                    if (switchingSource) {
                        source.close(UnifiedFilePickerDialog.this);
                    }
                    errorLabel.setText(ErrorMessages.translate(finalError));
                    showCard(CARD_ERROR);
                    setNavigationBusy(false);
                    return;
                }

                if (switchingSource && previousSource != null) {
                    previousSource.close(UnifiedFilePickerDialog.this);
                }

                currentSource = source;
                currentPath = finalPath;
                browser.setEntries(finalEntries);
                applyNavigationState(source, finalBreadcrumbs);
                showCard(CARD_TABLE);
                setNavigationBusy(false);
            }, ModalityState.any());
        });
    }

    private void applyNavigationState(
        @NotNull FileSource source,
        @NotNull List<LocationChoice> breadcrumbs
    ) {
        suppressSourceComboEvents = true;
        sourceCombo.setSelectedItem(source);
        suppressSourceComboEvents = false;

        suppressLocationComboEvents = true;
        DefaultComboBoxModel<LocationChoice> locationModel = new DefaultComboBoxModel<>();
        for (LocationChoice breadcrumb : breadcrumbs) {
            locationModel.addElement(breadcrumb);
        }
        locationCombo.setModel(locationModel);
        if (!breadcrumbs.isEmpty()) {
            locationCombo.setSelectedItem(breadcrumbs.get(breadcrumbs.size() - 1));
        }
        suppressLocationComboEvents = false;
    }

    private void setNavigationBusy(boolean busy) {
        FileSource selectedSource = selectedSourceOrFallback();
        sourceCombo.setEnabled(!busy);
        locationCombo.setEnabled(!busy);
        upButton.setEnabled(!busy && currentSource != null && currentPath != null
            && currentSource.parentOf(currentPath) != null);
        homeButton.setEnabled(!busy && selectedSource != null);
        refreshButton.setEnabled(!busy && selectedSource != null);
    }

    private @NotNull List<LocationChoice> buildLocationChoices(
        @NotNull FileSource source,
        @NotNull String path
    ) {
        List<String> lineage = buildLineage(source, path);
        if (lineage.isEmpty()) {
            return List.of(new LocationChoice(source, path, source.label(), source.icon()));
        }

        List<LocationChoice> choices = new ArrayList<>();
        choices.add(new LocationChoice(source, lineage.get(0), source.label(), source.icon()));
        for (int i = 1; i < lineage.size(); i++) {
            String ancestor = lineage.get(i);
            choices.add(new LocationChoice(
                source,
                ancestor,
                pathLabel(source, ancestor),
                AllIcons.Nodes.Folder));
        }
        return choices;
    }

    private @NotNull List<String> buildLineage(@NotNull FileSource source, @NotNull String path) {
        List<String> reversed = new ArrayList<>();
        String current = path;
        while (current != null) {
            reversed.add(current);
            current = source.parentOf(current);
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private @NotNull String pathLabel(@NotNull FileSource source, @NotNull String path) {
        String parent = source.parentOf(path);
        if (parent == null) {
            return source.label();
        }
        String normalized = path;
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        if (slash < 0 || slash == normalized.length() - 1) {
            return normalized;
        }
        return normalized.substring(slash + 1);
    }

    private void showCard(@NotNull String name) {
        ((java.awt.CardLayout) fileListCard.getLayout()).show(fileListCard, name);
    }

    @Override
    protected void doOKAction() {
        if (mode == Mode.SAVE) {
            doSaveAction();
        } else {
            doOpenAction();
        }
    }

    private void doSaveAction() {
        FileSource source = currentSource;
        if (source == null || currentPath == null) return;
        String filename = filenameField.getText().trim();
        if (filename.isEmpty()) return;
        if (filename.contains("/") || filename.contains("\\")) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                getContentPanel(),
                "File name must not contain path separators: " + filename,
                "Invalid File Name");
            return;
        }
        String destPath = source.resolve(currentPath, filename);
        boolean exists;
        try {
            exists = source.exists(destPath);
        } catch (IOException e) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                getContentPanel(), ErrorMessages.translate(e), "Error");
            return;
        }
        if (exists) {
            int choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
                getContentPanel(),
                filename + " already exists on " + source.label() + ". Overwrite?",
                "File Exists",
                com.intellij.openapi.ui.Messages.getQuestionIcon());
            if (choice != com.intellij.openapi.ui.Messages.YES) return;
        }
        result = new FilePickerResult(source, destPath);
        super.doOKAction();
    }

    private void doOpenAction() {
        FileSource source = currentSource;
        if (source == null || currentPath == null) return;
        FileEntry selected = browser.getSelectedEntry();
        if (selected == null || selected.isDirectory()) return;
        result = new FilePickerResult(source, source.resolve(currentPath, selected.name()));
        super.doOKAction();
    }

    @Override
    public void dispose() {
        if (currentSource != null) {
            currentSource.close(this);
            currentSource = null;
        }
        super.dispose();
    }

    private static @NotNull JButton toolbarButton(@NotNull javax.swing.Icon icon, @NotNull String tooltip) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setBorder(buttonBorder());
        button.setPreferredSize(JBUI.size(28, 28));
        return button;
    }

    private static @NotNull Border buttonBorder() {
        return JBUI.Borders.compound(
            JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
            JBUI.Borders.empty(4));
    }

    private record LocationChoice(
        @NotNull FileSource source,
        @NotNull String path,
        @NotNull String label,
        @NotNull javax.swing.Icon icon
    ) { }

    private static final class SourceComboRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
            javax.swing.JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
        ) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof FileSource source) {
                setText(source.label());
                setIcon(source.icon());
            }
            return this;
        }
    }

    private static final class LocationChoiceRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
            javax.swing.JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
        ) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof LocationChoice choice) {
                setText(choice.label());
                setIcon(choice.icon());
            }
            return this;
        }
    }
}
