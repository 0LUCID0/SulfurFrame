package dev.sulfurframe.idea;

import com.intellij.ide.util.PropertiesComponent;

/**
 * IDE-wide SulfurFrame settings (Settings | Tools | SulfurFrame, the tool window's toggles and the status bar menu
 * all read and write these). Keys predating this class are kept, so earlier choices carry over.
 */
public final class Settings {
    static final String HOTSWAP_ON_SAVE = "sulfurframe.hotswapOnSave", SHARED_WORLDS = "sulfurframe.sharedInstance",
            DEBUG = "sulfurframe.debug", KEEP_RATIO = "sulfurframe.keepRatio", CODING_FPS = "sulfurframe.codingFps",
            AGENT_BRIDGE = "sulfurframe.agentBridge";

    public static boolean hotswapOnSave() { return get(HOTSWAP_ON_SAVE, true); }
    public static boolean sharedWorlds() { return get(SHARED_WORLDS, false); }
    public static boolean debug() { return get(DEBUG, false); }
    public static boolean keepRatio() { return get(KEEP_RATIO, true); }
    /** Coding agents may reach the running game through the `sf` command (Bridge). */
    public static boolean agentBridge() { return get(AGENT_BRIDGE, true); }

    /** Frame rate cap while the editor (not the game) has the keyboard; 0 = no cap. */
    public static int codingFps() {
        try { return PropertiesComponent.getInstance().getInt(CODING_FPS, 15); } catch (Throwable outsideIde) { return 15; }
    }

    static void set(String key, boolean v, boolean def) { PropertiesComponent.getInstance().setValue(key, v, def); }
    static void setCodingFps(int fps) { PropertiesComponent.getInstance().setValue(CODING_FPS, fps, 15); }

    private static boolean get(String key, boolean def) {
        try { return PropertiesComponent.getInstance().getBoolean(key, def); } catch (Throwable outsideIde) { return def; }
    }

    private Settings() {}
}
