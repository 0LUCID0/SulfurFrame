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
 *   LWJGL 2   Display.isActive(): LWJGL 2 tracks WM_SETFOCUS itself, correct for a child window
 */
public final class SfHooks {
    static final boolean LINUX = System.getProperty("os.name", "").startsWith("Linux");
    public static volatile int codingFps = 15;   // public: SfAgent (app loader) and SfHooks (boot loader) are different runtime packages
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
    private static boolean win32Tried, fnAddressFirst;

    // ---- LWJGL 2
    private static volatile boolean lwjgl2;
    private static Method displayIsActive;

    // ---- frame pacing
    private static long lastPresent;

    public static void configure(String args) {
        if (args == null) return;
        for (String kv : args.split(",")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].trim().equals("codingFps")) codingFps = Integer.parseInt(p[1].trim());
        }
    }

    public static void lwjglLoader(ClassLoader l) { if (l != null && lwjglLoader == null) lwjglLoader = l; }

    // =================================================================== hooks

    /** SDLEvents.SDL_GetWindowFromEvent: windowID 0 (no SDL keyboard focus) -> the game window. */
    public static long sdlWindowFromEvent(long original) {
        if (original != 0) { sdlWindow = original; Lifeboat.sdl(original); return original; }
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

    /** glfwSwapBuffers entry. True = skip the swap: the window is gone (X11 host died), presenting into it crashes Mesa. */
    public static boolean beforeGlfwSwap(long window) {
        glfwWindow = window;
        Lifeboat.glfw(window);
        if (X11Lifeboat.windowGone) return true;
        pace();
        return false;
    }

    public static void beforeSdlSwap(long window) { if (window != 0) { sdlWindow = window; Lifeboat.sdl(window); } pace(); }

    public static void beforeVkPresent() { pace(); }

    /** Display.update(boolean): LWJGL 2 presents the frame here. */
    public static void beforeLwjgl2Update() { lwjgl2 = true; Lifeboat.lwjgl2(); pace(); }

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
            // On X11, GLFW_FOCUSED is XGetInputFocus() == the game's own X window: right for an embedded child too
            // (the host gives it focus with XSetInputFocus). Render thread = GLFW's thread, so the call is safe.
            if (glfwWindow != 0 && LINUX) f = glfwFocused();
            else if (glfwWindow != 0 || sdlWindow != 0) f = win32ThreadHasFocus();
            else if (lwjgl2) f = lwjgl2Active();
        } catch (Throwable ignored) {
        }
        if (f != focusCached) log(f ? "game has the keyboard: full frame rate" : "editor has the keyboard: capping at " + codingFps + " fps");
        focusCached = f;
        return f;
    }

    /** LWJGL 2's own focus flag (updated from WM_SETFOCUS/WM_KILLFOCUS, so it is right for a child window). */
    private static boolean lwjgl2Active() throws Exception {
        if (displayIsActive == null)
            displayIsActive = Class.forName("org.lwjgl.opengl.Display", false, loader()).getMethod("isActive");
        return (Boolean) displayIsActive.invoke(null);
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
        int owner = (Integer) (fnAddressFirst
                ? jniInvokePPI.invoke(null, fnGetWindowThreadProcessId, h, 0L)
                : jniInvokePPI.invoke(null, h, 0L, fnGetWindowThreadProcessId));
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
            // LWJGL 3.1.x passes the function address FIRST (invokePPI(fn, p0, p1)); 3.2+ passes it LAST.
            // Getting this wrong calls the HWND as a function and kills the game (seen on 1.13.2 / LWJGL 3.1.6).
            Class<?> ver = Class.forName("org.lwjgl.Version", false, l);
            int major = ver.getField("VERSION_MAJOR").getInt(null), minor = ver.getField("VERSION_MINOR").getInt(null);
            fnAddressFirst = major == 3 && minor < 2;
            log("Win32 focus source ready (LWJGL " + major + "." + minor + ", function address " + (fnAddressFirst ? "first" : "last") + ")");
        } catch (Throwable t) {
            fnGetFocus = 0;
            log("Win32 focus source unavailable: " + t);
        }
    }

    static ClassLoader lwjglClassLoader() { return loader(); }

    static Method jniMethod(Class<?> jni, String sig, Class<?>... params) throws NoSuchMethodException {
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

    public static void log(String s) {
        System.out.println("[SF-Agent] " + s);
    }

    private SfHooks() {}
}
