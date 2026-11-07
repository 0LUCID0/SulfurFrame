import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Does the embedded game really react to input? (Win32 focus alone is not enough: see the SDL3 finding.)
 * Title screen: click "Singleplayer" (mouse) -> world list, press ESC (keyboard) -> back to the title screen.
 * Screenshots: sf-input-0-before.png, sf-input-1-after-click.png, sf-input-2-after-esc.png (SF_SNAP_DIR or temp).
 *
 * usage: java EmbedHost.java --input-check --attach <pid> [--agent sf-agent.jar]
 */
final class InputCheck {
    private final EmbedHost host;
    private final long pid;
    private final ThrottleProbe util;   // reuses clickViewport / snapshot / requireGameFocus

    InputCheck(EmbedHost host, long pid) { this.host = host; this.pid = pid; this.util = new ThrottleProbe(host, pid); }

    /** Title screen -> Singleplayer -> (empty list opens Create World) -> "Create New World". 1.21.x layout, 854x480. */
    int createWorld() throws Exception {
        host.attachPid(pid, EmbedHost.Mode.REPARENT);
        if (host.target == 0) return 1;
        EmbedHost.onEdt(host.frame::toFront);
        EmbedHost.sleep(2000);
        var r = new Robot();
        util.clickViewport(r, 0.5, 236.0 / 480);            // Singleplayer
        EmbedHost.sleep(5000);
        // 1.12.2 shows an (empty) world list first: SF_CREATE_LIST_BTN="0.684,0.823" clicks its Create New World
        String listBtn = System.getenv("SF_CREATE_LIST_BTN");
        if (listBtn != null) {
            var xy = listBtn.split(",");
            util.clickViewport(r, Double.parseDouble(xy[0]), Double.parseDouble(xy[1]));
            EmbedHost.sleep(3000);
        }
        util.snapshotAs("sf-create-1-screen");
        util.clickViewport(r, 266.0 / 854, 444.0 / 480);    // Create New World (bottom left)
        EmbedHost.sleep(25000);
        util.snapshotAs("sf-create-2-world");
        EmbedHost.onEdt(host::detach);
        return 0;
    }

    /** Close whatever screen is open with ESC, then force real resizes and snapshot (stale framebuffer size check). */
    int escAndResize() throws Exception {
        host.attachPid(pid, EmbedHost.Mode.REPARENT);
        if (host.target == 0) return 1;
        EmbedHost.onEdt(host.frame::toFront);
        EmbedHost.sleep(1500);
        host.focusGameFromHelper(host.target);
        EmbedHost.sleep(500);
        util.requireGameFocus();
        var r = new Robot();
        // SF_KEY=F2: a key whose effect shows up in the game log (screenshot), for windows the screen capture does not refresh
        int key = "F2".equals(System.getenv("SF_KEY")) ? KeyEvent.VK_F2 : KeyEvent.VK_ESCAPE;
        r.keyPress(key); r.keyRelease(key);
        EmbedHost.sleep(800);
        util.snapshotAs("sf-resize-0-after-esc");
        var orig = EmbedHost.onEdt(() -> host.frame.getSize());
        EmbedHost.onEdt(() -> host.frame.setSize(orig.width + 120, orig.height + 80));
        EmbedHost.sleep(1200);
        EmbedHost.onEdt(() -> host.frame.setSize(orig));
        EmbedHost.sleep(1500);
        var c = Win32.clientRect(host.viewportHwnd());
        var t = Win32.clientRect(host.target);
        host.log("[input] canvas " + c.width() + "x" + c.height() + ", game client " + t.width() + "x" + t.height());
        util.snapshotAs("sf-resize-1-after-resize");
        EmbedHost.onEdt(host::detach);
        return 0;
    }

    /** Title -> Singleplayer -> double-click the first world (versions without --quickPlaySingleplayer). */
    int openFirstWorld() throws Exception {
        host.attachPid(pid, EmbedHost.Mode.REPARENT);
        if (host.target == 0) return 1;
        EmbedHost.onEdt(host.frame::toFront);
        EmbedHost.sleep(2000);
        var r = new Robot();
        util.clickViewport(r, 0.5, 236.0 / 480);            // Singleplayer
        EmbedHost.sleep(3000);
        util.snapshotAs("sf-open-1-list");
        util.clickViewport(r, 0.5, 132.0 / 480);            // first world entry
        EmbedHost.sleep(150);
        util.clickViewport(r, 0.5, 132.0 / 480);            // second click = double-click
        EmbedHost.sleep(20000);
        util.snapshotAs("sf-open-2-world");
        EmbedHost.onEdt(host::detach);
        return 0;
    }

    int run() throws Exception {
        host.attachPid(pid, EmbedHost.Mode.REPARENT);
        if (host.target == 0) return 1;
        EmbedHost.onEdt(host.frame::toFront);
        EmbedHost.sleep(2500);
        util.snapshotAs("sf-input-0-before");

        var r = new Robot();
        double singleplayerY = 236.0 / 480;   // title screen at 854x480, GUI scale 2
        util.clickViewport(r, 0.5, singleplayerY);
        EmbedHost.sleep(700);
        host.log("[input] after click 1: focus=" + Win32.hex(Win32.foregroundFocus()) + " game=" + Win32.hex(host.target));
        util.snapshotAs("sf-input-1-after-click");

        util.requireGameFocus();
        r.keyPress(KeyEvent.VK_ESCAPE); r.keyRelease(KeyEvent.VK_ESCAPE);
        EmbedHost.sleep(900);
        util.snapshotAs("sf-input-2-after-esc");

        EmbedHost.onEdt(host::detach);
        return 0;
    }
}
