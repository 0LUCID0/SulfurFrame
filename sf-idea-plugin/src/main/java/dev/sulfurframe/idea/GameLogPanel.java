package dev.sulfurframe.idea;

import com.intellij.execution.filters.ExceptionFilters;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import dev.sulfurframe.idea.profile.Workspace;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Game log, problems and crashes, from the game's own run/logs/latest.log ({@link GameLog}).
 *   Game log  IntelliJ console: stack trace lines are links to the sources (ExceptionFilters), warnings and errors
 *             coloured, optional "warnings and errors only"
 *   Problems  missing models, blockstates and textures reported by the game, plus translation keys missing from the
 *             mod's lang files (the game does not log those); double-click opens the file, "Add missing translations"
 *             fills en_us.json
 *   Crash     "Game crashed! Crash report saved to: ..." -> a notification with the cause, the first line of the
 *             project's own code in the stack (go there), the report, and a restart
 */
final class GameLogPanel {
    /** The two tabs, shown next to the SulfurFrame log in the tool window (one tab row, same layout as that log). */
    final JComponent logTab, problemsTab;
    private final Project project;
    private final Supplier<Workspace.Target> target;
    private final Runnable restart;
    private final Consumer<String> log;
    private final ConsoleView console;
    private final JCheckBox problemsOnly = new JCheckBox("Warnings and errors only");
    private final DefaultListModel<Problem> problems = new DefaultListModel<>();
    private final Set<String> seen = Collections.synchronizedSet(new HashSet<>());
    final GameLog gameLog = new GameLog();

    record Problem(String kind, String id, Path file, String detail) {
        @Override public String toString() { return kind + "  " + id + (detail.isEmpty() ? "" : "   (" + detail + ")"); }
    }

    /** Last crash seen (self-test). */
    volatile String lastCrash;

    GameLogPanel(Project project, Supplier<Workspace.Target> target, Runnable restart, Consumer<String> log) {
        this.project = project; this.target = target; this.restart = restart; this.log = log;
        var builder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        builder.filters(ExceptionFilters.getFilters(GlobalSearchScope.allScope(project)));   // "at a.b.C.m(C.java:12)" -> link
        console = builder.getConsole();

        var top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.add(problemsOnly);
        var clear = new JButton("Clear");
        clear.addActionListener(e -> console.clear());
        top.add(clear);
        var lt = new JPanel(new BorderLayout());
        lt.add(top, BorderLayout.NORTH);
        lt.add(console.getComponent(), BorderLayout.CENTER);
        logTab = lt;

        var list = new JList<>(problems);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && list.getSelectedValue() != null) open(list.getSelectedValue().file(), 0); }
        });
        var probTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        var scan = new JButton("Check translations");
        scan.addActionListener(e -> Thread.ofPlatform().daemon().start(this::checkTranslations));
        var add = new JButton("Add missing translations");
        add.addActionListener(e -> Thread.ofPlatform().daemon().start(this::addMissingTranslations));
        probTop.add(scan); probTop.add(add);
        var pt = new JPanel(new BorderLayout());
        pt.add(probTop, BorderLayout.NORTH);
        pt.add(new JScrollPane(list), BorderLayout.CENTER);
        problemsTab = pt;

        gameLog.addListener(this::onLine);
    }

    /** Follow the target's latest.log and watch its crash-reports folder (call when a game starts). */
    void follow(Workspace.Target t) {
        gameLog.follow(SharedInstance.runDir(t).resolve("logs/latest.log"));
        Path dir = SharedInstance.runDir(t).resolve("crash-reports");
        crashDir = dir;
        knownReports.clear();
        knownReports.addAll(reports(dir));
        if (crashWatcher == null) {
            crashWatcher = Thread.ofPlatform().daemon().name("sf-crash-watch").start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    Path d = crashDir;
                    if (d == null) continue;
                    for (Path r : reports(d)) if (knownReports.add(r)) { sleep(500); onCrash(r); }   // let the game finish writing it
                }
            });
        }
    }

    // The "Crash report saved to" line goes to stdout only (not to latest.log in a Loom dev run): watch the folder.
    private volatile Path crashDir;
    private final Set<Path> knownReports = Collections.synchronizedSet(new HashSet<>());
    private Thread crashWatcher;

    private static List<Path> reports(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) { return s.filter(p -> p.toString().endsWith(".txt")).toList(); } catch (Exception e) { return List.of(); }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }

    // ------------------------------------------------------------------ lines

    private static final Pattern LEVEL = Pattern.compile("^\\[[^\\]]*\\] \\[[^\\]]*/(WARN|ERROR|FATAL)\\]");
    private static final Pattern CRASH = Pattern.compile("Crash report saved to: (?:#@!@# )?(.+\\.txt)");
    private static final Pattern RES = Pattern.compile("([a-z0-9_.-]+):([a-z0-9_./-]+)");

    private boolean inMissingTextures;

    void onLine(String line) {
        var m = LEVEL.matcher(line);
        boolean warn = m.find();
        boolean err = warn && !m.group(1).equals("WARN");
        boolean stack = line.startsWith("\tat ") || line.startsWith("Caused by") || line.startsWith("java.") || line.matches("^\\s+at .*");
        if (!problemsOnly.isSelected() || warn || stack)
            console.print(line + "\n", err ? ConsoleViewContentType.ERROR_OUTPUT : warn ? ConsoleViewContentType.LOG_WARNING_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);

        // problems the game reports
        if (line.contains("missing model for variant") || line.contains("Missing model for variant")) {
            var r = lastResource(line, "blockstates/");
            if (r != null) problem("blockstate/model", r, asset(r, "blockstates", ".json", "blockstates/"), "a variant has no model");
        } else if (line.contains("Unable to load model")) {
            var r = firstResource(line);
            if (r != null) problem("model", r, asset(r, "models", ".json", ""), "model file missing");
        } else if (line.contains("Missing textures in model")) {
            inMissingTextures = true;
            var r = firstResource(line.substring(line.indexOf("model")));
            if (r != null) problem("textures", r, asset(r, "models", ".json", ""), "model refers to textures that do not exist");
        } else if (inMissingTextures && line.startsWith("    ") && line.contains(".png:")) {
            var r = firstResource(line.substring(line.indexOf(".png:") + 5));
            if (r != null) problem("texture", r, asset(r, "textures", ".png", ""), "texture missing");
        } else {
            inMissingTextures = false;
        }

        var c = CRASH.matcher(line);   // when the log does carry it (some launchers send stdout there)
        if (c.find()) { Path r = Path.of(c.group(1).strip()); if (knownReports.add(r)) onCrash(r); }
    }

    private static String firstResource(String s) {
        var m = RES.matcher(s.replace("'", " "));
        while (m.find()) if (!m.group(1).equals("java.io") && !m.group(1).matches("\\d+")) return m.group(1) + ":" + m.group(2);
        return null;
    }

    private static String lastResource(String s, String strip) {
        var m = RES.matcher(s.replace("'", " "));
        String r = null;
        while (m.find()) { String id = m.group(1) + ":" + m.group(2); if (id.contains(strip)) r = id.replace(strip, "").replace(".json", ""); }
        return r;
    }

    /** assets/<ns>/<dir>/<path><ext> in the target's resources (may not exist: that is the problem). */
    private Path asset(String id, String dir, String ext, String strip) {
        var t = target.get();
        if (t == null) return null;
        String ns = id.substring(0, id.indexOf(':')), path = id.substring(id.indexOf(':') + 1).replace(strip, "").replaceAll("#.*", "");
        if (path.startsWith(dir + "/")) path = path.substring(dir.length() + 1);
        if (path.endsWith(ext)) path = path.substring(0, path.length() - ext.length());
        return t.dir().resolve("src/main/resources/assets/" + ns + "/" + dir + "/" + path + ext);
    }

    private void problem(String kind, String id, Path file, String detail) {
        if (!seen.add(kind + id)) return;
        SwingUtilities.invokeLater(() -> problems.addElement(new Problem(kind, id, file, detail)));
    }

    int problemCount() { return problems.size(); }
    List<Problem> problemList() { var out = new ArrayList<Problem>(); for (int i = 0; i < problems.size(); i++) out.add(problems.get(i)); return out; }

    // ------------------------------------------------------------------ translations

    /** Block and item ids of the mod that have no entry in en_us.json (blockstates -> block.*, item models -> item.*). */
    List<String[]> missingTranslations() {
        var out = new ArrayList<String[]>();
        var t = target.get();
        if (t == null) return out;
        Path assets = t.dir().resolve("src/main/resources/assets");
        if (!Files.isDirectory(assets)) return out;
        try (var nss = Files.list(assets)) {
            for (Path ns : nss.filter(Files::isDirectory).toList()) {
                String n = ns.getFileName().toString();
                if (n.equals("minecraft")) continue;
                Map<String, Object> en = readLang(ns.resolve("lang/en_us.json"));
                var blocks = names(ns.resolve("blockstates"));
                for (String b : blocks) if (!en.containsKey("block." + n + "." + b)) out.add(new String[]{n, "block." + n + "." + b, b});
                for (String i : names(ns.resolve("models/item")))
                    if (!blocks.contains(i) && !en.containsKey("item." + n + "." + i)) out.add(new String[]{n, "item." + n + "." + i, i});
            }
        } catch (Exception ignored) { }
        return out;
    }

    void checkTranslations() {
        var missing = missingTranslations();
        var t = target.get();
        for (var m : missing) problem("translation", m[1], t.dir().resolve("src/main/resources/assets/" + m[0] + "/lang/en_us.json"), "no en_us entry");
        log.accept("translations: " + missing.size() + " key(s) missing from en_us.json");
    }

    /** Add the missing keys to each namespace's en_us.json with a readable default ("ms_block" -> "Ms Block"). */
    int addMissingTranslations() {
        var missing = missingTranslations();
        var t = target.get();
        var byNs = new LinkedHashMap<String, List<String[]>>();
        for (var m : missing) byNs.computeIfAbsent(m[0], k -> new ArrayList<>()).add(m);
        int added = 0;
        for (var e : byNs.entrySet()) {
            Path f = t.dir().resolve("src/main/resources/assets/" + e.getKey() + "/lang/en_us.json");
            try {
                Map<String, Object> lang = readLang(f);
                for (var m : e.getValue()) { lang.put(m[1], humanize(m[2])); added++; }
                Files.createDirectories(f.getParent());
                Files.writeString(f, new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(lang) + "\n", StandardCharsets.UTF_8);
                var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(f);
                if (vf != null) vf.refresh(true, false);
            } catch (Exception ex) { log.accept("translations: could not write " + f + ": " + ex); }
        }
        log.accept("translations: added " + added + " key(s)");
        SwingUtilities.invokeLater(() -> { for (int i = problems.size() - 1; i >= 0; i--) if (problems.get(i).kind().equals("translation")) problems.remove(i); });
        seen.removeIf(s -> s.startsWith("translation"));
        return added;
    }

    static String humanize(String id) {
        var sb = new StringBuilder();
        for (String w : id.split("[_/]")) if (!w.isEmpty()) sb.append(sb.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readLang(Path f) {
        try {
            if (!Files.exists(f)) return new LinkedHashMap<>();
            var m = new com.google.gson.Gson().fromJson(Files.readString(f, StandardCharsets.UTF_8), LinkedHashMap.class);
            return m == null ? new LinkedHashMap<>() : (Map<String, Object>) m;
        } catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private static Set<String> names(Path dir) throws java.io.IOException {
        var out = new TreeSet<String>();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) { s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).forEach(n -> out.add(n.substring(0, n.length() - 5))); }
        return out;
    }

    // ------------------------------------------------------------------ crash

    void onCrash(Path report) {
        String text;
        try { text = Files.readString(report, StandardCharsets.UTF_8); } catch (Exception e) { text = ""; }
        String cause = "", frame = null;
        boolean afterDesc = false;
        for (String l : text.split("\\R")) {
            if (l.startsWith("Description:")) { afterDesc = true; continue; }
            if (afterDesc && cause.isEmpty() && !l.isBlank()) cause = l.strip();
            if (afterDesc && frame == null && l.strip().startsWith("at ") && ownCode(l)) frame = stripLoader(l.strip().substring(3));
            if (l.startsWith("-- ")) break;
        }
        lastCrash = cause;
        final String f = frame, c = cause;
        log.accept("game crashed: " + cause + (frame == null ? "" : "  at " + frame));
        console.print("\n===== Minecraft crashed: " + cause + "\n" + (frame == null ? "" : "\tat " + frame + "\n") + "Report: " + report + "\n", ConsoleViewContentType.ERROR_OUTPUT);
        SwingUtilities.invokeLater(() -> {
            var n = com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("SulfurFrame")
                    .createNotification("Minecraft crashed", c + (f == null ? "" : "<br>at " + f), com.intellij.notification.NotificationType.ERROR);
            if (f != null) n.addAction(com.intellij.notification.NotificationAction.createSimple("Go to code", () -> goTo(f)));
            n.addAction(com.intellij.notification.NotificationAction.createSimple("Open crash report", () -> open(report, 0)));
            n.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Restart", restart));
            n.notify(project);
        });
    }

    /** "knot/a.b.C.m(C.java:1)", "app//a.b.C.m(...)", "java.base/..." -> "a.b.C.m(C.java:1)" (class loader / module prefix off). */
    static String stripLoader(String frame) {
        int paren = frame.indexOf('('), slash = frame.lastIndexOf('/', paren < 0 ? frame.length() : paren);
        return slash < 0 ? frame : frame.substring(slash + 1);
    }

    /** A stack frame of a class compiled from this project (its class file is in build/classes). */
    private boolean ownCode(String frameLine) {
        var t = target.get();
        if (t == null) return false;
        String s = stripLoader(frameLine.strip().substring(3));
        int paren = s.indexOf('(');
        if (paren < 0) return false;
        String method = s.substring(0, paren);
        int dot = method.lastIndexOf('.');
        if (dot < 0) return false;
        String cls = method.substring(0, dot).replaceAll("\\$.*", "").replace('.', '/') + ".class";
        try (var w = Files.walk(t.dir().resolve("build/classes"), 3)) {
            for (Path root : w.filter(p -> p.getNameCount() - t.dir().resolve("build/classes").getNameCount() == 2).toList())
                if (Files.exists(root.resolve(cls))) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** "a.b.C.m(C.java:12)" -> open the source at that line. */
    void goTo(String frame) {
        var t = target.get();
        var m = Pattern.compile("^(.+)\\.[^.(]+\\(([^:]+):(\\d+)\\)").matcher(frame);
        if (t == null || !m.find()) return;
        String pkg = m.group(1).replaceAll("\\$.*", "");
        pkg = pkg.contains(".") ? pkg.substring(0, pkg.lastIndexOf('.')).replace('.', '/') : "";
        for (String dir : List.of("src/main/java", "src/main/kotlin", "src/client/java", "src/client/kotlin")) {
            Path p = t.dir().resolve(dir).resolve(pkg).resolve(m.group(2));
            if (Files.exists(p)) { open(p, Integer.parseInt(m.group(3)) - 1); return; }
        }
    }

    void open(Path file, int line) {
        if (file == null) return;
        SwingUtilities.invokeLater(() -> {
            var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
            if (vf != null) new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, Math.max(0, line), 0).navigate(true);
            else log.accept("not found (yet): " + file);
        });
    }

    void dispose() { gameLog.stop(); console.dispose(); }
}
