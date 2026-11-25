package dev.sulfurframe.agent;

import java.lang.reflect.Method;

/**
 * Mouse look in an embedded GLFW game (Windows). Also reads GLFW's fullscreen state for the IDE (every OS).
 *
 * GLFW only really disables the cursor (hide, center, clip, raw input) when glfwSetInputMode(GLFW_CURSOR_DISABLED)
 * is called while the window is "focused", and on Win32 it checks that with GetActiveWindow(): never a child window.
 * So once the game sits inside the IDE, a mouse grab made after the click that focused it (joining a world, closing
 * a menu) is recorded but never applied, and GLFW then drops every mouse move (disabledCursorWindow != window).
 * Keyboard still works, because keys only need the Win32 focus. The release (menu, ESC, inventory, a held overlay key)
 * is gated the same way, and GLFW then keeps re-centering the cursor in the game forever.
 *
 * GLFW applies a grab on WM_SETFOCUS, so for a grab we send that (same thread, so the window procedure runs right
 * here); it only re-reports "focused", which is already true. For a release GLFW would want WM_KILLFOCUS, but that
 * makes it report every held key and button as released and the game sees a focus loss (an overlay shown while a key
 * is held closed again at once; the first version did this). GLFW also releases the cursor when the window menu loop
 * starts (WM_ENTERMENULOOP runs enableCursor() while the mode is DISABLED) without touching the focus: we send that.
 *
 * enableCursor() puts the cursor back where it was when the grab was applied (often where the user clicked into the
 * game); the game wants it where it put it (the middle), so the position from just before the release is kept.
 *
 * Minecraft also refuses to grab at all while it thinks its window is not focused; see syncFocus.
 *
 * Called from the glfwSwapBuffers hook on the render thread (GLFW's thread), every frame: a release made during a
 * frame is applied before that frame's event poll would re-center the cursor. Must never throw.
 */
final class GlfwCursorFix {
    private static final int GLFW_CURSOR = 0x00033001, GLFW_CURSOR_DISABLED = 0x00034003,
            WM_SETFOCUS = 0x0007, WM_ENTERMENULOOP = 0x0211;
    /** the fullscreen state is only polled this often */
    private static final long CHECK_NS = 50_000_000L;

    private static boolean broken;
    private static long checkedAt, window, hwnd;
    private static int lastMode;
    private static Method getInputMode, setInputMode, getWin32Window, osCursor, osSetCursor, invP, invPP, invPPPPI;
    private static Method getWindowMonitor;
    /** GLFW's own fullscreen state (window on a monitor): 1 / 0, -1 while unknown. Read by GameStats for the IDE, which
     *  cannot tell F11 from a merely borderless window by its style alone. */
    static volatile int fullscreen = -1;
    private static long fnGetFocus, fnGetParent, fnSendMessage, fnGetCursorPos, fnSetCursorPos, fnSetCursor, fnGetGUIThreadInfo;
    private static boolean addressFirst;
    private static long focusSentAt = Long.MIN_VALUE / 2, clientLookupAt = Long.MIN_VALUE / 2;
    private static Object client;
    private static Method gameFocused;
    private static boolean syncBroken;
    private static boolean pendingLogged;
    private static Method guiInfo;

    static void check(long w) {
        if (broken || w == 0) return;
        try {
            long now = System.nanoTime();
            if (now - checkedAt >= CHECK_NS) {
                checkedAt = now;
                // the fullscreen state is wanted on every OS (the IDE takes the game out of the panel on F11); the
                // cursor fix below is Win32 only
                if (getWindowMonitor == null) getWindowMonitor = Class.forName("org.lwjgl.glfw.GLFW", false, SfHooks.lwjglClassLoader()).getMethod("glfwGetWindowMonitor", long.class);
                fullscreen = (Long) getWindowMonitor.invoke(null, w) != 0 ? 1 : 0;
            }
            if (SfHooks.LINUX) return;
            if (getInputMode == null) init();
            if (w != window) { window = w; hwnd = (Long) getWin32Window.invoke(null, w); lastMode = 0; }
            if (hwnd == 0 || pp(fnGetParent, hwnd) == 0) { lastMode = 0; return; }   // not embedded: GLFW handles it itself
            // Only while the game has the keyboard focus. A grab or release seen without it waits (lastMode is not
            // updated): a key release such as Alt can take the focus away for a frame, and a grab missed then was
            // never applied. A release that GLFW did itself (a real WM_KILLFOCUS) is harmless to apply again.
            long own = (Long) invP.invoke(null, fnGetFocus), fg = foregroundFocus();
            if (own != hwnd && fg != hwnd) {
                if (!pendingLogged && (Integer) getInputMode.invoke(null, w, GLFW_CURSOR) != lastMode && lastMode != 0) {
                    pendingLogged = true;
                    SfHooks.log("mouse " + (lastMode == GLFW_CURSOR_DISABLED ? "release" : "grab") + " waits for the keyboard focus (GetFocus 0x"
                            + Long.toHexString(own) + ", foreground focus 0x" + Long.toHexString(fg) + ", game 0x" + Long.toHexString(hwnd) + ")");
                }
                return;
            }
            pendingLogged = false;
            if (!resyncBroken && !addressFirst && now - resyncAt >= 100_000_000L) {
                resyncAt = now;
                try { resyncKeys(w); } catch (Throwable t) { resyncBroken = true; SfHooks.log("key release check for the embedded game unavailable: " + t); }
            }
            if (!syncBroken) try { syncFocus(now); } catch (Throwable t) { syncBroken = true; SfHooks.log("focus sync for the embedded game unavailable: " + t); }
            int mode = (Integer) getInputMode.invoke(null, w, GLFW_CURSOR);
            int was = lastMode;
            lastMode = mode;
            boolean grab = mode == GLFW_CURSOR_DISABLED && was != GLFW_CURSOR_DISABLED;
            boolean release = mode != GLFW_CURSOR_DISABLED && was == GLFW_CURSOR_DISABLED;
            if (grab) {
                send(WM_SETFOCUS);   // re-reports "focused" (already true for the game) and runs disableCursor()
                SfHooks.log("mouse grabbed while embedded: applied it");
            } else if (release) {
                // GLFW releases only while the mode is DISABLED: switch it back for that moment (a plain field update,
                // the window is not "focused" for GLFW), let it release, then set the game's mode and cursor again (with
                // Win32 calls: glfwSetCursorPos is ignored too while GLFW thinks the window is not focused)
                int[] at = new int[2];
                boolean kept = osCursor != null && (Integer) osCursor.invoke(null, at, fnGetCursorPos) != 0;
                setInputMode.invoke(null, w, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                send(WM_ENTERMENULOOP);
                setInputMode.invoke(null, w, GLFW_CURSOR, mode);
                if (kept) osSetCursor.invoke(null, at[0], at[1], fnSetCursorPos);
                SfHooks.log("mouse released while embedded: applied it");
            }
            // Keep the cursor hidden while grabbed: without mouse moves GLFW gets no WM_SETCURSOR to hide it again, and
            // a cursor set by the IDE (it shares the input state, e.g. a busy cursor while indexing) stays on the game.
            if (mode == GLFW_CURSOR_DISABLED) pp(fnSetCursor, 0L);
        } catch (Throwable t) {
            broken = true;
            SfHooks.log("embedded mouse grab fix unavailable: " + t);
        }
    }

    /**
     * The game has the Win32 keyboard focus but Minecraft thinks its window is not focused (it missed a WM_SETFOCUS,
     * or a release of the old fix left it so): Minecraft then refuses to grab the mouse at all (lockCursor / grabMouse
     * check it first), so an overlay that grabs on a key release never gets mouse look back. Report the focus to GLFW
     * once (WM_SETFOCUS runs its focus callback). GLFW's own GLFW_FOCUSED cannot be used for this: on Win32 it asks
     * GetActiveWindow(), which is never a child window.
     */
    private static void syncFocus(long now) throws Exception {
        if (now - focusSentAt < 500_000_000L) return;
        if (gameFocused == null) {
            if (now - clientLookupAt < 2_000_000_000L) return;
            clientLookupAt = now;
            ClassLoader l = Thread.currentThread().getContextClassLoader();
            for (String[] c : new String[][]{{"net.minecraft.client.MinecraftClient", "isWindowFocused"}, {"net.minecraft.client.Minecraft", "isWindowActive"}}) {
                try {
                    Class<?> k = Class.forName(c[0], false, l);
                    Object mc = k.getMethod("getInstance").invoke(null);
                    if (mc == null) return;
                    client = mc; gameFocused = k.getMethod(c[1]);
                    break;
                } catch (ClassNotFoundException | NoSuchMethodException ignored) { }
            }
            if (gameFocused == null) return;
        }
        if ((Boolean) gameFocused.invoke(client)) return;
        focusSentAt = now;
        send(WM_SETFOCUS);
        SfHooks.log("game has the keyboard but thought its window was not focused: reported the focus (WM_SETFOCUS)");
    }

    /** Focus window of the foreground thread (GetGUIThreadInfo), as the IDE sees it; 0 if unknown. The game thread's
     *  own GetFocus can be 0 for a moment while the IDE's thread holds the input (a key such as Alt reaching it). */
    private static long foregroundFocus() throws Exception {
        if (guiInfo == null) return 0;
        int[] gti = new int[18];
        gti[0] = 72;
        if ((Integer) guiInfo.invoke(null, 0, gti, fnGetGUIThreadInfo) == 0) return 0;
        return (gti[4] & 0xFFFFFFFFL) | ((long) gti[5] << 32);
    }

    // ---- keys and buttons released while the game did not get the message
    private static Method getKey, getKeyScancode, getMouseButton, invI1, invI2;
    private static long fnGetAsyncKeyState, fnMapVirtualKey, fnGetSystemMetrics;
    private static int[] rkKey, rkScan, rkVk;
    private static boolean resyncBroken;
    private static long resyncAt;

    /**
     * GLFW believes a key or mouse button is held, but it is up: its release went elsewhere (while the game's input
     * was kept apart from the IDE's during a stall, or while the IDE had the keys for a moment). Minecraft then keeps
     * walking, or an overlay held open by Alt never closes. Send the missed release; one for a key GLFW already sees
     * released is ignored by GLFW. Only while the game has the keyboard focus (every 100 ms).
     */
    private static void resyncKeys(long w) throws Exception {
        if (rkKey == null) {
            ClassLoader l = SfHooks.lwjglClassLoader();
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW", false, l), jni = Class.forName("org.lwjgl.system.JNI", false, l);
            getKey = glfw.getMethod("glfwGetKey", long.class, int.class);
            getKeyScancode = glfw.getMethod("glfwGetKeyScancode", int.class);
            getMouseButton = glfw.getMethod("glfwGetMouseButton", long.class, int.class);
            invI1 = SfHooks.jniMethod(jni, "I", int.class, long.class);
            invI2 = SfHooks.jniMethod(jni, "I", int.class, int.class, long.class);
            Object user32 = Class.forName("org.lwjgl.system.windows.WindowsLibrary", false, l).getConstructor(String.class).newInstance("user32");
            Method addr = Class.forName("org.lwjgl.system.FunctionProvider", false, l).getMethod("getFunctionAddress", CharSequence.class);
            fnGetAsyncKeyState = (Long) addr.invoke(user32, "GetAsyncKeyState");
            fnMapVirtualKey = (Long) addr.invoke(user32, "MapVirtualKeyW");
            fnGetSystemMetrics = (Long) addr.invoke(user32, "GetSystemMetrics");
            java.util.List<int[]> t = new java.util.ArrayList<>();
            for (int key = 32; key <= 348; key++) {
                int sc = (Integer) getKeyScancode.invoke(null, key);
                if (sc <= 0) continue;
                int vk = (Integer) invI2.invoke(null, (sc & 0xFF) | ((sc & 0x100) != 0 ? 0xE000 : 0), 3, fnMapVirtualKey);   // MAPVK_VSC_TO_VK_EX
                if (vk != 0) t.add(new int[]{key, sc, vk});
            }
            rkKey = new int[t.size()]; rkScan = new int[t.size()]; rkVk = new int[t.size()];
            for (int i = 0; i < t.size(); i++) { rkKey[i] = t.get(i)[0]; rkScan[i] = t.get(i)[1]; rkVk[i] = t.get(i)[2]; }
        }
        for (int i = 0; i < rkKey.length; i++) {
            if ((Integer) getKey.invoke(null, w, rkKey[i]) != 1 || down(rkVk[i])) continue;
            int sc = rkScan[i];
            long lp = 1L | ((long) (sc & 0xFF) << 16) | ((sc & 0x100) != 0 ? 1L << 24 : 0) | (1L << 30) | (1L << 31);
            boolean sys = rkVk[i] == 0x12 || rkVk[i] == 0xA4 || rkVk[i] == 0xA5 || down(0x12);   // Alt or with Alt held: WM_SYSKEYUP
            send(sys ? 0x0105 : 0x0101, rkVk[i], lp);
            SfHooks.log("key " + rkKey[i] + " was released while the game missed it: released it");
        }
        boolean swapped = (Integer) invI1.invoke(null, 23, fnGetSystemMetrics) != 0;   // SM_SWAPBUTTON: async state is physical
        int[][] buttons = {{0, swapped ? 0x02 : 0x01, 0x0202}, {1, swapped ? 0x01 : 0x02, 0x0205}, {2, 0x04, 0x0208}};
        for (int[] b : buttons) {
            if ((Integer) getMouseButton.invoke(null, w, b[0]) != 1 || down(b[1])) continue;
            send(b[2], 0, 0);
            SfHooks.log("mouse button " + b[0] + " was released while the game missed it: released it");
        }
    }

    private static boolean down(int vk) throws Exception {
        return ((Integer) invI1.invoke(null, vk, fnGetAsyncKeyState) & 0x8000) != 0;
    }

    /** SendMessage to the game window; same thread, so its window procedure runs right here. */
    private static void send(int msg) throws Exception { send(msg, 0L, 0L); }

    private static void send(int msg, long wp, long lp) throws Exception {
        if (addressFirst) invPPPPI.invoke(null, fnSendMessage, hwnd, (long) msg, wp, lp);
        else invPPPPI.invoke(null, hwnd, (long) msg, wp, lp, fnSendMessage);
    }

    private static long pp(long fn, long a) throws Exception {
        return (Long) (addressFirst ? invPP.invoke(null, fn, a) : invPP.invoke(null, a, fn));
    }

    private static void init() throws Exception {
        ClassLoader l = SfHooks.lwjglClassLoader();
        Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW", false, l);
        getInputMode = glfw.getMethod("glfwGetInputMode", long.class, int.class);
        setInputMode = glfw.getMethod("glfwSetInputMode", long.class, int.class, int.class);
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
        fnGetCursorPos = (Long) addr.invoke(user32, "GetCursorPos");
        fnSetCursorPos = (Long) addr.invoke(user32, "SetCursorPos");
        fnSetCursor = (Long) addr.invoke(user32, "SetCursor");
        fnGetGUIThreadInfo = (Long) addr.invoke(user32, "GetGUIThreadInfo");
        Class<?> ver = Class.forName("org.lwjgl.Version", false, l);
        addressFirst = ver.getField("VERSION_MAJOR").getInt(null) == 3 && ver.getField("VERSION_MINOR").getInt(null) < 2;
        if (!addressFirst) try {   // optional: without them the cursor is left where GLFW puts it on a release
            osCursor = SfHooks.jniMethod(jni, "PI", int[].class, long.class);
            osSetCursor = SfHooks.jniMethod(jni, "I", int.class, int.class, long.class);
            guiInfo = SfHooks.jniMethod(jni, "PI", int.class, int[].class, long.class);
        } catch (NoSuchMethodException e) { osCursor = null; osSetCursor = null; guiInfo = null; }
    }

    private GlfwCursorFix() {}
}
