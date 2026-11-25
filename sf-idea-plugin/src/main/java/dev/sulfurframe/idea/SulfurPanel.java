package dev.sulfurframe.idea;

import dev.sulfurframe.idea.embed.GameView;
import dev.sulfurframe.idea.profile.ProjectProfile;
import dev.sulfurframe.idea.profile.Workspace;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tool window content: which client to run (every runnable target found in the opened folder), controls, the
 * embedded game, a short log.
 */
final class SulfurPanel extends JPanel implements GameHost {
    final GameView embedder;
    private final GameLauncher launcher;
    private final JComboBox<Workspace.Target> targets = new JComboBox<>();
    private final JTextArea log = new JTextArea(5, 40);
    String logText() { return log.getText(); }
    void setSwapOnSave(boolean on) { swapOnSave.setSelected(on); }
    boolean swapOnSaveSelected() { return swapOnSave.isSelected(); }

    /** The settings page changed something: show it here (no events, nothing is stored again). */
    void syncSettings() {
        swapOnSave.setSelected(Settings.hotswapOnSave());
        shareWorlds.setSelected(Settings.sharedWorlds());
        if (keepRatio.isSelected() != Settings.keepRatio()) { keepRatio.setSelected(Settings.keepRatio()); embedder.setAspect(keepRatio.isSelected() ? 16.0 / 9.0 : 0); }
    }
    private final Path projectDir;
    private final Supplier<String> savedTarget;
    private final Consumer<String> saveTarget;
    /** Profile of the selected target (null if none). */
    ProjectProfile profile;
    final Hotswapper hotswapper;
    private final Path agentJar;
    private final JCheckBox swapOnSave = new JCheckBox("Hotswap on save", Settings.hotswapOnSave());
    private final JCheckBox keepRatio = new JCheckBox("16:9 (black bars)", Settings.keepRatio());

    SulfurPanel(Path projectDir, Supplier<String> savedTarget, Consumer<String> saveTarget, Runnable openAsTab) {
        super(new BorderLayout());
        this.projectDir = projectDir;
        this.savedTarget = savedTarget;
        this.saveTarget = saveTarget;
        embedder = GameView.create(this::log);
        embedder.setFullscreenHint(() -> { var s = stats(); return s.containsKey("fullscreen") ? Boolean.valueOf(s.get("fullscreen")) : null; });
        launcher = new GameLauncher(this::log);
        hotswapper = new Hotswapper(this::log, Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe"));
        hotswapper.beforeInitRerun = () -> snapshot(INIT_SNAPSHOT);
        agentJar = extractAgent();
        embedder.setAgentJar(agentJar);
        launcher.setAgentJar(agentJar);
        launcher.restartAction = this::restartAsync;

        log.setEditable(false);
        log.setFont(consoleFont());   // same font as the Game log console next to it
        targets.addActionListener(e -> onTargetSelected());
        // The functions are IDE actions (SfActions, plugin.xml): the same ones are in Tools | SulfurFrame, the status
        // bar menu, Find Action and the keymap. Here they form an icon toolbar that folds what does not fit behind ».
        var group = (com.intellij.openapi.actionSystem.ActionGroup) com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("SulfurFrame.Toolbar");
        var toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance().createActionToolbar("SulfurFrame", group, true);
        toolbar.setLayoutStrategy(com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY);
        toolbar.setTargetComponent(this);
        var buttons = toolbar.getComponent();
        keepRatio.setToolTipText("Keep the game at 16:9 (black bars) instead of stretching it to the panel");
        keepRatio.addActionListener(e -> { embedder.setAspect(keepRatio.isSelected() ? 16.0 / 9.0 : 0); Settings.set(Settings.KEEP_RATIO, keepRatio.isSelected(), true); });
        if (!keepRatio.isSelected()) embedder.setAspect(0);
        swapOnSave.addActionListener(e -> Settings.set(Settings.HOTSWAP_ON_SAVE, swapOnSave.isSelected(), true));
        swapOnSave.setToolTipText("Compile and reload changed classes into the running game whenever a source file is saved");
        shareWorlds.setToolTipText("Worlds, options and resource packs shared by all projects of the same Minecraft version ("
                + SharedInstance.root() + "). Existing worlds of a project move there when it is first launched; nothing is deleted.");
        shareWorlds.addActionListener(e -> Settings.set(Settings.SHARED_WORLDS, shareWorlds.isSelected(), false));
        debug.setToolTipText("Run client with a debug port and attach the IDE debugger (breakpoints; the game pauses while stopped)");
        debug.addActionListener(e -> Settings.set(Settings.DEBUG, debug.isSelected(), false));
        var north = new JPanel(new BorderLayout());
        north.add(buttons, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(embedder.component(), BorderLayout.CENTER);
        // same layout as the Game log and Problems tabs: a button row on top, the text below
        var logTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        var clearLog = new JButton("Clear");
        clearLog.addActionListener(e -> log.setText(""));
        logTop.add(clearLog);
        // shown as a tab of the bottom "Minecraft Log" tool window, next to Game log and Problems
        logTab.add(logTop, BorderLayout.NORTH);
        logTab.add(new JScrollPane(log), BorderLayout.CENTER);
        scan();
        Thread.ofPlatform().daemon().name("sf-temp-sweep").start(() -> TempWorlds.sweep(this::log));   // left over from a previous IDE session
    }

    void attachPidDialog() {
        String s = JOptionPane.showInputDialog(this, "PID of a running Minecraft:");
        if (s != null && !s.isBlank()) embedder.attach(Long.parseLong(s.trim()));
    }

    private JButton button(String text, Runnable r) {
        var b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    /** Find every runnable client in the opened folder (single project, container of builds, multi-project, Stonecutter). */
    void scan() {
        List<Workspace.Target> found;
        try {
            found = projectDir == null ? List.of() : Workspace.scan(projectDir);
        } catch (Exception e) {
            found = List.of();
            log("scan failed: " + e);
        }
        targets.removeAllItems();
        found.forEach(targets::addItem);
        log(found.isEmpty() ? "no Minecraft mod project found in " + projectDir
                : found.size() + " runnable client" + (found.size() == 1 ? "" : "s") + " found");
        String want = savedTarget.get();
        for (int i = 0; i < targets.getItemCount(); i++)
            if (key(targets.getItemAt(i)).equals(want)) { targets.setSelectedIndex(i); break; }
        onTargetSelected();
        ensureConfigs(found);
    }

    private void onTargetSelected() {
        var t = (Workspace.Target) targets.getSelectedItem();
        profile = t == null ? null : t.profile();
        if (t == null) return;
        saveTarget.accept(key(t));
        log("selected " + t.where() + ": " + t.profile());
        t.profile().evidence().forEach(e -> log("  " + e));
        restartSourceWatcher();
    }

    // ------------------------------------------------------------------ changes made outside the IDE

    private volatile java.util.function.Consumer<Path> sourceSink;
    private SourceWatcher sourceWatcher;
    private Path watchedRoot;

    /** Where changed source files go (the tool window's debounced save handler); starts watching the selected build. */
    void watchSources(java.util.function.Consumer<Path> sink) { sourceSink = sink; restartSourceWatcher(); }

    synchronized void stopWatchingSources() {
        sourceSink = null;
        if (sourceWatcher != null) sourceWatcher.close();
        sourceWatcher = null; watchedRoot = null;
    }

    /** Watch the selected target's build (all its modules' src folders); off the EDT, the folder walk can take a moment. */
    private void restartSourceWatcher() {
        var t = (Workspace.Target) targets.getSelectedItem();
        var sink = sourceSink;
        if (t == null || sink == null) return;
        Path root = t.buildRoot().toAbsolutePath().normalize();
        Thread.ofPlatform().daemon().name("sf-source-watch-start").start(() -> {
            synchronized (this) {
                if (sourceSink == null || (root.equals(watchedRoot) && sourceWatcher != null)) return;
                if (sourceWatcher != null) sourceWatcher.close();
                sourceWatcher = SourceWatcher.start(root, sink);
                watchedRoot = root;
                if (sourceWatcher != null) log("watching " + sourceWatcher.rootCount() + " source folder(s): files changed outside the IDE are hotswapped too");
            }
        });
    }

    static String key(Workspace.Target t) { return t.key(); }

    void runClient() { runClient(List.of()); }

    void runClient(List<String> gameArgs) {
        var t = (Workspace.Target) targets.getSelectedItem();
        if (t == null || !t.profile().isComplete()) { log("pick a client to run (none detected)"); return; }
        if (launcher.running() || launching.get()) { log("the game is already running (or still starting): use Restart to start it again"); return; }
        if (!t.hasWrapper()) log("note: " + t.where() + " has no Gradle wrapper of its own; using the nearest parent's");
        startRun(t, gameArgs, debug.isSelected());
    }

    /** Every launch (Run client, Restart): shared instance first if on, Gradle, embed, and collect when the game ends. */
    private void launch(Workspace.Target t, List<String> gameArgs, boolean debugRun, SulfurRunState.Session session) {
        currentDebug = debugRun;
        launcher.output = session == null ? null : session::line;
        String mc = t.profile().minecraftVersion();
        Path run = SharedInstance.runDir(t);
        boolean share = shareWorlds.isSelected();
        if (share) {
            try { SharedInstance.apply(run, mc, t.dir().getFileName().toString(), this::log); }
            catch (Exception e) { log("shared instance not set up: " + e); }
        }
        if (gameLogPanel != null) gameLogPanel.follow(t);
        int port = 0;
        if (debugRun && project != null) {
            try { port = DebugAttach.freePort(); } catch (java.io.IOException e) { log("no free port for the debugger: " + e); }
        }
        final int debugPort = port;
        launching.set(true);
        // off the EDT: the dev mods may have to be downloaded first
        Thread.ofPlatform().daemon().name("sf-launch").start(() -> {
            try {
                var jvm = new java.util.ArrayList<String>();
                if (debugPort > 0) jvm.add(DebugAttach.jdwpArg(debugPort));
                jvm.addAll(DevMods.jvmArgs(project, t, this::log));
                launcher.setExtraJvmArgs(jvm);
                java.util.function.BiConsumer<Long, Long> onWindow = (pid, hwnd) -> SwingUtilities.invokeLater(() -> {
                    embedder.attach(pid); hotswapper.snapshot(t);
                    startStats(pid);
                    TempWorlds.unpinAll();
                    if (debugPort > 0) DebugAttach.attach(project, debugPort, this::log);
                });
                Runnable onEnd = () -> {
                    if (share) SharedInstance.collect(run, mc);
                    TempWorlds.sweep(this::log);
                    if (session != null) session.ended(launcher.exitCode());
                };
                // the same game again (Restart, Rerun, Run client): straight from its last command line if nothing
                // Gradle would redo has changed, else through Gradle as the first time
                var spec = launcher.cached(t);
                String why = spec == null ? "" : QuickRelaunch.whyNot(spec, t);
                if (why == null) why = hotswapper.prepareRelaunch(t, spec.capturedAt());
                if (spec != null && why == null && launcher.relaunch(spec, t, gameArgs, onWindow, onEnd)) return;
                if (spec != null) { log("starting through Gradle: " + (why != null ? why : "the direct start failed")); launcher.forget(t); }
                hotswapper.clearAwOverlay(t);   // Loom applies the current access widener to its jar again
                launcher.runClient(t, gameArgs, onWindow, onEnd);
            } finally {
                launching.set(false);
            }
        });
    }

    private final java.util.concurrent.atomic.AtomicBoolean launching = new java.util.concurrent.atomic.AtomicBoolean();

    /** Milliseconds from the last launch to its game window (self-test). */
    long launcherWindowMillis() { return launcher.lastWindowMillis; }

    /** The SulfurFrame log, a tab of the bottom "Minecraft Log" tool window. */
    final JPanel logTab = new JPanel(new BorderLayout());
    /** Game log, problems, crashes (needs the IDE project for source links); null until {@link #attachProject}. */
    GameLogPanel gameLogPanel;

    private com.intellij.openapi.project.Project project;

    /** Panel-started launches use the Debug executor (debug port + debugger attach); the IDE's Debug button does the same. */
    final JCheckBox debug = new JCheckBox("Debug", false);

    /** The IDE console font (Settings | Editor | Color Scheme | Console Font), what the Game log console uses. */
    private static Font consoleFont() {
        try {
            var scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme();
            return new Font(scheme.getConsoleFontName(), Font.PLAIN, scheme.getConsoleFontSize());
        } catch (Throwable t) {
            return new Font(Font.MONOSPACED, Font.PLAIN, 12);
        }
    }

    void attachProject(com.intellij.openapi.project.Project project) {
        this.project = project;
        gameLogPanel = new GameLogPanel(project, this::selectedTarget, this::restartAsync, this::log);
        var t = selectedTarget();
        if (t != null) gameLogPanel.follow(t);
        bridge = Bridge.start(this, projectDir);
        com.intellij.openapi.util.Disposer.register(project, () -> { var b = bridge; if (b != null) b.stop(); });
        // the first scan ran in the constructor, before there was a project: the run configurations come now
        var found = new java.util.ArrayList<Workspace.Target>();
        for (int i = 0; i < targets.getItemCount(); i++) found.add(targets.getItemAt(i));
        ensureConfigs(found);
    }

    /** One game folder per Minecraft version for all projects (worlds, options, resource packs). */
    final JCheckBox shareWorlds = new JCheckBox("Shared worlds", Settings.sharedWorlds());

    /** The client the IDE's selected run configuration starts (if it is one of ours), else the last one used. */
    public Workspace.Target selectedTarget() {
        if (project != null && !project.isDisposed()) {
            var sel = com.intellij.execution.RunManager.getInstance(project).getSelectedConfiguration();
            if (sel != null && sel.getConfiguration() instanceof SulfurRunConfiguration c) { var t = targetByKey(c.target); if (t != null) return t; }
        }
        return (Workspace.Target) targets.getSelectedItem();
    }

    /** Compile the selected target and reload what changed into the running game (background thread). */
    public void hotswapAsync() {
        var t = selectedTarget();
        long pid = embedder.gamePid();
        work("sf-hotswap", () -> {
            hotswapper.swap(t, pid, agentJar);
            var restartNeeded = hotswapper.lastResult.stream().filter(s -> s.startsWith("restart needed")).toList();
            Path rollback = hotswapper.failedInitSnapshot;
            if (rollback != null) offerInitRollback("init code ran again and failed: " + String.join("; ",
                    hotswapper.lastRegistration.stream().filter(s -> s.startsWith("error")).toList()), rollback);
            else if (!restartNeeded.isEmpty()) offerRestart(restartNeeded.size() + " class(es) changed in a way hotswap cannot apply");
            else if (!hotswapper.lastStartupChanges.isEmpty())
                offerRestart("startup code changed (runs only once, at launch): " + String.join(", ", hotswapper.lastStartupChanges));
        });
    }

    // ------------------------------------------------------------------ quick restart

    /** World the last restart went back into (self-test). */
    volatile String lastRestartWorld;
    private volatile boolean restarting;
    private volatile long lastOffer;

    public void restartAsync() { work("sf-restart", this::restart); }

    /**
     * Quick restart for what hotswap cannot do (new registrations, mod metadata, access wideners, build changes):
     * ask the game which world is open and to quit (it saves), wait for it to exit, then Run client again with
     * --quickPlaySingleplayer, so it lands back in the same world, embedded again. Blocking; call off the EDT.
     */
    void restart() { restart(null, null); }

    /**
     * @param worldOverride world to come back into instead of the one that was open (test world), or null
     * @param whileDown     runs after the game exited and before it starts again, with the world that was open
     *                      (restore a snapshot), or null
     */
    void restart(String worldOverride, java.util.function.Consumer<String> whileDown) {
        var t = selectedTarget();
        if (t == null || restarting) return;
        restarting = true;
        try {
            long pid = embedder.gamePid();
            String world = "";
            var game = pid > 0 ? ProcessHandle.of(pid).orElse(null) : null;
            if (game != null && game.isAlive()) {
                Path wf = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "world-" + pid + ".txt");
                Files.deleteIfExists(wf);
                Hotswapper.loadAgent(pid, agentJar, "codingFps=" + Settings.codingFps() + ",world=" + wf + ",quit");
                for (int i = 0; i < 50 && !Files.exists(wf); i++) Thread.sleep(100);
                if (Files.exists(wf)) world = Files.readString(wf, StandardCharsets.UTF_8).strip();
                log("restart: saving and quitting" + (world.isEmpty() ? "" : " (world '" + world + "')"));
                try { game.onExit().get(90, java.util.concurrent.TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException e) { log("restart: the game did not exit in 90 s, stopping it"); game.destroyForcibly(); }
            }
            if (whileDown != null) whileDown.accept(world);
            if (worldOverride != null) world = worldOverride;
            TempWorlds.pin(world);   // coming back into it: the old game's exit sweep must not take it
            var args = !world.isEmpty() && quickPlay(t.profile().minecraftVersion()) ? List.of("--quickPlaySingleplayer", world) : List.<String>of();
            lastRestartWorld = world;
            for (int i = 0; i < 300 && launcher.running(); i++) Thread.sleep(100);   // the old run ends (its Run tab shows it), then the new one starts
            SwingUtilities.invokeLater(() -> startRun(t, args, currentDebug));
        } catch (Exception e) {
            log("restart failed: " + e);
        } finally {
            restarting = false;
        }
    }

    // ------------------------------------------------------------------ worlds: snapshots, test world

    /** Folder name of the world open in the running game ("" if none / no game). */
    public String openWorld() throws Exception {
        long pid = embedder.gamePid();
        if (pid <= 0 || ProcessHandle.of(pid).isEmpty()) return "";
        Path wf = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "world-" + pid + "-q.txt");
        Files.deleteIfExists(wf);
        Hotswapper.loadAgent(pid, agentJar, "codingFps=" + Settings.codingFps() + ",world=" + wf);
        for (int i = 0; i < 50 && !Files.exists(wf); i++) Thread.sleep(100);
        return Files.exists(wf) ? Files.readString(wf, StandardCharsets.UTF_8).strip() : "";
    }

    /** Last snapshot taken (self-test). */
    volatile Path lastSnapshot;

    void snapshotAsync() { Thread.ofPlatform().daemon().name("sf-snapshot").start(() -> snapshot(null)); }

    /** Label of the automatic snapshots taken before init code runs again; the newest few are kept. */
    static final String INIT_SNAPSHOT = "before-init";

    /** Flush the open world on the integrated server, then copy it to a snapshot (null label: by hand). Blocking. */
    Path snapshot(String label) {
        var t = selectedTarget();
        try {
            String world = openWorld();
            if (world.isEmpty()) { if (label == null) log("snapshot: open a world in the game first"); return null; }
            long pid = embedder.gamePid();
            Path done = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "flush-" + pid + ".txt");
            Files.deleteIfExists(done);
            // the integrated server's own save (save-all is a dedicated-server command: in singleplayer it does not exist)
            Hotswapper.loadAgent(pid, agentJar, "codingFps=" + Settings.codingFps() + ",save=" + done);
            for (int i = 0; i < 150 && !Files.exists(done); i++) Thread.sleep(100);
            String flushed = Files.exists(done) ? Files.readAllLines(done).stream().findFirst().orElse("").strip() : "no answer";   // line 2: what it said
            if (!flushed.equals("done")) log("snapshot: world not written out first: " + flushed + " (copying anyway)");
            Path snap = WorldSnapshots.take(SharedInstance.runDir(t).resolve("saves"), world, t.profile().minecraftVersion(), label);
            if (label == null) lastSnapshot = snap;
            else WorldSnapshots.prune(t.profile().minecraftVersion(), world, label, 3);
            log("snapshot of '" + world + "': " + snap);
            return snap;
        } catch (Exception e) {
            log("snapshot failed: " + e);
            return null;
        }
    }

    /**
     * Init code that ran again went wrong (an error in the game, or a crash soon after): offer a restart into the world as
     * it was right before (the automatic snapshot), next to the plain restart.
     */
    void offerInitRollback(String why, Path snapshot) {
        log("restart needed: " + why + " (the world before it: " + snapshot.getFileName() + ")");
        String world = snapshot.getParent().getFileName().toString();
        lastOffer = System.currentTimeMillis();
        lastRollbackOffer = snapshot;
        SwingUtilities.invokeLater(() -> {
            try {
                var n = com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("SulfurFrame")
                        .createNotification("Minecraft needs a restart", why, com.intellij.notification.NotificationType.WARNING);
                n.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Restart with the world from before",
                        () -> Thread.ofPlatform().daemon().name("sf-restore").start(() -> restoreSnapshot(world, snapshot))));
                n.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Restart into the same world", this::restartAsync));
                n.notify(null);
            } catch (Throwable ignored) { }
        });
    }

    /** The snapshot the last rollback offer pointed at (self-test). */
    volatile Path lastRollbackOffer;

    void restoreAsync() {
        Thread.ofPlatform().daemon().name("sf-restore").start(() -> {
            try {
                var t = selectedTarget();
                String world = openWorld();
                Path saves = SharedInstance.runDir(t).resolve("saves");
                if (world.isEmpty()) {   // no game: pick the world first
                    String[] worlds;
                    try (var s = Files.list(saves)) { worlds = s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toArray(String[]::new); }
                    if (worlds.length == 0) { log("restore: no worlds in " + saves); return; }
                    var pick = new String[1];
                    SwingUtilities.invokeAndWait(() -> pick[0] = (String) JOptionPane.showInputDialog(this, "World:", "Restore a snapshot",
                            JOptionPane.PLAIN_MESSAGE, null, worlds, worlds[0]));
                    if (pick[0] == null) return;
                    world = pick[0];
                }
                var snaps = WorldSnapshots.list(t.profile().minecraftVersion(), world);
                if (snaps.isEmpty()) { log("restore: no snapshots of '" + world + "' yet"); return; }
                var names = snaps.stream().map(WorldSnapshots::label).toArray(String[]::new);
                var pick = new String[1];
                final String w = world;
                SwingUtilities.invokeAndWait(() -> pick[0] = (String) JOptionPane.showInputDialog(this, "Snapshot of '" + w + "':", "Restore a snapshot",
                        JOptionPane.PLAIN_MESSAGE, null, names, names[0]));
                if (pick[0] != null) restoreSnapshot(w, snaps.get(Arrays.asList(names).indexOf(pick[0])));
            } catch (Exception e) { log("restore failed: " + e); }
        });
    }

    /** Quit the game, put the snapshot in place of the world, start again in it. Blocking. */
    void restoreSnapshot(String world, Path snapshot) {
        var t = selectedTarget();
        Path saves = SharedInstance.runDir(t).resolve("saves");
        restart(world, open -> {
            try {
                WorldSnapshots.restore(saves, world, t.profile().minecraftVersion(), snapshot);
                log("restored '" + world + "' from " + WorldSnapshots.label(snapshot) + " (the replaced world was kept as a snapshot)");
            } catch (IOException e) { log("restore failed: " + e); }
        });
    }

    /** Name of the last test world created (self-test). */
    volatile String lastTestWorld;

    // ------------------------------------------------------------------ what the player looks at

    /** Last answer of the game (key=value), for the self-test. */
    volatile java.util.Map<String, String> lastLook = java.util.Map.of();

    void inspectAsync() { Thread.ofPlatform().daemon().name("sf-inspect").start(() -> { if (inspect()) SwingUtilities.invokeLater(this::showLook); }); }

    /** Ask the agent what the crosshair is on (block or entity, classes, NBT). Blocking. */
    public boolean inspect() {
        long pid = embedder.gamePid();
        if (pid <= 0) { log("inspect: no game"); return false; }
        try {
            Path f = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "look-" + pid + ".txt");
            Files.deleteIfExists(f);
            Hotswapper.loadAgent(pid, agentJar, "codingFps=" + Settings.codingFps() + ",look=" + f);
            for (int i = 0; i < 50 && !Files.exists(f); i++) Thread.sleep(100);
            if (!Files.exists(f)) { log("inspect: no answer from the game"); return false; }
            var m = new java.util.LinkedHashMap<String, String>();
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) { int eq = l.indexOf('='); if (eq > 0) m.put(l.substring(0, eq), l.substring(eq + 1)); }
            lastLook = m;
            log("looking at: " + m.getOrDefault("state", m.getOrDefault("entity", m.getOrDefault("kind", "?"))));
            return true;
        } catch (Exception e) { log("inspect failed: " + e); return false; }
    }

    private void showLook() {
        var m = lastLook;
        var text = new StringBuilder();
        for (var e : m.entrySet()) {
            String v = e.getValue();
            text.append(e.getKey()).append(": ").append(v.length() > 400 ? v.substring(0, 400) + "…" : v).append('\n');
        }
        var classes = m.entrySet().stream().filter(e -> e.getKey().endsWith("Class")).map(java.util.Map.Entry::getValue).distinct().toArray(String[]::new);
        var area = new JTextArea(text.toString(), Math.min(14, m.size() + 2), 60);
        area.setEditable(false); area.setLineWrap(true);
        Object[] options = java.util.Arrays.copyOf(classes, classes.length + 1);
        options[classes.length] = "Close";
        int pick = JOptionPane.showOptionDialog(this, new JScrollPane(area), "Looking at", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, "Close");
        if (pick >= 0 && pick < classes.length) openClass(classes[pick]);
    }

    /** Open a class by name: the project's own sources, or the game's (decompiled/attached) in the dev environment. */
    void openClass(String fqn) {
        if (project == null) return;
        com.intellij.openapi.application.ReadAction.nonBlocking(() -> com.intellij.psi.JavaPsiFacade.getInstance(project)
                        .findClass(fqn.replace('$', '.'), com.intellij.psi.search.GlobalSearchScope.allScope(project)))
                .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState(), c -> {
                    if (c != null) c.navigate(true); else log("class not found in the project: " + fqn);
                })
                .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
    }

    void testWorldAsync(boolean temporary) { Thread.ofPlatform().daemon().name("sf-testworld").start(() -> testWorld(temporary)); }

    /** Write a superflat creative test world and go into it (restarting the game if one runs). Blocking. */
    void testWorld(boolean temporary) {
        var t = selectedTarget();
        String mc = t.profile().minecraftVersion();
        if (!TestWorld.supported(mc)) { log("test world: needs Minecraft 1.20.5 or newer (this is " + mc + ")"); return; }
        try {
            Path saves = SharedInstance.runDir(t).resolve("saves");
            Files.createDirectories(saves);
            String base = temporary ? TempWorlds.PREFIX + "Test" : "SulfurFrame Test", name = base;
            for (int i = 2; Files.exists(saves.resolve(name)); i++) name = base + " " + i;
            Path dir = TestWorld.create(saves, name, mc);
            if (temporary) TempWorlds.register(dir);
            lastTestWorld = name;
            log((temporary ? "temporary " : "") + "test world '" + name + "' created (superflat, creative, cheats, no day/weather cycle"
                    + (temporary ? "; deleted when the game exits" : "") + ")");
            if (embedder.attached()) restart(name, null);
            else { final String n = name; SwingUtilities.invokeLater(() -> startRun(t, List.of("--quickPlaySingleplayer", n), debug.isSelected())); }
        } catch (Exception e) {
            log("test world failed: " + e);
        }
    }

    /** --quickPlaySingleplayer exists since 1.20. */
    static boolean quickPlay(String mc) {
        if (mc == null) return false;
        String[] v = mc.split("\\.");
        try {
            int a = Integer.parseInt(v[0]), b = v.length > 1 ? Integer.parseInt(v[1].replaceAll("\\D.*", "")) : 0;
            return a > 1 || b >= 20;
        } catch (NumberFormatException e) { return false; }
    }

    /** Something changed that needs a restart: say so, with a button that does it (at most every 10 s). */
    void offerRestart(String why) {
        log("restart needed: " + why);
        if (System.currentTimeMillis() - lastOffer < 10_000) return;
        lastOffer = System.currentTimeMillis();
        SwingUtilities.invokeLater(() -> {
            try {
                var n = com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("SulfurFrame")
                        .createNotification("Minecraft needs a restart", why, com.intellij.notification.NotificationType.INFORMATION);
                n.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Restart into the same world", this::restartAsync));
                n.notify(null);
            } catch (Throwable ignored) { }
        });
    }

    /** Files that only take effect when the game starts. */
    static boolean needsRestart(Path f) {
        String n = f.getFileName().toString();
        return n.equals("fabric.mod.json") || n.equals("quilt.mod.json") || n.endsWith("mods.toml") || n.endsWith(".accesswidener")
                || n.equals("accesstransformer.cfg") || n.endsWith(".mixins.json") || n.equals("mixins.json")
                || n.startsWith("build.gradle") || n.startsWith("settings.gradle") || n.equals("gradle.properties") || n.equals("libs.versions.toml");
    }

    // ------------------------------------------------------------------ dev mods

    /** Dev mods are edited on the settings page (Settings | Tools | SulfurFrame). */
    void editDevMods() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, SulfurConfigurable.class);
    }

    // ------------------------------------------------------------------ run configurations

    /** Debug mode of the current / last launch: a restart keeps it. */
    private volatile boolean currentDebug;
    private final java.util.Map<String, String> quitWorlds = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> quitTimes = new java.util.concurrent.ConcurrentHashMap<>();

    Workspace.Target targetByKey(String key) {
        for (int i = 0; i < targets.getItemCount(); i++) if (key(targets.getItemAt(i)).equals(key)) return targets.getItemAt(i);
        return null;
    }

    /** World the game of this client was in when it was last stopped from the IDE (within 30 min), else "". */
    String lastQuitWorld(Workspace.Target t) {
        Long at = quitTimes.get(key(t));
        return at != null && System.currentTimeMillis() - at < 30 * 60_000 ? quitWorlds.getOrDefault(key(t), "") : "";
    }

    /** The run configuration of a client, created if it has none yet. EDT. */
    private com.intellij.execution.RunnerAndConfigurationSettings configFor(Workspace.Target t) {
        var rm = com.intellij.execution.RunManager.getInstance(project);
        for (var s : rm.getConfigurationSettingsList(SulfurRunConfigurationType.get()))
            if (s.getConfiguration() instanceof SulfurRunConfiguration c && c.target.equals(key(t))) return s;
        var s = rm.createConfiguration(SulfurRunConfiguration.nameFor(t), SulfurRunConfigurationType.get().factory());
        ((SulfurRunConfiguration) s.getConfiguration()).target = key(t);
        rm.addConfiguration(s);
        return s;
    }

    /** Every detected client gets its run configuration; the remembered one is selected if nothing of ours is. */
    private void ensureConfigs(List<Workspace.Target> found) {
        if (project == null || found.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) return;
            var rm = com.intellij.execution.RunManager.getInstance(project);
            com.intellij.execution.RunnerAndConfigurationSettings remembered = null;
            for (var t : found) { var s = configFor(t); if (key(t).equals(savedTarget.get())) remembered = s; }            var sel = rm.getSelectedConfiguration();
            if (sel == null || !(sel.getConfiguration() instanceof SulfurRunConfiguration)) rm.setSelectedConfiguration(remembered != null ? remembered : configFor(found.get(0)));
        });
    }

    /** Start a client through its run configuration (Run window, Stop, Rerun); without a project directly. */
    void startRun(Workspace.Target t, List<String> gameArgs, boolean debugRun) {
        if (project == null) { launch(t, gameArgs, debugRun, null); return; }
        try {
            var s = configFor(t);
            var executor = debugRun ? com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance()
                    : com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance();
            var env = com.intellij.execution.runners.ExecutionEnvironmentBuilder.create(executor, s).build();
            env.putUserData(SulfurRunState.GAME_ARGS, gameArgs);
            com.intellij.execution.ProgramRunnerUtil.executeConfiguration(env, false, true);
        } catch (Exception e) {
            log("could not start the run configuration: " + e.getMessage());
        }
    }

    /** Called by a run configuration's state. False if a game of ours is already running or starting. */
    boolean launchSession(Workspace.Target t, List<String> gameArgs, boolean debugRun, SulfurRunState.Session session) {
        if (launcher.running() || launching.get()) return false;
        if (!t.hasWrapper()) log("note: " + t.where() + " has no Gradle wrapper of its own; using the nearest parent's");
        saveTarget.accept(key(t));
        selectInList(t);
        launch(t, gameArgs, debugRun, session);
        return true;
    }

    /** Stop from the Run window: save and quit (remembering the world for Rerun), stop Gradle if the game is gone. */
    void stopSession(SulfurRunState.Session session) {
        var t = selectedTarget();
        Thread.ofPlatform().daemon().name("sf-stop").start(() -> {
            try {
                long pid = embedder.gamePid();
                var game = pid > 0 ? ProcessHandle.of(pid).orElse(null) : null;
                if (game != null && game.isAlive()) {
                    Path wf = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "world-" + pid + "-stop.txt");
                    Files.deleteIfExists(wf);
                    Hotswapper.loadAgent(pid, agentJar, "codingFps=" + Settings.codingFps() + ",world=" + wf + ",quit");
                    for (int i = 0; i < 50 && !Files.exists(wf); i++) Thread.sleep(100);
                    String world = Files.exists(wf) ? Files.readString(wf, StandardCharsets.UTF_8).strip() : "";
                    if (t != null) { quitWorlds.put(key(t), world); quitTimes.put(key(t), System.currentTimeMillis()); }
                    log("stop: saving and quitting" + (world.isEmpty() ? "" : " (world '" + world + "')"));
                    try { game.onExit().get(90, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (java.util.concurrent.TimeoutException e) { log("stop: the game did not exit in 90 s, stopping it"); game.destroyForcibly(); }
                }
                for (int i = 0; i < 100 && launcher.running(); i++) Thread.sleep(100);
                launcher.stopGradle();
                for (int i = 0; i < 50 && launcher.running(); i++) Thread.sleep(100);
            } catch (Exception e) {
                log("stop failed: " + e);
                launcher.stopGradle();
            } finally {
                if (!launcher.running()) session.ended(launcher.exitCode());
            }
        });
    }

    private void selectInList(Workspace.Target t) {
        SwingUtilities.invokeLater(() -> { for (int i = 0; i < targets.getItemCount(); i++) if (targets.getItemAt(i) == t) { targets.setSelectedIndex(i); break; } });
    }

    // ------------------------------------------------------------------ stats (status bar)

    private volatile Path statsFile;

    /** Ask the game's agent to write its numbers (fps, tick time, memory) every second, for the status bar. */
    public void startStats(long pid) {
        Thread.ofPlatform().daemon().name("sf-stats").start(() -> {
            try {
                Path f = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe", "stats-" + pid + ".txt");
                Files.deleteIfExists(f);
                Hotswapper.loadAgent(pid, agentJar, "codingFps=" + Settings.codingFps() + ",stats=" + f);
                statsFile = f;
            } catch (Exception e) {
                log("status bar numbers unavailable: " + e.getMessage());
            }
        });
    }

    /** The game's latest numbers (empty when there is no fresh report). */
    public java.util.Map<String, String> stats() {
        Path f = statsFile;
        var m = new java.util.LinkedHashMap<String, String>();
        try {
            if (f == null || !Files.exists(f) || System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() > 3000) return m;
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) { int eq = l.indexOf('='); if (eq > 0) m.put(l.substring(0, eq), l.substring(eq + 1)); }
        } catch (Exception ignored) { }
        return m;
    }

    // ------------------------------------------------------------------ datagen

    private final java.util.concurrent.atomic.AtomicBoolean datagenBusy = new java.util.concurrent.atomic.AtomicBoolean();
    /** Generated folders are written by datagen, not saved by the user: the save listener ignores them for a moment. */
    private volatile List<Path> datagenDirs = List.of();
    private volatile long datagenQuietUntil;
    volatile Datagen.Result lastDatagen;

    void datagenAsync() {
        var t = selectedTarget();
        if (t == null) { log("datagen: pick a project first"); return; }
        if (!datagenBusy.compareAndSet(false, true)) { log("datagen is already running"); return; }
        Thread.ofPlatform().daemon().name("sf-datagen").start(() -> {
            try {
                var r = Datagen.run(t, this::log);
                lastDatagen = r;
                if (!r.ok()) { log("datagen failed (" + r.ms() / 1000 + " s)"); return; }
                log("datagen: " + r.changed().size() + " file(s) changed (" + r.added() + " new, " + r.removed() + " removed) in " + r.ms() / 1000 + " s");
                if (r.changed().isEmpty()) return;
                datagenDirs = r.outputs();
                datagenQuietUntil = System.currentTimeMillis() + 15_000;
                try {   // show the new files in the project view and editors
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshNioFiles(r.outputs(), true, true, null);
                } catch (Throwable ignored) { }
                if (!embedder.attached()) { log("datagen: no game running; the changes apply when it starts"); return; }
                if (!hotswapper.reloadResources(t, embedder.gamePid(), agentJar, r.changed()))
                    log("datagen: the generated folder is not a resource folder of this project (sourceSets.main.resources.srcDir); restart to apply");
            } finally {
                datagenBusy.set(false);
            }
        });
    }

    private boolean writtenByDatagen(Path f) {
        if (System.currentTimeMillis() > datagenQuietUntil) return false;
        Path abs = f.toAbsolutePath().normalize();
        return datagenDirs.stream().anyMatch(abs::startsWith);
    }

    /** Source files were saved: hotswap if they belong to the selected target and a game is attached. */
    void onSourcesSaved(java.util.List<Path> files) {
        files = files.stream().filter(f -> !writtenByDatagen(f)).toList();
        if (files.isEmpty()) return;
        var t = selectedTarget();
        if (!swapOnSave.isSelected() || t == null || !embedder.attached()) return;
        Path dir = t.dir().toAbsolutePath().normalize();
        var all = files.stream().filter(f -> f.toAbsolutePath().normalize().startsWith(t.buildRoot().toAbsolutePath().normalize())).toList();
        // a mixins.json with only added mixins goes into the running game (Hotswapper.addMixins); anything else waits
        var mixinConfigs = all.stream().filter(f -> f.getFileName().toString().endsWith("mixins.json")).toList();
        // an access widener with only added entries goes into the running game too (Hotswapper.widenLive)
        var wideners = all.stream().filter(f -> Hotswapper.isWidener(f.getFileName().toString())).toList();
        var startup = all.stream().filter(f -> needsRestart(f) && !mixinConfigs.contains(f) && !wideners.contains(f)).toList();
        if (!startup.isEmpty())
            offerRestart(String.join(", ", startup.stream().map(f -> f.getFileName().toString()).distinct().toList()) + " changed (read only when the game starts)");
        if (!mixinConfigs.isEmpty()) {
            long pid = embedder.gamePid();
            work("sf-mixins", () -> {
                for (Path f : mixinConfigs) {
                    String why = hotswapper.addMixins(t, pid, agentJar, f);
                    if (why != null) offerRestart(why);
                }
            });
        }
        // code from any module of the build (a run config like runAllModsClient runs several; the hotswap compiles and
        // compares every source set on its class path); resources only from the target's own module
        var code = all.stream().filter(f -> !needsRestart(f) && (f.toString().endsWith(".java") || f.toString().endsWith(".kt"))).toList();
        var res = files.stream().filter(f -> f.toAbsolutePath().normalize().startsWith(dir) && !needsRestart(f) && !code.contains(f)
                && f.toString().replace('\\', '/').contains("/resources/")).toList();
        if (!wideners.isEmpty()) {   // first, then the code that may use what it widens
            long pid = embedder.gamePid();
            work("sf-widen", () -> {
                for (Path f : wideners) {
                    String why = hotswapper.widenLive(t, pid, agentJar, f);
                    if (why != null) offerRestart(why);
                }
                if (!code.isEmpty()) hotswapAsync();
            });
        } else if (!code.isEmpty()) hotswapAsync();
        if (!res.isEmpty()) {
            long pid = embedder.gamePid();
            work("sf-resources", () -> hotswapper.reloadResources(t, pid, agentJar, res));
        }
    }

    private final java.util.ArrayDeque<String> journal = new java.util.ArrayDeque<>();
    private long journalSeq;
    /** Swaps, reloads and restarts running now, and how many have started so far (agent bridge: sf wait). */
    private final java.util.concurrent.atomic.AtomicInteger busy = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong activity = new java.util.concurrent.atomic.AtomicLong();
    private volatile Bridge bridge;

    public long journalSeq() { synchronized (journal) { return journalSeq; } }

    /** Log lines numbered after {@code seq} (as many as are still kept). */
    public List<String> journalSince(long seq) {
        synchronized (journal) {
            int skip = (int) Math.max(0, journal.size() - (journalSeq - seq));
            return journal.stream().skip(skip).toList();
        }
    }

    public int busy() { return busy.get(); }
    public long activity() { return activity.get(); }
    private volatile long lastWorkStartSeq, lastWorkEndMillis;
    public long lastWorkStartSeq() { return lastWorkStartSeq; }
    public long lastWorkEndMillis() { return lastWorkEndMillis; }
    public long gamePid() { return embedder.gamePid(); }
    public boolean gameAttached() { return embedder.attached(); }
    public Hotswapper hotswapper() { return hotswapper; }
    public java.util.Map<String, String> lastLook() { return lastLook; }
    @Override public String runTests(List<String> names) throws Exception { return AutoTest.runOnRunningGame(this, names); }

    /** Runs {@code r} on a daemon thread, counted as work (sf wait waits for it). */
    private void work(String name, Runnable r) {
        busy.incrementAndGet();
        activity.incrementAndGet();
        lastWorkStartSeq = journalSeq();
        try {
            Thread.ofPlatform().daemon().name(name).start(() -> { try { r.run(); } finally { lastWorkEndMillis = System.currentTimeMillis(); busy.decrementAndGet(); } });
        } catch (RuntimeException e) { busy.decrementAndGet(); throw e; }
    }

    public void log(String s) {
        // a crash soon after init code ran again: offer the world from before it
        Path init = hotswapper == null ? null : hotswapper.lastInitSnapshot;
        if (s.startsWith("game crashed:") && init != null && System.currentTimeMillis() - hotswapper.lastInitSnapshotAt < 120_000 && init != lastRollbackOffer)
            Thread.ofPlatform().daemon().start(() -> offerInitRollback(s + " (shortly after init code ran again)", init));
        String line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + s;
        System.out.println("[SulfurFrame] " + line);
        synchronized (journal) {   // for the agent bridge (sf status / wait): the newest 500 lines, numbered
            journal.addLast(line);
            if (journal.size() > 500) journal.removeFirst();
            journalSeq++;
        }
        UsageLog.append((AutoTest.enabled() ? "autotest:" : "") + (project == null ? "-" : project.getName()), s);
        SwingUtilities.invokeLater(() -> { log.append(line + "\n"); log.setCaretPosition(log.getDocument().getLength()); });
    }

    /**
     * The javaagent ships inside the plugin. Java 8 games cannot load an agent from a path with non-ASCII characters,
     * so extract it to an ASCII-only directory.
     */
    private Path extractAgent() {
        try (InputStream in = SulfurPanel.class.getResourceAsStream("/sf-agent.jar")) {
            if (in == null) { log("sf-agent.jar not bundled; no FPS cap / SDL focus fix"); return null; }
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe");
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(dir.toString()))
                dir = Path.of(System.getenv().getOrDefault("PUBLIC", "C:\\Users\\Public"), "sulfurframe");
            Files.createDirectories(dir);
            // Named by content: a game still running (from another IDE, or an older plugin) keeps its copy locked on
            // Windows, and overwriting it failed, leaving this IDE without the agent and so without the "which game is
            // ours" marker (it then embedded another Minecraft). An identical copy is reused as is.
            byte[] bytes = in.readAllBytes();
            var crc = new java.util.zip.CRC32();
            crc.update(bytes);
            Path jar = dir.resolve(String.format("sf-agent-%08x.jar", crc.getValue()));
            if (!Files.exists(jar) || Files.size(jar) != bytes.length) {
                Path tmp = dir.resolve(jar.getFileName() + "." + ProcessHandle.current().pid() + ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            try (var old = Files.newDirectoryStream(dir, "sf-agent*.jar")) {   // older builds: gone unless a game holds them
                for (Path o : old) if (!o.equals(jar)) try { Files.delete(o); } catch (IOException ignored) { }
            }
            return jar;
        } catch (Exception e) {
            log("could not extract the agent: " + e);
            return null;
        }
    }

    public Path agentJar() { return agentJar; }

    /** For the self-test. */
    int targetCount() { return targets.getItemCount(); }
    void selectTarget(int i) {
        targets.setSelectedIndex(i);
        if (project != null) com.intellij.execution.RunManager.getInstance(project).setSelectedConfiguration(configFor(targets.getItemAt(i)));
    }
    Workspace.Target targetAt(int i) { return targets.getItemAt(i); }
}
