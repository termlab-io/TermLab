package com.conch.core.filepicker.ui;

import com.conch.core.filepicker.FilePickerResult;
import com.conch.core.filepicker.FileSource;
import com.conch.core.filepicker.FileSourceProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
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

    private final JComboBox<FileSource> sourceCombo = new JComboBox<>();
    private final JTextField pathField = new JTextField();
    private final JTextField filenameField = new JTextField();
    private final FileBrowserTable browser = new FileBrowserTable();
    private final JPanel fileListCard = new JPanel(new CardLayout());
    private final JLabel errorLabel = new JLabel();
    private final JButton retryButton = new JButton("Retry");

    private @Nullable FileSource currentSource;
    private @Nullable String currentPath;
    private @Nullable FilePickerResult result;

    // Static entry points ---------------------------------------------------

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

    // Construction ----------------------------------------------------------

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
        init();
        // Source population and initial listing happens in Task 13/14.
        populateSources();
    }

    private @Nullable FilePickerResult showAndReturn() {
        if (showAndGet()) return result;
        return null;
    }

    // Layout ----------------------------------------------------------------

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout());
        root.setPreferredSize(new Dimension(700, 500));

        root.add(buildNorthPanel(), BorderLayout.NORTH);
        root.add(buildFileListCard(), BorderLayout.CENTER);
        if (mode == Mode.SAVE) {
            root.add(buildSouthPanel(), BorderLayout.SOUTH);
        }
        return root;
    }

    private JComponent buildNorthPanel() {
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setBorder(JBUI.Borders.empty(4, 8));

        JPanel sourceRow = new JPanel(new BorderLayout(6, 0));
        sourceRow.add(new JLabel("Where: "), BorderLayout.WEST);
        sourceRow.add(sourceCombo, BorderLayout.CENTER);
        sourceRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(sourceRow);

        north.add(Box.createVerticalStrut(4));

        JPanel pathRow = new JPanel(new BorderLayout(6, 0));
        JButton upButton = new JButton("▲");
        upButton.setToolTipText("Parent directory");
        pathRow.add(upButton, BorderLayout.WEST);
        pathRow.add(pathField, BorderLayout.CENTER);
        pathRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(pathRow);

        return north;
    }

    private JComponent buildFileListCard() {
        // Table card (default)
        JPanel tableCard = new JPanel(new BorderLayout());
        tableCard.add(browser.getComponent(), BorderLayout.CENTER);

        // Loading card
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

        // Error card
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
        fileListCard.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        showCard(CARD_LOADING);
        return fileListCard;
    }

    private JComponent buildSouthPanel() {
        JPanel south = new JPanel(new BorderLayout(6, 0));
        south.setBorder(JBUI.Borders.empty(4, 8));
        south.add(new JLabel("File name: "), BorderLayout.WEST);
        filenameField.setText(suggestedFileName);
        south.add(filenameField, BorderLayout.CENTER);
        return south;
    }

    private void showCard(@NotNull String name) {
        ((CardLayout) fileListCard.getLayout()).show(fileListCard, name);
    }

    // Source population -----------------------------------------------------

    private void populateSources() {
        List<FileSource> sources = collectSources();
        for (FileSource s : sources) {
            sourceCombo.addItem(s);
        }
        sourceCombo.setRenderer(new SourceComboRenderer());
        if (sourceCombo.getItemCount() > 0) {
            sourceCombo.setSelectedIndex(0);
        }
    }

    private @NotNull List<FileSource> collectSources() {
        List<FileSource> all = new ArrayList<>();
        for (FileSourceProvider provider : FileSourceProvider.EP_NAME.getExtensionList()) {
            all.addAll(provider.listSources());
        }
        // Sort: preferred id first, then alphabetically by label.
        all.sort(Comparator
            .<FileSource, Boolean>comparing(s -> !s.id().equals(preferredSourceId))
            .thenComparing(FileSource::label, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    // Dialog completion -----------------------------------------------------

    @Override
    protected void doOKAction() {
        // Implemented in Task 13 for Save and Task 14 for Open.
        // For the skeleton, just close with no result.
        super.doOKAction();
    }

    @Override
    public void dispose() {
        // Release any acquired source references.
        if (currentSource != null) {
            currentSource.close(this);
            currentSource = null;
        }
        super.dispose();
    }

    private static final class SourceComboRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(
            javax.swing.JList<?> list, Object value, int index,
            boolean isSelected, boolean cellHasFocus
        ) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof FileSource source) {
                setText(source.label());
                setIcon(source.icon());
            }
            return this;
        }
    }
}
