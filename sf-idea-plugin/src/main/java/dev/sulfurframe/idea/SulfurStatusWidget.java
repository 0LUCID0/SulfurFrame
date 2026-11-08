package dev.sulfurframe.idea;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.ui.awt.RelativePoint;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.Map;

/**
 * Status bar: what the game is doing (state, frame rate, server ticks, memory) once a game is embedded; a click
 * opens the SulfurFrame menu (the same actions as the tool window toolbar and Tools | SulfurFrame).
 */
final class SulfurStatusWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {
    static final String ID = "SulfurFrame.Status";

    public static final class Factory implements StatusBarWidgetFactory {
        @Override public String getId() { return ID; }
        @Override public String getDisplayName() { return "SulfurFrame (Minecraft)"; }
        @Override public boolean isAvailable(Project project) { return true; }
        @Override public StatusBarWidget createWidget(Project project) { return new SulfurStatusWidget(project); }
    }

    private final Project project;
    private StatusBar statusBar;
    private final Timer timer = new Timer(1000, e -> { if (statusBar != null) statusBar.updateWidget(ID); });

    SulfurStatusWidget(Project project) { this.project = project; }

    @Override public String ID() { return ID; }
    @Override public WidgetPresentation getPresentation() { return this; }
    @Override public void install(StatusBar statusBar) { this.statusBar = statusBar; timer.start(); }
    @Override public void dispose() { timer.stop(); statusBar = null; }

    private SulfurPanel panel() { var h = project.isDisposed() ? null : PanelHost.of(project); return h == null ? null : h.panel; }

    @Override public String getText() {
        var p = panel();
        if (p == null || !p.embedder.attached()) return "⛏ Minecraft";
        var s = p.stats();
        var sb = new StringBuilder("⛏ ").append(state(p.embedder.throttleState()));
        if (s.containsKey("fps")) sb.append(" · ").append(s.get("fps")).append(" fps");
        if (s.containsKey("tickMs")) {
            double ms = Double.parseDouble(s.get("tickMs"));
            sb.append(" · ").append(String.format(java.util.Locale.ROOT, "%.0f", Math.min(20, ms <= 0 ? 20 : 1000 / Math.max(ms, 50)))).append(" TPS");
        }
        if (s.containsKey("heapMb") && s.containsKey("maxMb"))
            sb.append(" · ").append(gb(s.get("heapMb"))).append("/").append(gb(s.get("maxMb"))).append(" GB");
        return sb.toString();
    }

    private static String state(String throttle) {
        return switch (throttle) {
            case "ACTIVE" -> "playing";
            case "CODING" -> "capped while coding";
            case "HIDDEN" -> "hidden, memory handed back";
            default -> throttle.toLowerCase();
        };
    }

    private static String gb(String mb) { return String.format(java.util.Locale.ROOT, "%.1f", Long.parseLong(mb) / 1024.0); }

    @Override public float getAlignment() { return Component.CENTER_ALIGNMENT; }

    @Override public String getTooltipText() {
        var p = panel();
        if (p == null || !p.embedder.attached()) return "SulfurFrame: no game running. Click for Run Client and more.";
        Map<String, String> s = p.stats();
        String tick = s.containsKey("tickMs") ? s.get("tickMs") + " ms per server tick (50 ms = 20 TPS)" : "no world open";
        return "<html>SulfurFrame: game " + p.embedder.throttleState().toLowerCase() + "<br>" + tick + "<br>Click for the SulfurFrame menu</html>";
    }

    @Override public com.intellij.util.Consumer<MouseEvent> getClickConsumer() {
        return e -> {
            var group = (ActionGroup) ActionManager.getInstance().getAction("SulfurFrame.Menu");
            var popup = JBPopupFactory.getInstance().createActionGroupPopup("SulfurFrame", group,
                    com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);
            var c = e.getComponent();
            var size = popup.getContent().getPreferredSize();
            popup.show(new RelativePoint(c, new Point(0, -size.height)));
        };
    }
}
