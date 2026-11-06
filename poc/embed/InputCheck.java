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
        util.snapshotAs("sf-create-1-screen");
        util.clickViewport(r, 266.0 / 854, 444.0 / 480);    // Create New World (bottom left)
        EmbedHost.sleep(25000);
        util.snapshotAs("sf-create-2-world");
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
