import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Automated checks for the embedding PoC. Prints "RESULT ..." lines and a summary.
 * With --robot it also drives the real mouse/keyboard for a few seconds (focus + hang tests).
 */
final class SelfTest {
    private final EmbedHost host;
    private final boolean robot;
    private final List<String> results = new ArrayList<>();
    private int failures;

    private final long attachPid;   // 0 = launch our own SDL target; otherwise embed this process (e.g. Minecraft)

    SelfTest(EmbedHost host, boolean robot, long attachPid) { this.host = host; this.robot = robot; this.attachPid = attachPid; }

    boolean external() { return attachPid != 0; }

    int run() {
        try {
            if (robot) EmbedHost.onEdt(host.frame::toFront);
            if (external()) Thread.ofPlatform().daemon().start(() -> host.attachPid(attachPid, EmbedHost.Mode.REPARENT));
            else host.launchTarget(EmbedHost.Mode.REPARENT);
            if (!waitFor(() -> host.target != 0, 20_000)) { fail("launch", "target window never embedded"); return finish(); }
            EmbedHost.sleep(800);

            for (var mode : EmbedHost.Mode.values()) {
                if (mode == EmbedHost.Mode.OVERLAY) {
                    long h = host.target;
                    EmbedHost.onEdt(() -> host.embed(h, EmbedHost.Mode.OVERLAY));
                    EmbedHost.sleep(500);
                }
                String m = mode.name();
                checkStructure(m);
                checkResize(m);
                checkStall(m, false);
                checkStall(m, true);
                if (robot) {
                    checkFocusRoundTrip(m);
                    checkEditorWhileHung(m);
                }
            }
        } catch (Throwable t) {
            fail("exception", t.toString());
            t.printStackTrace();
        }
        return finish();
    }

    // ------------------------------------------------------------------ checks

    void checkStructure(String m) {
        long h = host.target, canvas = host.viewportHwnd(), frame = host.frameHwnd();
        long style = Win32.getLongPtr(h, Win32.GWL_STYLE);
        if (m.equals("REPARENT")) {
            boolean ok = Win32.getAncestor(h, Win32.GA_PARENT) == canvas && (style & Win32.WS_CHILD) != 0
                    && (style & Win32.WS_CAPTION) != Win32.WS_CAPTION;
            check(m + " structure", ok, "parent=" + Win32.hex(Win32.getAncestor(h, Win32.GA_PARENT)) + " canvas=" + Win32.hex(canvas)
                    + " style=" + Long.toHexString(style));
        } else {
            boolean ok = Win32.getWindow(h, Win32.GW_OWNER) == frame && (style & Win32.WS_CHILD) == 0;
            check(m + " structure", ok, "owner=" + Win32.hex(Win32.getWindow(h, Win32.GW_OWNER)) + " frame=" + Win32.hex(frame));
        }
    }

    void checkResize(String m) {
        EmbedHost.onEdt(() -> host.frame.setSize(1300, 820));
        EmbedHost.sleep(900);
        EmbedHost.onEdt(() -> host.frame.setLocation(120, 90));   // overlay must follow moves too
        EmbedHost.sleep(600);
        long canvas = host.viewportHwnd();
        var c = Win32.clientRect(canvas);
        var t = Win32.clientRect(host.target);
        boolean sizeOk = Math.abs(c.width() - t.width()) <= 1 && Math.abs(c.height() - t.height()) <= 1;
        String posInfo = "";
        boolean posOk = true;
        if (m.equals("OVERLAY")) {
            var cw = Win32.windowRect(canvas);
            var tw = Win32.windowRect(host.target);
            posOk = Math.abs(cw.left() - tw.left()) <= 1 && Math.abs(cw.top() - tw.top()) <= 1;
            posInfo = " canvasAt=" + cw.left() + "," + cw.top() + " targetAt=" + tw.left() + "," + tw.top();
        }
        String px = lastTargetLine("EVT pixelsize");
        check(m + " resize follows viewport", sizeOk && posOk,
                "canvas=" + c.width() + "x" + c.height() + " target=" + t.width() + "x" + t.height() + posInfo + " | target saw: " + px);
    }

    void checkStall(String m, boolean async) {
        host.stallTarget(2500);
        waitFor(() -> lastTargetLine("STALL").startsWith("STALL begin"), 3000);
        EmbedHost.sleep(300);
        long ms = EmbedHost.onEdt(() -> host.timedResize(async));
        waitFor(() -> lastTargetLine("STALL").equals("STALL end"), 5000);
        EmbedHost.sleep(300);
        if (async) check(m + " async SetWindowPos while game hung", ms < 100, ms + " ms on EDT");
        else info(m + " sync SetWindowPos while game hung (hazard demo)", ms + " ms on EDT"
                + (ms > 1000 ? "  -> EDT blocked until the game pumped messages again" : ""));
    }

    void checkFocusRoundTrip(String m) throws AWTException {
        var r = new Robot();
        r.setAutoDelay(40);
        clickCenter(r, host.viewport);
        EmbedHost.sleep(400);
        long focusAfterClick = Win32.foregroundFocus();
        int before = host.targetLog.size();
        key(r, KeyEvent.VK_K);
        EmbedHost.sleep(400);
        // our SDL target logs keys; for an external game we check where keyboard focus actually is
        boolean gameGotKey = external()
                ? focusAfterClick == host.target
                : host.targetLog.subList(before, host.targetLog.size()).stream().anyMatch(s -> s.startsWith("EVT key"));

        clickCenter(r, host.editor);
        EmbedHost.sleep(300);
        String marker = "q";
        int len0 = EmbedHost.onEdt(() -> host.editor.getText().length());
        key(r, KeyEvent.VK_Q);
        EmbedHost.sleep(400);
        boolean editorGotKey = EmbedHost.onEdt(() -> host.editor.getText().length() > len0 && host.editor.getText().endsWith(marker));
        check(m + " focus: click game -> keys go to game", gameGotKey,
                "focus=" + Win32.hex(focusAfterClick) + " (" + (focusAfterClick == 0 ? "-" : Win32.className(focusAfterClick)) + ")");
        check(m + " focus: click editor -> keys go to editor", editorGotKey, "");
    }

    /** The scenario that matters: you were in the game, the game hangs, you click back into the editor and type. */
    void checkEditorWhileHung(String m) throws AWTException {
        var r = new Robot();
        r.setAutoDelay(30);
        clickCenter(r, host.viewport);
        EmbedHost.sleep(400);
        host.stallTarget(4000);
        waitFor(() -> lastTargetLine("STALL").startsWith("STALL begin"), 3000);
        EmbedHost.sleep(1000);  // a human needs longer than this to react; gives the watchdog time
        host.edtWatch.reset();
        int len0 = EmbedHost.onEdt(() -> host.editor.getText().length());
        long t0 = System.nanoTime();
        clickCenter(r, host.editor, false);   // no toFront here: we measure what the click itself costs
        key(r, KeyEvent.VK_Z);
        boolean typed = waitFor(() -> EmbedHost.onEdt(() -> host.editor.getText().length() > len0), 6000);
        long latency = (System.nanoTime() - t0) / 1_000_000;
        waitFor(() -> lastTargetLine("STALL").equals("STALL end"), 6000);
        EmbedHost.sleep(300);
        check(m + " editor usable while game hung", typed && latency < 1000,
                "keystroke reached editor after " + latency + " ms, EDT max stall " + host.edtWatch.maxSinceReset() + " ms");
    }

    // ------------------------------------------------------------------ helpers

    /** Robot input must never land in another app: abort unless the foreground window is ours or the game's. */
    void requireOurForeground() {
        long fg = Win32.foregroundWindow();
        long pid = fg == 0 ? 0 : Win32.processIdOf(fg);
        if (pid != ProcessHandle.current().pid() && pid != host.targetPid)
            throw new IllegalStateException("foreground is another app (" + Win32.className(fg) + "), robot input aborted");
    }

    void key(Robot r, int vk) {
        requireOurForeground();
        r.keyPress(vk);
        r.keyRelease(vk);
    }

    void clickCenter(Robot r, Component c) { clickCenter(r, c, true); }

    void clickCenter(Robot r, Component c, boolean raise) {
        if (raise) {
            EmbedHost.onEdt(host.frame::toFront);
            EmbedHost.sleep(150);
        }
        requireOurForeground();
        var p = EmbedHost.onEdt(() -> { var l = c.getLocationOnScreen(); return new Point(l.x + c.getWidth() / 2, l.y + c.getHeight() / 2); });
        r.mouseMove(p.x, p.y);
        r.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    String lastTargetLine(String prefix) {
        var l = host.targetLog;
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).startsWith(prefix)) return l.get(i);
        return "";
    }

    static boolean waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            EmbedHost.sleep(50);
        }
        return cond.getAsBoolean();
    }

    void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        add((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
    }

    void info(String name, String detail) { add("INFO " + name + "  [" + detail + "]"); }
    void fail(String name, String detail) { check(name, false, detail); }

    void add(String s) {
        results.add(s);
        System.out.println("RESULT " + s);
    }

    int finish() {
        EmbedHost.onEdt(host::shutdown);
        System.out.println("\n===== SulfurFrame embed PoC self-test =====");
        results.forEach(System.out::println);
        System.out.println("failures: " + failures);
        return failures == 0 ? 0 : 1;
    }
}
