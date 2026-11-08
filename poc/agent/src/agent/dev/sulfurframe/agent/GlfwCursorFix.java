package dev.sulfurframe.agent;

import java.lang.reflect.Method;

/**
 * Mouse look in an embedded GLFW game (Windows).
 *
 * GLFW only really disables the cursor (hide, center, clip, raw input) when glfwSetInputMode(GLFW_CURSOR_DISABLED)
 * is called while the window is "focused", and on Win32 it checks that with GetActiveWindow(): never a child window.
 * So once the game sits inside the IDE, a mouse grab made after the click that focused it (joining a world, closing
 * a menu) is recorded but never applied, and GLFW then drops every mouse move (disabledCursorWindow != window).
 * Keyboard still works, because keys only need the Win32 focus.
 *
 * GLFW does apply the grab on WM_SETFOCUS. So when the cursor mode turns DISABLED while our child window has the
 * keyboard focus, we send it WM_SETFOCUS ourselves (same thread, so the window procedure runs right here). That only
 * re-reports "focused" (already true) and runs GLFW's disableCursor().
 *
 * Called from the glfwSwapBuffers hook on the render thread (GLFW's thread). Must never throw.
 */
final class GlfwCursorFix {
    private static final int GLFW_CURSOR = 0x00033001, GLFW_CURSOR_DISABLED = 0x00034003, WM_SETFOCUS = 0x0007;
    private static final long CHECK_NS = 50_000_000L;

    private static boolean broken;
    private static long checkedAt, window, hwnd;
    private static int lastMode;
    private static Method getInputMode, getWin32Window, invP, invPP, invPPPPI;
    private static long fnGetFocus, fnGetParent, fnSendMessage;
    private static boolean addressFirst;

    static void check(long w) {
        if (broken || SfHooks.LINUX || w == 0) return;
        long now = System.nanoTime();
        if (now - checkedAt < CHECK_NS) return;
        checkedAt = now;
        try {
            if (getInputMode == null) init();
            if (w != window) { window = w; hwnd = (Long) getWin32Window.invoke(null, w); lastMode = 0; }
            int mode = (Integer) getInputMode.invoke(null, w, GLFW_CURSOR);
            int was = lastMode;
            lastMode = mode;
            if (mode != GLFW_CURSOR_DISABLED || was == GLFW_CURSOR_DISABLED || hwnd == 0) return;
            if (pp(fnGetParent, hwnd) == 0) return;                 // not embedded: GLFW handles it itself
            if ((Long) invP.invoke(null, fnGetFocus) != hwnd) return;
            if (addressFirst) invPPPPI.invoke(null, fnSendMessage, hwnd, (long) WM_SETFOCUS, 0L, 0L);
            else invPPPPI.invoke(null, hwnd, (long) WM_SETFOCUS, 0L, 0L, fnSendMessage);
            SfHooks.log("mouse grabbed while embedded: applied it (WM_SETFOCUS)");
        } catch (Throwable t) {
            broken = true;
            SfHooks.log("embedded mouse grab fix unavailable: " + t);
        }
    }

    private static long pp(long fn, long a) throws Exception {
        return (Long) (addressFirst ? invPP.invoke(null, fn, a) : invPP.invoke(null, a, fn));
    }

    private static void init() throws Exception {
        ClassLoader l = SfHooks.lwjglClassLoader();
        getInputMode = Class.forName("org.lwjgl.glfw.GLFW", false, l).getMethod("glfwGetInputMode", long.class, int.class);
        getWin32Window = Class.forName("org.lwjgl.glfw.GLFWNativeWin32", false, l).getMethod("glfwGetWin32Window", long.class);
        Class<?> jni = Class.forName("org.lwjgl.system.JNI", false, l);
        invP = SfHooks.jniMethod(jni, "P", long.class);
        invPP = SfHooks.jniMethod(jni, "PP", long.class, long.class);
        invPPPPI = SfHooks.jniMethod(jni, "PPPPI", long.class, long.class, long.class, long.class, long.class);
        Class<?> winLib = Class.forName("org.lwjgl.system.windows.WindowsLibrary", false, l);
        Object user32 = winLib.getConstructor(String.class).newInstance("user32");
        Method addr = Class.forName("org.lwjgl.system.FunctionProvider", false, l).getMethod("getFunctionAddress", CharSequence.class);
        fnGetFocus = (Long) addr.invoke(user32, "GetFocus");
        fnGetParent = (Long) addr.invoke(user32, "GetParent");
        fnSendMessage = (Long) addr.invoke(user32, "SendMessageW");
        Class<?> ver = Class.forName("org.lwjgl.Version", false, l);
        addressFirst = ver.getField("VERSION_MAJOR").getInt(null) == 3 && ver.getField("VERSION_MINOR").getInt(null) < 2;
    }

    private GlfwCursorFix() {}
}
