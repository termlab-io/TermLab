package com.conch.minecraftadmin.toolwindow;

import com.conch.minecraftadmin.model.Player;
import com.conch.minecraftadmin.model.ServerState;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Table of online players with a right-click popup exposing Kick / Ban /
 * Op actions. Callbacks are injected as {@code Consumer<String>} so tests
 * can exercise the rendering path without wiring a real poller.
 */
public final class PlayersPanel extends javax.swing.JPanel {

    private static final String[] COLUMNS = { "Name", "Ping (ms)" };

    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JBTable table = new JBTable(model);
    private final Consumer<String> onKick;
    private final Consumer<String> onBan;
    private final Consumer<String> onOp;

    public PlayersPanel(
        @NotNull Consumer<String> onKick,
        @NotNull Consumer<String> onBan,
        @NotNull Consumer<String> onOp
    ) {
        super(new BorderLayout());
        this.onKick = onKick;
        this.onBan = onBan;
        this.onOp = onOp;
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
        });
        add(new JBScrollPane(table), BorderLayout.CENTER);
    }

    public void update(@NotNull ServerState state) {
        model.setRowCount(0);
        for (Player p : state.players()) {
            String ping = p.pingMs() == Player.PING_UNKNOWN ? "—" : String.valueOf(p.pingMs());
            model.addRow(new Object[] { p.name(), ping });
        }
    }

    public int rowCount() { return model.getRowCount(); }
    public String pingAt(int row) { return String.valueOf(model.getValueAt(row, 1)); }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) return;
        table.setRowSelectionInterval(row, row);
        String name = (String) model.getValueAt(row, 0);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem kick = new JMenuItem("Kick " + name);
        kick.addActionListener(ev -> onKick.accept(name));
        JMenuItem ban = new JMenuItem("Ban " + name);
        ban.addActionListener(ev -> onBan.accept(name));
        JMenuItem op = new JMenuItem("Op " + name);
        op.addActionListener(ev -> onOp.accept(name));
        menu.add(kick);
        menu.add(ban);
        menu.add(op);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }
}
