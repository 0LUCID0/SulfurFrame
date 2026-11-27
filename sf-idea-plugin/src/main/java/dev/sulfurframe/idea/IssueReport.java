package dev.sulfurframe.idea;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A saved `sf feedback` report as a GitHub issue, sent only by the person: SulfurFrame opens the repository's new-issue
 * page in the browser with the title and body filled in, and nothing is posted until they press Submit there (and can
 * edit it first). So an agent can never post on its own, from the IDE or the terminal, and no token is needed.
 *
 * Before that the text is cleaned: the home folder, the user name, the project's name (also inside package names and
 * mod ids) and world names are replaced, and the body is cut to what fits in the page's address (the game log first).
 * A report that went out gets a "&lt;file&gt;.issue" marker next to it. No IntelliJ classes: the terminal host uses it.
 */
final class IssueReport {
    private IssueReport() {}

    static final String NEW_ISSUE = "https://github.com/0LUCID0/SulfurFrame/issues/new";
    /** Browsers and GitHub take addresses well past this; above ~8 KB GitHub answers 414. */
    private static final int MAX_URL = 7600;

    record Issue(String title, String body, Path file) {
        String url() { return NEW_ISSUE + "?title=" + enc(title) + "&body=" + enc(body); }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    /** Reports in the inbox not sent as an issue yet, newest first. */
    static List<Path> unsent(Path inbox) {
        var out = new ArrayList<Path>();
        try (var s = Files.list(inbox)) {
            s.filter(f -> f.getFileName().toString().endsWith(".md")).filter(f -> !Files.exists(marker(f))).forEach(out::add);
        } catch (IOException ignored) { }
        out.sort(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed());
        return out;
    }

    static Path marker(Path report) { return report.resolveSibling(report.getFileName() + ".issue"); }

    static void markSent(Path report) {
        try { Files.writeString(marker(report), java.time.LocalDateTime.now() + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) { }
    }

    /** The project's name from the report's file name ("20261127-115032-mymod.md"), null if there is none. */
    static String projectOf(Path report) {
        var m = Pattern.compile("^\\d{8}-\\d{6}-(.+)\\.md$").matcher(report.getFileName().toString());
        return m.matches() && !m.group(1).equals("unknown") ? m.group(1) : null;
    }

    /** Names and paths that say who and what the report is about, replaced. */
    static String redact(String text, String project) {
        String s = text;
        var worlds = new LinkedHashSet<String>();
        for (Pattern p : new Pattern[]{Pattern.compile("world '([^'\\r\\n]+)'"), Pattern.compile("--quickPlaySingleplayer ([^\\r\\n]+)"),
                Pattern.compile("snapshots[\\\\/][^\\\\/\\s]+[\\\\/]([^\\\\/\\s]+)")}) {
            Matcher m = p.matcher(s);
            while (m.find()) if (!m.group(1).isBlank() && !m.group(1).startsWith("<")) worlds.add(m.group(1).strip());
        }
        String home = System.getProperty("user.home", "");
        if (home.length() > 3) {
            s = replaceIgnoreCase(s, home, "<home>");
            s = replaceIgnoreCase(s, home.replace('\\', '/'), "<home>");
        }
        String user = System.getProperty("user.name", "");
        for (String w : worlds) s = s.replace("'" + w + "'", "'<world>'").replace("--quickPlaySingleplayer " + w, "--quickPlaySingleplayer <world>")
                .replace(w, "<world>");
        if (project != null && project.length() >= 3) s = replaceIgnoreCase(s, project, "<project>");
        if (user.length() >= 3) s = s.replaceAll("(?i)(?<![A-Za-z0-9])" + Pattern.quote(user) + "(?![A-Za-z0-9])", "<user>");
        // folders under the home folder say how someone keeps their files: only the last two names stay
        // (folders in between may have spaces, "My Stuff"; the last name ends at a space)
        s = Pattern.compile("<home>((?:[\\\\/][^\\\\/'\",;)\\]\\r\\n]+?(?=[\\\\/]))*[\\\\/][^\\\\/\\s'\",;)\\]]+)").matcher(s).replaceAll(m -> {
            String[] parts = m.group(1).split("[\\\\/]");
            var keep = new ArrayList<String>();
            for (String p : parts) if (!p.isEmpty()) keep.add(p);
            if (keep.size() <= 2) return Matcher.quoteReplacement(m.group());
            return Matcher.quoteReplacement("<home>/.../" + keep.get(keep.size() - 2) + "/" + keep.get(keep.size() - 1));
        });
        return s;
    }

    private static String replaceIgnoreCase(String s, String what, String with) {
        return Pattern.compile(Pattern.quote(what), Pattern.CASE_INSENSITIVE).matcher(s).replaceAll(Matcher.quoteReplacement(with));
    }

    /** The issue for a saved report: cleaned, and cut so the page's address stays short enough. */
    static Issue build(Path report) throws IOException {
        String md = Files.readString(report, StandardCharsets.UTF_8);
        String clean = redact(md, projectOf(report));
        String reported = section(clean, "What the agent reported");
        String state = section(clean, "State");
        String log = section(clean, "Game log (last 60 lines)");
        String first = reported.strip().lines().findFirst().orElse("feedback").strip();
        String title = "sf feedback: " + (first.length() > 80 ? first.substring(0, 77) + "..." : first);
        String foot = "\n\n---\nSent with `sf feedback send` (home folder, user, project and world names replaced). The full report stays in "
                + "`~/.sulfurframe/feedback/inbox/" + redact(report.getFileName().toString(), projectOf(report)) + "` on the sender's computer.";
        // what has to go first (most useful kept longest): the game log, then the state's older lines, then the report
        var tries = new ArrayList<int[]>();   // {game log lines, state lines}
        for (int l : new int[]{40, 30, 20, 10, 5, 0}) tries.add(new int[]{l, 60});
        for (int s = 48; s >= 8; s -= 8) tries.add(new int[]{0, s});
        for (int[] t : tries) {
            var body = new StringBuilder("## What the agent reported\n\n").append(reported.strip()).append("\n\n");
            if (!state.isBlank()) body.append("## State\n\n").append(fence(tail(state, t[1]))).append("\n");
            if (t[0] > 0 && !log.isBlank()) body.append("## Game log (last ").append(t[0]).append(" lines)\n\n").append(fence(tail(log, t[0]))).append("\n");
            body.append(foot);
            var i = new Issue(title, body.toString(), report);
            if (i.url().length() <= MAX_URL) return i;
        }
        // still too long: only the report, cut
        String r = reported.strip();
        while (r.length() > 200) {
            r = r.substring(0, r.length() * 3 / 4);
            var i = new Issue(title, "## What the agent reported\n\n" + r + " [cut]\n" + foot, report);
            if (i.url().length() <= MAX_URL) return i;
        }
        return new Issue(title, "## What the agent reported\n\n" + r + foot, report);
    }

    /** A "## name" section of the report, without its heading or code fences. */
    static String section(String md, String name) {
        int at = md.indexOf("## " + name);
        if (at < 0) return "";
        int start = md.indexOf('\n', at);
        if (start < 0) return "";
        int end = md.indexOf("\n## ", start);
        String s = md.substring(start + 1, end < 0 ? md.length() : end);
        return s.replace("```", "").strip();
    }

    private static String tail(String s, int lines) {
        var l = s.lines().toList();
        return String.join("\n", l.subList(Math.max(0, l.size() - lines), l.size()));
    }

    private static String fence(String s) { return "```\n" + s + "\n```\n"; }

    /** Open the page in the default browser. False if no way was found (the caller prints the address). */
    static boolean open(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                return true;
            }
        } catch (Throwable ignored) { }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd = os.startsWith("windows") ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : os.contains("mac") ? List.of("open", url) : List.of("xdg-open", url);
        try { new ProcessBuilder(cmd).redirectErrorStream(true).start(); return true; } catch (IOException e) { return false; }
    }

    /**
     * `sf feedback send [file]` in a terminal: shows what would go out and, after the person says yes, opens the page.
     * Asked only at a terminal a person types into: run by an agent (no terminal), it explains and opens nothing.
     */
    static int main(List<String> args) throws IOException {
        Path inbox = Bridge.FEEDBACK;
        Path report;
        if (!args.isEmpty() && !args.get(0).isBlank()) {
            report = Path.of(args.get(0));
            if (!report.isAbsolute() && !Files.exists(report)) report = inbox.resolve(args.get(0));
            if (!Files.isRegularFile(report)) { System.err.println("sf feedback send: no report " + args.get(0)); return 2; }
        } else {
            var all = unsent(inbox);
            if (all.isEmpty()) { System.out.println("sf feedback send: no report waiting in " + inbox); return 0; }
            report = all.get(0);
            if (all.size() > 1) {
                System.out.println("Reports not sent yet (newest first; this sends the first, name another to send it):");
                for (Path f : all) System.out.println("  " + f.getFileName());
                System.out.println();
            }
        }
        Issue i = build(report);
        System.out.println("Title: " + i.title() + "\n");
        System.out.println(i.body());
        System.out.println();
        if (!personAtTerminal()) {
            System.out.println("Not opened: sending a report is the user's decision. Ask them to run `sf feedback send"
                    + (args.isEmpty() ? "" : " " + report.getFileName()) + "` in their own terminal.");
            return 1;
        }
        System.out.print("Open this as a new issue on github.com/0LUCID0/SulfurFrame in your browser? Nothing is posted until you press Submit there. [y/N] ");
        System.out.flush();
        String answer = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (answer == null || !answer.strip().toLowerCase(Locale.ROOT).startsWith("y")) { System.out.println("Not sent."); return 1; }
        if (!open(i.url())) { System.out.println("No browser could be opened; the page:\n" + i.url()); }
        markSent(report);
        System.out.println("Opened. Check it and press Submit to post it (" + report.getFileName() + " is marked as sent).");
        return 0;
    }

    /** A person typing into this terminal: Console.isTerminal (Java 22+), else a console at all. */
    static boolean personAtTerminal() {
        var c = System.console();
        if (c == null) return false;
        try { return (Boolean) java.io.Console.class.getMethod("isTerminal").invoke(c); } catch (Throwable t) { return true; }
    }
}
