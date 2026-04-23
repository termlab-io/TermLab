package com.termlab.sysinfo.toolwindow;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

final class MemoryGraph extends JComponent {

    private List<Double> values = List.of();
    private final Color fill;
    private final Color line;

    MemoryGraph() {
        this(
            new JBColor(new Color(46, 134, 171, 80), new Color(90, 170, 210, 90)),
            new JBColor(new Color(31, 105, 135), new Color(120, 200, 240))
        );
    }

    MemoryGraph(@NotNull Color fill, @NotNull Color line) {
        this.fill = fill;
        this.line = line;
        setPreferredSize(new Dimension(240, 72));
        setMinimumSize(new Dimension(160, 56));
    }

    void setValues(@NotNull List<Double> values) {
        this.values = List.copyOf(values);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = JBUI.scale(6);
            int w = getWidth() - inset * 2;
            int h = getHeight() - inset * 2;
            g2.setColor(JBColor.border());
            g2.drawRect(inset, inset, w, h);
            if (values.isEmpty()) return;

            int n = values.size();
            int[] xs = new int[n + 2];
            int[] ys = new int[n + 2];
            xs[0] = inset;
            ys[0] = inset + h;
            for (int i = 0; i < n; i++) {
                double value = Math.max(0.0, Math.min(100.0, values.get(i)));
                xs[i + 1] = inset + (n == 1 ? w : (int) Math.round(i * (w / (double) (n - 1))));
                ys[i + 1] = inset + h - (int) Math.round(value * h / 100.0);
            }
            xs[n + 1] = inset + w;
            ys[n + 1] = inset + h;
            g2.setColor(fill);
            g2.fillPolygon(xs, ys, n + 2);
            g2.setColor(line);
            for (int i = 1; i < n; i++) {
                g2.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1]);
            }
        } finally {
            g2.dispose();
        }
    }
}
