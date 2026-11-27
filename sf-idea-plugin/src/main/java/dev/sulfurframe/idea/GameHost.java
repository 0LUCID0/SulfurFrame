package dev.sulfurframe.idea;

import dev.sulfurframe.idea.profile.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What runs the game for a project: the IDE's panel (SulfurPanel) or the terminal host without IntelliJ
 * (HeadlessHost, `sf run`). The agent bridge (`sf` commands) talks to either through this.
 */
interface GameHost {
    Workspace.Target selectedTarget();
    long gamePid();
    boolean gameAttached();
    /** Folder name of the open world, "" when none. Blocking. */
    String openWorld() throws Exception;
    Hotswapper hotswapper();
    Path agentJar();
    void log(String s);
    /** A feedback report was saved: offer the person to send it as a GitHub issue (the IDE asks; the terminal host does not). */
    default void offerIssue(Path report) {}

    /** Swaps, reloads and restarts running now / started so far. */
    int busy();
    long activity();
    /** Log position when the latest work started, and when the latest work ended (sf wait arriving just after it). */
    long lastWorkStartSeq();
    long lastWorkEndMillis();
    long journalSeq();
    List<String> journalSince(long seq);

    void hotswapAsync();
    void restartAsync();

    /** What the crosshair is on; false when the game did not answer. Blocking. */
    boolean inspect();
    Map<String, String> lastLook();
    Map<String, String> stats();
    void startStats(long pid);

    /** SulfurFrame's runtime tests on this game (`sf test`); only the IDE has them. */
    default String runTests(List<String> names) throws Exception {
        throw new Bridge.Failure("sf test needs the IDE (the tests drive IntelliJ too)");
    }
}
