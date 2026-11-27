package dev.sulfurframe.idea;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A feedback report as a GitHub issue: names and paths replaced, the page's address short enough; plus the small text
 * rules of the same release (editor temporary files, java argument files).
 *   java -cp out dev.sulfurframe.idea.IssueReportSelfTest
 */
public class IssueReportSelfTest {
    public static void main(String[] a) throws Exception {
        var failures = new ArrayList<String>();
        System.setProperty("user.home", "C:\\Users\\alice");
        System.setProperty("user.name", "alice");

        String text = String.join("\n",
                "project: . :core (FABRIC 1.21.1)",
                "game: running, pid 1, world 'Alice Land'",
                "options sync: run folder C:\\Users\\alice\\My Stuff\\code\\coolmod\\run, own mod keys: coolmod-core minecraft",
                "game arguments: --quickPlaySingleplayer Alice Land",
                "not loaded: dev.coolmod.gui.Screen and C:/Users/alice/Workspace/projects/CoolMod/build/x.jar",
                "user alice saved it; malice and alice2 are other words");
        String r = IssueReport.redact(text, "coolmod");
        for (String leak : new String[]{"user alice", "\\alice", "Alice Land", "coolmod", "CoolMod", "My Stuff", "Workspace/projects"})
            if (r.contains(leak)) failures.add("redact: still contains '" + leak + "':\n" + r);
        expect(failures, "world in status", r.contains("world '<world>'"), true);
        expect(failures, "world in game arguments", r.contains("--quickPlaySingleplayer <world>"), true);
        expect(failures, "project in package names", r.contains("dev.<project>.gui.Screen"), true);
        expect(failures, "home folder kept to its last two names", r.contains("<home>/.../build/x.jar"), true);
        expect(failures, "folders with spaces too", r.contains("run folder <home>/.../<project>/run, own"), true);
        expect(failures, "user as a word only", r.contains("user <user> saved it; malice and alice2"), true);

        // a long report still fits in the page's address, game log first to go
        Path dir = Files.createTempDirectory("sf-issue");
        Path f = dir.resolve("20261127-115032-coolmod.md");
        var log = new StringBuilder();
        for (int i = 0; i < 60; i++) log.append("[12:00:").append(i).append("] [Render thread/INFO] a long line of the game log that says something about coolmod ").append("x".repeat(80)).append('\n');
        Files.writeString(f, "# SulfurFrame feedback\n\n## What the agent reported\n\nThe swap said redefined but coolmod kept the old code.\n\n"
                + "## State\n\nplugin 0.3.0\n\n```\n" + text + "\n```\n\n## Game log (last 60 lines)\n\n```\n" + log + "```\n", StandardCharsets.UTF_8);
        var issue = IssueReport.build(f);
        expect(failures, "address short enough", issue.url().length() <= 7600, true);
        expect(failures, "title from the report, redacted", issue.title(), "sf feedback: The swap said redefined but <project> kept the old code.");
        expect(failures, "report kept", issue.body().contains("## What the agent reported"), true);
        expect(failures, "game log cut", issue.body().contains("Game log (last 60 lines)"), false);
        expect(failures, "file name redacted", issue.body().contains("coolmod"), false);
        expect(failures, "project from the file name", IssueReport.projectOf(f), "coolmod");
        expect(failures, "unsent: the new report", IssueReport.unsent(dir), List.of(f));
        IssueReport.markSent(f);
        expect(failures, "unsent after markSent", IssueReport.unsent(dir), List.of());

        // editor and tool temporary files are not resources
        expect(failures, "sed -i temp", Hotswapper.isEditorTemp("sedAb12Cd"), true);
        expect(failures, "vim swap", Hotswapper.isEditorTemp(".en_us.json.swp"), true);
        expect(failures, "IntelliJ safe write", Hotswapper.isEditorTemp("en_us.json___jb_tmp___"), true);
        expect(failures, "a real file", Hotswapper.isEditorTemp("en_us.json"), false);
        expect(failures, "a name that starts with sed", Hotswapper.isEditorTemp("sediment.png"), false);

        // java argument files: quotes, escapes, whitespace
        expect(failures, "arg file tokens", QuickRelaunch.argFileTokens("-cp \"C:\\\\a b\\\\x.jar;y.jar\"\n  -Dk=v 'q r'"),
                List.of("-cp", "C:\\a b\\x.jar;y.jar", "-Dk=v", "q r"));

        if (failures.isEmpty()) System.out.println("issue report checks passed");
        else { failures.forEach(x -> System.out.println("FAIL " + x)); System.exit(1); }
    }

    private static void expect(List<String> failures, String what, Object got, Object want) {
        if (!Objects.equals(got, want)) failures.add(what + ": got " + got + ", want " + want);
    }
}
