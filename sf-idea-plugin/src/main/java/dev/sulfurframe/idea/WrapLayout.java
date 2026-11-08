package dev.sulfurframe.idea;

import javax.swing.*;
import java.awt.*;

/**
 * FlowLayout that also reports the height of its wrapped rows. Plain FlowLayout wraps when painting but asks for
 * one row's height, so in a narrow tool window (sidebar) the buttons past the first row were simply cut off.
 */
final class WrapLayout extends FlowLayout {
    WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

    @Override public Dimension preferredLayoutSize(Container target) { return size(target, true); }

    @Override public Dimension minimumLayoutSize(Container target) {
        Dimension d = size(target, false);
        d.width -= getHgap() + 1;
        return d;
    }

    private Dimension size(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            // the width we may use: the container's, or (before it has one) its scroll pane / parent's
            int width = target.getSize().width;
            Container c = target;
            while (width == 0 && c.getParent() != null) { c = c.getParent(); width = c.getSize().width; }
            if (width == 0) width = Integer.MAX_VALUE;
            Insets in = target.getInsets();
            int max = width - (in.left + in.right + getHgap() * 2);
            int w = 0, h = 0, rowW = 0, rowH = 0;
            for (Component m : target.getComponents()) {
                if (!m.isVisible()) continue;
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (rowW + d.width > max && rowW > 0) {
                    w = Math.max(w, rowW); h += rowH + getVgap();
                    rowW = 0; rowH = 0;
                }
                if (rowW > 0) rowW += getHgap();
                rowW += d.width;
                rowH = Math.max(rowH, d.height);
            }
            w = Math.max(w, rowW); h += rowH;
            Dimension out = new Dimension(w + in.left + in.right + getHgap() * 2, h + in.top + in.bottom + getVgap() * 2);
            // inside a scroll pane the layout would never shrink again: let the viewport decide the width
            if (SwingUtilities.getAncestorOfClass(JScrollPane.class, target) != null && target.isValid()) out.width -= getHgap() + 1;
            return out;
        }
    }
}
