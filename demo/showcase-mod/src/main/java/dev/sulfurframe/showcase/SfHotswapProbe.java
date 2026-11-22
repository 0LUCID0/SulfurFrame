package dev.sulfurframe.showcase;

/** SulfurFrame self-test probe: logs "[SF-PROBE] " + message() every 5 seconds; the test hotswaps message(). */
public final class SfHotswapProbe {
    static int ticks;

    static String message() { return "v1"; }

    /** The constant is read here, not in the loop: a running loop frame keeps its old code after a swap. */
    static String tag() { return SfProbeConst.TAG; }

    private static Thread thread;

    /** Once: SulfurFrame may run onInitialize again after it changed. */
    static void start() {
        if (thread != null) return;
        Thread t = thread = new Thread(() -> {
            while (true) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                ticks++;
                Showcase.LOGGER.info("[SF-PROBE] " + message() + " " + tag());
            }
        }, "sf-probe");
        t.setDaemon(true);
        t.start();
    }

    private SfHotswapProbe() {}
}
