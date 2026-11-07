package dev.sulfurframe.idea.embed;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Focus-aware throttling of the embedded game, done entirely from the host (no mod).
 *
 *   ACTIVE  the game has keyboard focus                -> nothing to do
 *   CODING  the editor has focus, viewport visible     -> nothing to do: vanilla pauseOnLostFocus opens the pause
 *                                                         menu and the integrated server stops ticking
 *   HIDDEN  viewport not visible (tool window hidden,  -> minimize the game window (vanilla caps iconified windows
 *           IDE minimized)                                at 10 fps), then after {@link #reclaimDelayMs}:
 *                                                         tighten G1's free-ratio flags + GC (heap shrinks),
 *                                                         then trim the working set (physical RAM back to the OS)
 *
 * Leaving HIDDEN restores the JVM flags and the window. All window calls are async (never wait on the game);
 * jcmd runs on a worker thread.
 */
final class Throttler {
    enum State { ACTIVE, CODING, HIDDEN }

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("sf-throttler").unstarted(r));
    private final Consumer<String> log;
    private volatile State state = State.CODING;
    private volatile long hwnd, pid;
    private ScheduledFuture<?> pendingReclaim;
    private volatile boolean flagsTightened;
    volatile long reclaimedAtNanos;   // for tests
    volatile long reclaimDelayOverrideMs = -1;
    final long reclaimDelayMs;

    /** How the platform hides/shows the game window and hands physical memory back. */
    interface WindowOps {
        void hide(long window);
        void show(long window);
        /** true if pages were returned to the OS */
        boolean trim(long pid);
    }

    /** Windows: minimize (vanilla caps an iconified window at 10 fps), restore, EmptyWorkingSet. */
    static final WindowOps WIN32 = new WindowOps() {
        public void hide(long w) { Win32.showWindow(w, Win32.SW_SHOWMINNOACTIVE); }
        public void show(long w) { Win32.showWindow(w, Win32.SW_SHOWNOACTIVATE); }
        public boolean trim(long pid) { return Win32.emptyWorkingSet(pid); }
    };

    private final WindowOps ops;

    Throttler(Consumer<String> log, long reclaimDelayMs) { this(log, reclaimDelayMs, WIN32); }

    Throttler(Consumer<String> log, long reclaimDelayMs, WindowOps ops) { this.log = log; this.reclaimDelayMs = reclaimDelayMs; this.ops = ops; }

    void bind(long hwnd, long pid) { this.hwnd = hwnd; this.pid = pid; }

    State state() { return state; }

    synchronized void onGameFocused() { transition(State.ACTIVE); }
    synchronized void onEditorFocused() { if (state != State.HIDDEN) transition(State.CODING); }
    synchronized void onViewportHidden() { transition(State.HIDDEN); }
    synchronized void onViewportShown() { if (state == State.HIDDEN) transition(State.CODING); }

    private void transition(State next) {
        State prev = state;
        if (prev == next || hwnd == 0) { state = next; return; }
        state = next;
        log.accept("[throttle] " + prev + " -> " + next);
        if (next == State.HIDDEN) enterHidden();
        else if (prev == State.HIDDEN) leaveHidden();
    }

    private void enterHidden() {
        ops.hide(hwnd);
        long d = reclaimDelayOverrideMs >= 0 ? reclaimDelayOverrideMs : reclaimDelayMs;
        pendingReclaim = worker.schedule(this::reclaim, d, TimeUnit.MILLISECONDS);
    }

    /** Runs on the worker, only if we are still hidden when the delay expires (quick peeks cost nothing). */
    private void reclaim() {
        if (state != State.HIDDEN) return;
        long t0 = System.nanoTime();
        jcmd("VM.set_flag MinHeapFreeRatio 0");
        jcmd("VM.set_flag MaxHeapFreeRatio 10");
        flagsTightened = true;
        jcmd("GC.run");
        if (state != State.HIDDEN) return;                  // came back during the GC: keep the pages
        boolean trimmed = ops.trim(pid);
        reclaimedAtNanos = System.nanoTime();
        log.accept("[throttle] reclaimed (heap shrink + working-set trim=" + trimmed + ") in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }

    private void leaveHidden() {
        if (pendingReclaim != null) pendingReclaim.cancel(false);
        ops.show(hwnd);
        worker.execute(() -> {
            if (!flagsTightened) return;
            // JVM defaults; order matters (Min must stay <= Max)
            jcmd("VM.set_flag MaxHeapFreeRatio 70");
            jcmd("VM.set_flag MinHeapFreeRatio 40");
            flagsTightened = false;
        });
    }

    void shutdown() {
        synchronized (this) { if (state == State.HIDDEN) leaveHidden(); }
        worker.shutdown();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    /** jcmd next to the game's own java (JDK installs have it), else the IDE runtime's (JBR ships jcmd). */
    static String jcmdFor(long pid) {
        var game = ProcessHandle.of(pid).flatMap(p -> p.info().command()).map(Path::of).orElse(null);
        if (game != null) {
            Path j = game.resolveSibling(EXE);
            if (java.nio.file.Files.exists(j)) return j.toString();
        }
        return Path.of(System.getProperty("java.home"), "bin", EXE).toString();
    }

    private static final String EXE = System.getProperty("os.name", "").startsWith("Windows") ? "jcmd.exe" : "jcmd";

    String jcmd(String cmd) {
        try {
            String exe = jcmdFor(pid);
            var p = new ProcessBuilder(exe, Long.toString(pid), cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Exception e) {
            log.accept("[throttle] jcmd " + cmd + " failed: " + e);
            return "";
        }
    }
}
