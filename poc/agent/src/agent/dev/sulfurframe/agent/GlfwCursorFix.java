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
 * The release (menu, ESC, inventory) is gated the same way and was the bigger problem: GLFW then keeps re-centering
 * the cursor in the game forever. It releases on WM_KILLFOCUS while the mode is DISABLED, so for that moment the mode
 * is set back, WM_KILLFOCUS sent, the game's mode restored and WM_SETFOCUS sent (focus false -> true for the game).
 *
 * Called from the glfwSwapBuffers hook on the render thread (GLFW's thread). Must never throw.
 */
final class GlfwCursorFix {
    private static final int GLFW_CURSOR = 0x00033001, GLFW_CURSOR_DISABLED = 0x00034003, WM_SETFOCUS = 0x0007, WM_KILLFOCUS = 0x0008;
    private static final long CHECK_NS = 50_000_000L;

    private static boolean broken;
    private static long checkedAt, window, hwnd;
    private static int lastMode;
    private static Method getInputMode, setInputMode, getWin32Window, invP, invPP, invPPPPI;
    private static Method getWindowMonitor;
    /** GLFW's own fullscreen state (window on a monitor): 1 / 0, -1 while unknown. Read by GameStats for the IDE, which
     *  cannot tell F11 from a merely borderless window by its style alone. */
    static volatile int fullscreen = -1;
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
            fullscreen = (Long) getWindowMonitor.invoke(null, w) != 0 ? 1 : 0;
            int was = lastMode;
            lastMode = mode;
            boolean grab = mode == GLFW_CURSOR_DISABLED && was != GLFW_CURSOR_DISABLED;
            boolean release = mode != GLFW_CURSOR_DISABLED && was == GLFW_CURSOR_DISABLED;
            if (!grab && !release || hwnd == 0) return;
            if (pp(fnGetParent, hwnd) == 0) return;                 // not embedded: GLFW handles it itself
            // without the keyboard focus GLFW already did the right thing (WM_KILLFOCUS released the cursor)
            if ((Long) invP.invoke(null, fnGetFocus) != hwnd) return;
            if (grab) {
                send(WM_SETFOCUS);
                SfHooks.log("mouse grabbed while embedded: applied it (WM_SETFOCUS)");
            } else {
                // The release is gated the same way: GLFW keeps the window as its "disabled cursor window", so every
                // poll puts the cursor back in the middle of the game, raw input keeps feeding fake positions, and
                // the mouse looks stuck (menus, ESC, inventory). GLFW's own release runs on WM_KILLFOCUS while the
                // mode is DISABLED: switch the mode back for that moment (a plain field update, the window is not
                // "focused" for GLFW), let it release, set the game's mode again, and report the focus back.
                setInputMode.invoke(null, w, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                send(WM_KILLFOCUS);
                setInputMode.invoke(null, w, GLFW_CURSOR, mode);
                send(WM_SETFOCUS);
                SfHooks.log("mouse released while embedded: applied it");
            }
        } catch (Throwable t) {
            broken = true;
            SfHooks.log("embedded mouse grab fix unavailable: " + t);
        }
    }

    /** SendMessage to the game window; same thread, so its window procedure runs right here. */
    private static void send(int msg) throws Exception {
        if (addressFirst) invPPPPI.invoke(null, fnSendMessage, hwnd, (long) msg, 0L, 0L);
        else invPPPPI.invoke(null, hwnd, (long) msg, 0L, 0L, fnSendMessage);
    }

    private static long pp(long fn, long a) throws Exception {
        return (Long) (addressFirst ? invPP.invoke(null, fn, a) : invPP.invoke(null, a, fn));
    }

    private static void init() throws Exception {
        ClassLoader l = SfHooks.lwjglClassLoader();
        getInputMode = Class.forName("org.lwjgl.glfw.GLFW", false, l).getMethod("glfwGetInputMode", long.class, int.class);
        getWindowMonitor = Class.forName("org.lwjgl.glfw.GLFW", false, l).getMethod("glfwGetWindowMonitor", long.class);
        setInputMode = Class.forName("org.lwjgl.glfw.GLFW", false, l).getMethod("glfwSetInputMode", long.class, int.class, int.class);
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
