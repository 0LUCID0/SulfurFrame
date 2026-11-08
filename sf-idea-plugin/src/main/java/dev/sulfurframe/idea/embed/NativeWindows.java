package dev.sulfurframe.idea.embed;

import java.util.List;

/**
 * Native window queries for code outside this package (launcher, self-test), on either platform: Win32 on Windows,
 * X11 on Linux. Handles are HWNDs or X window ids.
 */
public final class NativeWindows {
    private static final boolean X = GameView.linux();

    public static List<Long> gameWindows() { return X ? X11.findGameWindows() : Win32.findGameWindows(); }
    public static long pidOf(long w) { return X ? X11.pidOf(w) : Win32.processIdOf(w); }
    public static boolean isWindow(long w) { return X ? X11.exists(w) : Win32.isWindow(w); }
    public static long parentOf(long w) { return X ? X11.parentOf(w) : Win32.getAncestor(w, Win32.GA_PARENT); }
    public static String className(long w) { return X ? X11.name(w) : Win32.className(w); }
    /** Windows: minimized. X11: not viewable (unmapped, the "hidden" state there). */
    public static boolean isIconic(long w) { return X ? !X11.viewable(w) : Win32.isIconic(w); }
    /** {x, y, width, height} of the window in screen pixels, or null. */
    public static int[] screenRect(long w) {
        if (X) return X11.screenRect(w);
        var r = Win32.windowRect(w); return r == null ? null : new int[]{r.left(), r.top(), r.width(), r.height()};
    }
    public static boolean hasCaption(long w) { return !X && (Win32.getLongPtr(w, Win32.GWL_STYLE) & Win32.WS_CAPTION) == Win32.WS_CAPTION; }
    /** Window with keyboard focus (Windows: in the foreground input context), 0 if none. */
    public static long keyboardFocus() { return X ? X11.inputFocus() : Win32.foregroundFocus(); }
    /** Window under a physical-pixel screen point (Windows; 0 on X11). */
    public static long windowAt(int x, int y) { return X ? 0 : Win32.windowFromPoint(x, y); }
    public static boolean isChild(long w) { return X ? X11.parentOf(w) != X11.root() : (Win32.getLongPtr(w, Win32.GWL_STYLE) & Win32.WS_CHILD) != 0; }
    public static String clientSize(long w) {
        if (X) { int[] r = X11.screenRect(w); return r == null ? "?" : r[2] + "x" + r[3]; }
        var r = Win32.clientRect(w); return r == null ? "?" : r.width() + "x" + r.height();
    }
    /** Is this the plugin's own AWT canvas window? */
    public static boolean isOwnCanvas(long w) { return X ? X11.name(w).contains("XCanvasPeer") : Win32.className(w).equals("SunAwtCanvas"); }
    private NativeWindows() {}
}
