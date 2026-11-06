package dev.sulfurframe.agent;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Called from the patched Minecraft methods. Must be cheap (isFocused runs many times per frame) and must never throw.
 * Depends on nothing from Minecraft, so it works whatever class loader the game lives in.
 */
public final class SfHooks {
    static volatile int codingFps = 15;
    private static volatile long lastWindow;          // last non-zero SDL window seen in an event
    private static volatile long focusCheckedAt;
    private static volatile boolean focusCached = true;
    private static final long FOCUS_CACHE_NS = 20_000_000L;   // 20 ms
    private static final long SELF_PID = ProcessHandle.current().pid();

    private static final MethodHandle GetFocus, GetWindowThreadProcessId;

    static {
        MethodHandle gf = null, gwtp = null;
        try {
            var linker = Linker.nativeLinker();
            var u32 = SymbolLookup.libraryLookup("user32", Arena.global());
            gf = linker.downcallHandle(u32.find("GetFocus").orElseThrow(), FunctionDescriptor.of(JAVA_LONG));
            gwtp = linker.downcallHandle(u32.find("GetWindowThreadProcessId").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
        } catch (Throwable t) {
            log("native access unavailable, focus patch disabled (" + t + "); launch with --enable-native-access=ALL-UNNAMED");
        }
        GetFocus = gf;
        GetWindowThreadProcessId = gwtp;
    }

    /** args: "codingFps=15" (comma separated key=value). */
    static void configure(String args) {
        if (args == null) return;
        for (var kv : args.split(",")) {
            var p = kv.split("=", 2);
            if (p.length == 2 && p[0].strip().equals("codingFps")) codingFps = Integer.parseInt(p[1].strip());
        }
    }

    /**
     * Window.isFocused(): true when the window with Win32 keyboard focus belongs to this process.
     * GetFocus() only sees windows attached to the calling thread's input queue; SetParent attached the game's queue
     * to the IDE's, so this reports the IDE's editor as "not ours" and the clicked game window as "ours".
     * Not embedded, it behaves like normal focus for a top-level window.
     */
    public static boolean focused(boolean original) {
        if (GetFocus == null) return original;
        long now = System.nanoTime();
        if (now - focusCheckedAt < FOCUS_CACHE_NS) return focusCached;
        boolean f;
        try {
            long h = (long) GetFocus.invokeExact();
            f = h != 0 && pidOf(h) == SELF_PID;
        } catch (Throwable t) {
            f = original;
        }
        focusCached = f;
        focusCheckedAt = now;
        return f;
    }

    /** SDLEventHandler.getWindowHandle(): SDL gives key events windowID 0 when it thinks no window has focus. */
    public static long windowHandle(long original) {
        if (original != 0) { lastWindow = original; return original; }
        return lastWindow;   // only events for this process's own window reach SDL at all
    }

    /** FramerateLimitTracker.getFramerateLimit(): cap the frame rate while the user is in the editor. */
    public static int fpsLimit(int original) {
        return codingFps > 0 && !focused(true) ? Math.min(original, codingFps) : original;
    }

    private static long pidOf(long hwnd) throws Throwable {
        try (var a = Arena.ofConfined()) {
            var pid = a.allocate(JAVA_INT);
            int unused = (int) GetWindowThreadProcessId.invokeExact(hwnd, pid);
            return pid.get(JAVA_INT, 0) & 0xFFFFFFFFL;
        }
    }

    static void log(String s) {
        System.out.println("[SF-Agent] " + s);
    }

    private SfHooks() {}
}
