package dev.sulfurframe.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Keeps the game (and its world) safe when the IDE that embeds it dies abruptly.
 *
 * An embedded game window is a WS_CHILD of the IDE's panel, and Windows destroys a child window with its parent:
 * if the IDE crashes or is force-killed, the game window is gone about 2-4 s later (measured), while the game process
 * keeps running without a window. A watcher outside cannot win that race (the parent window disappears at the same
 * moment the child is destroyed; the IDE's thread/process handles signal later).
 *
 * So this thread watches the game's own window: if it vanishes while it was a child window (embedded), it asks the
 * game to quit the normal way (GLFW "should close" / SDL QUIT event / LWJGL 2 close request), so the integrated server
 * saves the world and the process exits instead of lingering. A window that was detached first (normal IDE close,
 * plugin detach) is a top-level window and is left alone. Java 8, everything through reflection.
 */
final class Lifeboat {
    private static volatile boolean started;
    private static volatile long hwnd, glfwWindow, sdlWindow;
    private static volatile boolean lwjgl2;

    // Win32 through LWJGL 3's JNI invokers (IsWindow, GetParent)
    private static Method invokePI, invokePP;
    private static long fnIsWindow, fnGetParent;
    private static boolean addressFirst;

    /** Set by the watcher; the render thread asks GLFW to close at its next frame, while the window certainly exists. */
    private static volatile boolean glfwQuitRequested;

    /** Every frame (glfwSwapBuffers hook, render thread). */
    static void glfw(long window) {
        if (glfwQuitRequested && window != 0) {
            glfwQuitRequested = false;
            try { Class.forName("org.lwjgl.glfw.GLFW", false, SfHooks.lwjglClassLoader()).getMethod("glfwSetWindowShouldClose", long.class, boolean.class).invoke(null, window, true); }
            catch (Throwable t) { SfHooks.log("lifeboat: could not ask the game to quit: " + t); }
        }
        if (started || window == 0) return;
        glfwWindow = window;
        if (SfHooks.LINUX) { X11Lifeboat.glfw(window); started = true; return; }
        try {
            Class<?> c = Class.forName("org.lwjgl.glfw.GLFWNativeWin32", false, SfHooks.lwjglClassLoader());
            start((Long) c.getMethod("glfwGetWin32Window", long.class).invoke(null, window));
        } catch (Throwable t) { give_up("GLFW", t); }
    }

    static void sdl(long window) {
        if (started || window == 0) return;
        sdlWindow = window;
        if (SfHooks.LINUX) { X11Lifeboat.sdl(window); started = true; return; }
        try {
            ClassLoader l = SfHooks.lwjglClassLoader();
            Class<?> video = Class.forName("org.lwjgl.sdl.SDLVideo", false, l), props = Class.forName("org.lwjgl.sdl.SDLProperties", false, l);
            int id = (Integer) video.getMethod("SDL_GetWindowProperties", long.class).invoke(null, window);
            long h = (Long) props.getMethod("SDL_GetPointerProperty", int.class, CharSequence.class, long.class)
                    .invoke(null, id, "SDL.window.win32.hwnd", 0L);
            start(h);
        } catch (Throwable t) { give_up("SDL", t); }
    }

    /** LWJGL 2: the native window lives in Display's WindowsDisplay implementation. */
    static void lwjgl2() {
        if (started) return;
        lwjgl2 = true;
        if (SfHooks.LINUX) { X11Lifeboat.lwjgl2(); started = true; return; }
        try {
            Class<?> display = Class.forName("org.lwjgl.opengl.Display", false, SfHooks.lwjglClassLoader());
            Method impl = display.getDeclaredMethod("getImplementation");
            impl.setAccessible(true);
            Object wd = impl.invoke(null);
            Field f = wd.getClass().getDeclaredField("hwnd");
            f.setAccessible(true);
            start(f.getLong(wd));
        } catch (Throwable t) { give_up("LWJGL 2", t); }
    }

    private static synchronized void start(long h) {
        if (started || h == 0) return;
        started = true;
        hwnd = h;
        if (!initWin32()) return;
        Thread t = new Thread(Lifeboat::watch, "sulfurframe-lifeboat");
        t.setDaemon(true);
        t.start();
        SfHooks.log("lifeboat armed for window 0x" + Long.toHexString(h));
    }

    private static void watch() {
        boolean embedded = false;
        while (true) {
            try {
                Thread.sleep(250);
                boolean alive = isWindow(hwnd);
                if (alive) { embedded = getParent(hwnd) != 0; continue; }
                if (embedded) {
                    SfHooks.log("game window destroyed while embedded (IDE gone?): asking the game to quit so the world is saved");
                    requestQuit();
                }
                return;
            } catch (Throwable t) {
                SfHooks.log("lifeboat stopped: " + t);
                return;
            }
        }
    }

    static void requestQuit() throws Exception {
        ClassLoader l = SfHooks.lwjglClassLoader();
        if (glfwWindow != 0) {
            // not from this thread: the game's own shutdown destroys the window too, and then the GLFW window is freed
            // (calling it crashed the JVM on exit). A game still running presents another frame and quits from there.
            glfwQuitRequested = true;
        } else if (sdlWindow != 0) {
            Class<?> evc = Class.forName("org.lwjgl.sdl.SDL_Event", false, l);
            Object ev = evc.getMethod("calloc").invoke(null);
            evc.getMethod("type", int.class).invoke(ev, 0x100);   // SDL_EVENT_QUIT
            Class.forName("org.lwjgl.sdl.SDLEvents", false, l).getMethod("SDL_PushEvent", evc).invoke(null, ev);
        } else if (lwjgl2) {
            Class<?> display = Class.forName("org.lwjgl.opengl.Display", false, l);
            Method impl = display.getDeclaredMethod("getImplementation");
            impl.setAccessible(true);
            Object wd = impl.invoke(null);
            Field f = wd.getClass().getDeclaredField("close_requested");
            f.setAccessible(true);
            f.setBoolean(wd, true);
        }
    }

    // ------------------------------------------------------------------ Win32

    private static boolean initWin32() {
        try {
            ClassLoader l = SfHooks.lwjglClassLoader();
            Class<?> jni = Class.forName("org.lwjgl.system.JNI", false, l);
            invokePI = SfHooks.jniMethod(jni, "PI", long.class, long.class);
            invokePP = SfHooks.jniMethod(jni, "PP", long.class, long.class);
            Class<?> winLib = Class.forName("org.lwjgl.system.windows.WindowsLibrary", false, l);
            Object user32 = winLib.getConstructor(String.class).newInstance("user32");
            Method addr = Class.forName("org.lwjgl.system.FunctionProvider", false, l).getMethod("getFunctionAddress", CharSequence.class);
            fnIsWindow = (Long) addr.invoke(user32, "IsWindow");
            fnGetParent = (Long) addr.invoke(user32, "GetParent");
            Class<?> ver = Class.forName("org.lwjgl.Version", false, l);
            addressFirst = ver.getField("VERSION_MAJOR").getInt(null) == 3 && ver.getField("VERSION_MINOR").getInt(null) < 2;
            return true;
        } catch (Throwable t) {
            // LWJGL 2 games have no LWJGL 3 JNI; Minecraft 1.8+ ships JNA, use it if it is there
            try {
                ClassLoader l = SfHooks.lwjglClassLoader();
                Class<?> fn = Class.forName("com.sun.jna.Function", false, l);
                jnaPointer = Class.forName("com.sun.jna.Pointer", false, l).getConstructor(long.class);
                Method get = fn.getMethod("getFunction", String.class, String.class);
                jnaIsWindow = get.invoke(null, "user32", "IsWindow");
                jnaGetParent = get.invoke(null, "user32", "GetParent");
                jnaInvokeInt = fn.getMethod("invokeInt", Object[].class);
                jnaInvokePointer = fn.getMethod("invokePointer", Object[].class);
                jnaPeer = Class.forName("com.sun.jna.Pointer", false, l).getMethod("nativeValue", Class.forName("com.sun.jna.Pointer", false, l));
                SfHooks.log("lifeboat uses JNA for Win32 access");
                return true;
            } catch (Throwable t2) {
                SfHooks.log("lifeboat unavailable (no Win32 access: no LWJGL 3 JNI, no JNA)");
                return false;
            }
        }
    }

    // JNA fallback (LWJGL 2 games)
    private static Object jnaIsWindow, jnaGetParent;
    private static Method jnaInvokeInt, jnaInvokePointer, jnaPeer;
    private static java.lang.reflect.Constructor<?> jnaPointer;

    private static boolean isWindow(long h) throws Exception {
        if (jnaIsWindow != null) return (Integer) jnaInvokeInt.invoke(jnaIsWindow, (Object) new Object[]{jnaPointer.newInstance(h)}) != 0;
        return (Integer) (addressFirst ? invokePI.invoke(null, fnIsWindow, h) : invokePI.invoke(null, h, fnIsWindow)) != 0;
    }

    private static long getParent(long h) throws Exception {
        if (jnaGetParent != null) {
            Object p = jnaInvokePointer.invoke(jnaGetParent, (Object) new Object[]{jnaPointer.newInstance(h)});
            return p == null ? 0 : (Long) jnaPeer.invoke(null, p);
        }
        return (Long) (addressFirst ? invokePP.invoke(null, fnGetParent, h) : invokePP.invoke(null, h, fnGetParent));
    }

    private static void give_up(String what, Throwable t) {
        started = true;
        SfHooks.log("lifeboat: cannot find the " + what + " window handle (" + t.getClass().getSimpleName() + ")");
    }

    private Lifeboat() {}
}
