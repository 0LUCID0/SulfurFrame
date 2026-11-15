package dev.sulfurframe.idea.embed;

import java.awt.Component;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * A running game shown inside an IDE component. Windows: {@link GameEmbedder} (Win32 SetParent). Linux:
 * {@link X11Embedder} (XReparentWindow; needs the IDE on XToolkit, not the Wayland toolkit).
 */
public interface GameView {
    /** The game's own word on whether it is fullscreen (true / false), or null if unknown; lets F11 be told apart from a merely borderless window. */
    default void setFullscreenHint(java.util.function.Supplier<Boolean> hint) { }
    Component component();
    boolean attach(long pid);
    void detach();
    void dispose();
    boolean attached();
    long gamePid();
    void setAgentJar(Path jar);
    void setAspect(double aspect);
    double aspect();
    /** Native handle of the game window (HWND / X window), 0 when not embedded. */
    long gameHwnd();
    /** Native handle of the canvas the game is embedded in. */
    long canvasNativeHwnd();
    long parkedHwnd();
    String throttleState();
    boolean isFullscreen();
    void setHidden(boolean hidden);
    void focusGame();

    static boolean linux() { return System.getProperty("os.name", "").startsWith("Linux"); }

    static GameView create(Consumer<String> log) {
        return linux() ? new X11Embedder(log) : new GameEmbedder(log);
    }
}
