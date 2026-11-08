package dev.sulfurframe.idea;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;

/**
 * Combo box renderer that scrolls a text too long for the box instead of cutting it with "...": the chosen value
 * (closed box) and the highlighted row of the open list slide left to their end and back, with a pause at each end.
 * Texts that fit are drawn as usual. Only repaints while something actually overflows and the box is on screen.
 */
final class MarqueeRenderer<T> extends JComponent implements ListCellRenderer<T> {
    private static final int PAD = 4, PAUSE_MS = 1500, PX_PER_S = 45, FRAME_MS = 33;

    private final JComboBox<T> combo;
    private final Timer timer = new Timer(FRAME_MS, e -> tick());
    private JList<? extends T> list;
    private String text = "";
    private boolean scrolls, rowBackground;
    private Color fg, bg;

    // per scrolling text: when its cycle started (a newly shown text starts at its beginning)
    private final java.util.Map<String, Long> cycleStart = new java.util.HashMap<>();
    private volatile boolean overflowing;

    MarqueeRenderer(JComboBox<T> combo) {
        this.combo = combo;
        combo.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (combo.isShowing()) timer.start(); else timer.stop();
        });
        combo.addActionListener(e -> combo.setToolTipText(combo.getSelectedItem() == null ? null : combo.getSelectedItem().toString()));
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends T> list, T value, int index, boolean isSelected, boolean cellHasFocus) {
        this.list = list;
        text = value == null ? "" : value.toString();
        boolean row = index >= 0;
        scrolls = !row || isSelected;               // the closed box, and the row under the mouse / keyboard
        rowBackground = row;
        fg = row && isSelected ? list.getSelectionForeground() : (row ? list.getForeground() : combo.getForeground());
        bg = row && isSelected ? list.getSelectionBackground() : list.getBackground();
        setFont(list.getFont());
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont() != null ? getFont() : UIManager.getFont("Label.font"));
        // keep the box from asking for the full text width (it would widen the tool window); the text scrolls instead
        return new Dimension(Math.min(fm.stringWidth(text), 160) + 2 * PAD, fm.getHeight() + 2);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (rowBackground) { g.setColor(bg); g.fillRect(0, 0, getWidth(), getHeight()); }
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int avail = getWidth() - 2 * PAD, tw = fm.stringWidth(text);
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            int x = PAD;
            if (tw > avail && scrolls) {
                overflowing = true;
                x = PAD - offset(tw - avail);
            }
            g.clipRect(PAD, 0, Math.max(0, avail), getHeight());
            g.setColor(fg);
            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }
    }

    /** Pause at the start, slide to the end, pause, jump back. */
    private int offset(int distance) {
        if (cycleStart.size() > 32) cycleStart.clear();
        long start = cycleStart.computeIfAbsent(text, k -> System.currentTimeMillis());
        long slide = distance * 1000L / PX_PER_S;
        long t = (System.currentTimeMillis() - start) % (PAUSE_MS + slide + PAUSE_MS);
        if (t < PAUSE_MS) return 0;
        if (t < PAUSE_MS + slide) return (int) ((t - PAUSE_MS) * distance / slide);
        return distance;
    }

    private void tick() {
        if (!overflowing || !combo.isShowing()) return;
        overflowing = false;          // set again by the next paint if it still overflows
        combo.repaint();
        if (combo.isPopupVisible() && list != null) list.repaint();
    }
}
