package dev.sulfurframe.agent;

import dev.sulfurframe.profile.OptionsSync;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Auto-sync of key bindings/options with the SulfurFrame master profile (see dev.sulfurframe.profile.OptionsSync).
 * Runs in premain, before Minecraft reads options.txt, and again from a shutdown hook after it last saved it.
 * Works for any launcher: all of them let you add JVM arguments.
 *
 * Agent arguments: sync=off (disable), master=<dir> (default ~/.sulfurframe/profile), gameDir=<dir> (override).
 * Does nothing until a master profile exists (created by `OptionsSync pull --apply` or the IDE).
 */
final class ProfileSync {
    static void start(String args) {
        String sync = arg(args, "sync"), masterArg = arg(args, "master"), dirArg = arg(args, "gameDir");
        if ("off".equals(sync)) return;
        final Path master = masterArg != null ? Paths.get(masterArg) : OptionsSync.defaultMaster();
        final Path gameDir = dirArg != null ? Paths.get(dirArg) : gameDir();
        if (gameDir == null) { SfHooks.log("auto-sync: game directory not found, skipped"); return; }
        try {
            OptionsSync.atGameStart(gameDir, master);
        } catch (Throwable t) {
            SfHooks.log("auto-sync at start failed (the game starts normally): " + t);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { OptionsSync.atGameExit(gameDir, master); }
            catch (Throwable t) { System.out.println("[SF-Sync] auto-sync at exit failed: " + t); }
        }, "sf-profile-sync"));
    }

    /**
     * Minecraft's --gameDir from the JVM command line (HotSpot's sun.java.command = main class + args joined by
     * spaces, so a path with spaces runs until the next "--" option); otherwise the working directory, which is
     * what Prism, Modrinth, CurseForge and Gradle run configurations set to the game directory.
     */
    static Path gameDir() {
        String cmd = System.getProperty("sun.java.command", "");
        String[] t = cmd.split(" ");
        for (int i = 0; i < t.length - 1; i++) {
            if (!t[i].equals("--gameDir")) continue;
            StringBuilder p = new StringBuilder(t[i + 1]);
            for (int j = i + 2; j < t.length && !t[j].startsWith("--"); j++) p.append(' ').append(t[j]);
            Path d = Paths.get(p.toString());
            if (Files.isDirectory(d)) return d;
        }
        Path cwd = new File(System.getProperty("user.dir", ".")).toPath();
        return Files.isDirectory(cwd) ? cwd : null;
    }

    static String arg(String args, String key) {
        if (args == null) return null;
        for (String kv : args.split(",")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).trim().equals(key)) return kv.substring(e + 1).trim();
        }
        return null;
    }

    private ProfileSync() {}
}
