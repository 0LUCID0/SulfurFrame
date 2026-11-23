package dev.sulfurframe.mojtest;

/** SulfurFrame self-test probe: logs "[SF-PROBE] " + message() every 5 seconds; the test hotswaps message(). */
public final class SfHotswapProbe {
    static String message() { return "v1"; }

    private static Thread thread;

    /** Once: SulfurFrame may run onInitialize again after it changed. */
    static void start() {
        if (thread != null) return;
        Thread t = thread = new Thread(() -> {
            while (true) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                MojangMod.LOGGER.info("[SF-PROBE] " + message());
            }
        }, "sf-probe");
        t.setDaemon(true);
        t.start();
    }

    private SfHotswapProbe() {}
}
