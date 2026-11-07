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
    public static String clientSize(long hwnd) { var r = Win32.clientRect(hwnd); return r == null ? "?" : r.width() + "x" + r.height(); }
    private Win32Access() {}
}
