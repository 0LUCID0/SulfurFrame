package dev.sulfurframe.agent;

import java.lang.reflect.Method;

/**
 * Called from the patched LWJGL methods (on the game's render thread). Java 8, no dependency on Minecraft or on
 * LWJGL at compile time: LWJGL is reached by reflection through the loader that loaded it, so this works whatever
 * loader (vanilla, Fabric, Forge) the game uses. Must be cheap and must never throw.
 *
 * Focus ("is the game, not the editor, receiving keys?") comes from:
 *   GLFW      glfwGetWindowAttrib(window, GLFW_FOCUSED): GLFW tracks WM_SETFOCUS, correct for a child window
 *   SDL3      Win32 GetFocus() through LWJGL's JNI invokers: SDL itself never reports focus for a child window
 *   LWJGL 2   not implemented yet (original value is kept)
 */
public final class SfHooks {
    static volatile int codingFps = 15;
    private static volatile ClassLoader lwjglLoader;

    private static final long FOCUS_CACHE_NS = 20_000_000L;
    private static long focusCheckedAt;
    private static boolean focusCached = true;

    // ---- GLFW
    private static volatile long glfwWindow;
    private static Method glfwGetWindowAttrib;
    private static final int GLFW_FOCUSED = 0x00020001;

    // ---- SDL
    private static volatile long sdlWindow;
    private static Boolean sdlFocusDelivered;      // last focus state we told the game about (null: none yet)
    private static Method evType, evWindow, winType, winWindowID, winTimestamp, sdlGetWindowID;
    private static final int SDL_EVENT_WINDOW_FOCUS_GAINED = 0x20E, SDL_EVENT_WINDOW_FOCUS_LOST = 0x20F;

    // ---- Win32 through LWJGL's JNI invokers (LWJGL 3)
    private static Method jniInvokeP, jniInvokePPI, jniInvokeI;
    private static long fnGetFocus, fnGetWindowThreadProcessId, fnGetCurrentThreadId;
    private static boolean win32Tried;

    // ---- frame pacing
    private static long lastPresent;

    static void configure(String args) {
        if (args == null) return;
        for (String kv : args.split(",")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].trim().equals("codingFps")) codingFps = Integer.parseInt(p[1].trim());
        }
    }

    static void lwjglLoader(ClassLoader l) { if (l != null && lwjglLoader == null) lwjglLoader = l; }

    // =================================================================== hooks

    /** SDLEvents.SDL_GetWindowFromEvent: windowID 0 (no SDL keyboard focus) -> the game window. */
    public static long sdlWindowFromEvent(long original) {
        if (original != 0) { sdlWindow = original; return original; }
        return sdlWindow;
    }

    /** SDLEvents.SDL_PollEvent: when the queue is empty and focus changed, hand the game a focus event. */
    public static boolean sdlPollEvent(boolean got, Object event) {
        if (got || sdlWindow == 0 || event == null) return got;
        try {
            boolean now = focused();
            if (sdlFocusDelivered != null && sdlFocusDelivered == now) return false;
            if (!initSdlEventAccess(event)) return false;
            int windowId = (Integer) sdlGetWindowID.invoke(null, sdlWindow);
            evType.invoke(event, now ? SDL_EVENT_WINDOW_FOCUS_GAINED : SDL_EVENT_WINDOW_FOCUS_LOST);
            Object w = evWindow.invoke(event);
            winTimestamp.invoke(w, System.nanoTime());
            winWindowID.invoke(w, windowId);
            sdlFocusDelivered = now;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void beforeGlfwSwap(long window) { glfwWindow = window; pace(); }

    public static void beforeSdlSwap(long window) { if (window != 0) sdlWindow = window; pace(); }

    public static void beforeVkPresent() { pace(); }

    public static boolean lwjgl2IsActive(boolean original) { return original; }   // TODO LWJGL 2 focus source

    public static void beforeLwjgl2Update() { }                                       // TODO LWJGL 2 pacing

    // =================================================================== focus

    /** True when the game (not the IDE editor) has keyboard focus. Cached for 20 ms. */
    static boolean focused() {
        long now = System.nanoTime();
        if (now - focusCheckedAt < FOCUS_CACHE_NS) return focusCached;
        focusCheckedAt = now;
        boolean f = true;
        try {
            // GLFW's own GLFW_FOCUSED attribute compares against GetActiveWindow(), which is never a child window,
            // so it reads "unfocused" forever once embedded. Win32 focus by thread is right for GLFW and SDL alike.
            if (glfwWindow != 0 || sdlWindow != 0) f = win32ThreadHasFocus();
        } catch (Throwable ignored) {
        }
        focusCached = f;
        return f;
    }

    private static boolean glfwFocused() throws Exception {
        if (glfwGetWindowAttrib == null) {
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW", false, loader());
            glfwGetWindowAttrib = glfw.getMethod("glfwGetWindowAttrib", long.class, int.class);
        }
        return (Integer) glfwGetWindowAttrib.invoke(null, glfwWindow, GLFW_FOCUSED) != 0;
    }

    /**
     * GetFocus() only sees windows attached to the calling thread's input queue. SetParent attached the game's queue
     * to the IDE's, so: focus window owned by this (render) thread = the game has the keys; anything else = the IDE.
     */
    private static boolean win32ThreadHasFocus() throws Exception {
        if (!win32Tried) initWin32();
        if (fnGetFocus == 0) return true;
        long h = (Long) jniInvokeP.invoke(null, fnGetFocus);
        if (h == 0) return false;
        int owner = (Integer) jniInvokePPI.invoke(null, h, 0L, fnGetWindowThreadProcessId);
        int me = (Integer) jniInvokeI.invoke(null, fnGetCurrentThreadId);
        return owner == me;
    }

    private static void initWin32() {
        win32Tried = true;
        try {
            ClassLoader l = loader();
            Class<?> jni = Class.forName("org.lwjgl.system.JNI", false, l);
            // LWJGL 3.2+ calls them invokeX, LWJGL 3.1.x (Minecraft 1.13-1.14) callX
            jniInvokeP = jniMethod(jni, "P", long.class);
            jniInvokePPI = jniMethod(jni, "PPI", long.class, long.class, long.class);
            jniInvokeI = jniMethod(jni, "I", long.class);
            Class<?> winLib = Class.forName("org.lwjgl.system.windows.WindowsLibrary", false, l);
            Class<?> provider = Class.forName("org.lwjgl.system.FunctionProvider", false, l);
            Method addr = provider.getMethod("getFunctionAddress", CharSequence.class);
            Object user32 = winLib.getConstructor(String.class).newInstance("user32");
            Object kernel32 = winLib.getConstructor(String.class).newInstance("kernel32");
            fnGetFocus = (Long) addr.invoke(user32, "GetFocus");
            fnGetWindowThreadProcessId = (Long) addr.invoke(user32, "GetWindowThreadProcessId");
            fnGetCurrentThreadId = (Long) addr.invoke(kernel32, "GetCurrentThreadId");
            log("Win32 focus source ready");
        } catch (Throwable t) {
            fnGetFocus = 0;
            log("Win32 focus source unavailable: " + t);
        }
    }

    private static Method jniMethod(Class<?> jni, String sig, Class<?>... params) throws NoSuchMethodException {
        try { return jni.getMethod("invoke" + sig, params); }
        catch (NoSuchMethodException e) { return jni.getMethod("call" + sig, params); }
    }

    private static boolean initSdlEventAccess(Object event) throws Exception {
        if (evType != null) return true;
        Class<?> ev = event.getClass();
        evType = ev.getMethod("type", int.class);
        evWindow = ev.getMethod("window");
        Class<?> we = evWindow.getReturnType();
        winType = we.getMethod("type", int.class);
        winWindowID = we.getMethod("windowID", int.class);
        winTimestamp = we.getMethod("timestamp", long.class);
        Class<?> video = Class.forName("org.lwjgl.sdl.SDLVideo", false, ev.getClassLoader());
        sdlGetWindowID = video.getMethod("SDL_GetWindowID", long.class);
        return true;
    }

    private static ClassLoader loader() {
        ClassLoader l = lwjglLoader;
        return l != null ? l : ClassLoader.getSystemClassLoader();
    }

    // =================================================================== pacing

    /** Sleep before presenting so the frame rate stays at codingFps while the editor has focus. */
    private static void pace() {
        int fps = codingFps;
        if (fps <= 0 || focused()) { lastPresent = 0; return; }
        long interval = 1_000_000_000L / fps;
        long now = System.nanoTime();
        if (lastPresent != 0) {
            long wait = lastPresent + interval - now;
            if (wait > 0) {
                try { Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                now = System.nanoTime();
            }
        }
        lastPresent = now;
    }

    static void log(String s) {
        System.out.println("[SF-Agent] " + s);
    }

    private SfHooks() {}
}
