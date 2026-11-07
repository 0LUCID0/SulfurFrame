package dev.sulfurframe.idea.embed;

import java.util.List;

/** The few Win32 queries the rest of the plugin needs, without making the whole binding class public. */
public final class Win32Access {
    public static List<Long> gameWindows() { return Win32.findGameWindows(); }
    public static long pidOf(long hwnd) { return Win32.processIdOf(hwnd); }
    public static boolean isWindow(long hwnd) { return Win32.isWindow(hwnd); }
    public static long parentOf(long hwnd) { return Win32.getAncestor(hwnd, Win32.GA_PARENT); }
    public static String className(long hwnd) { return Win32.className(hwnd); }
    public static boolean isIconic(long hwnd) { return Win32.isIconic(hwnd); }
    /** {x, y, width, height} of the window in screen pixels, or null. */
    public static int[] screenRect(long hwnd) { var r = Win32.windowRect(hwnd); return r == null ? null : new int[]{r.left(), r.top(), r.width(), r.height()}; }
    public static boolean hasCaption(long hwnd) { return (Win32.getLongPtr(hwnd, Win32.GWL_STYLE) & Win32.WS_CAPTION) == Win32.WS_CAPTION; }
    /** Window with keyboard focus in the foreground input context (0 if none). */
    public static long keyboardFocus() { return Win32.foregroundFocus(); }
    public static boolean isChild(long hwnd) { return (Win32.getLongPtr(hwnd, Win32.GWL_STYLE) & Win32.WS_CHILD) != 0; }
    public static String clientSize(long hwnd) { var r = Win32.clientRect(hwnd); return r == null ? "?" : r.width() + "x" + r.height(); }
    private Win32Access() {}
}
