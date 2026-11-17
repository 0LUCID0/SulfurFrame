package dev.sulfurframe.idea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The panel log, also kept on disk: ~/.sulfurframe/logs/sulfurframe-<date>.log, one line per message with the date,
 * time and project. idea.log is no place for it: other plugins can fill and rotate it within minutes. Local only,
 * nothing is sent anywhere; files older than 14 days are deleted. Used to look back at real sessions (how long swaps
 * and restarts took, which changes needed a restart).
 */
final class UsageLog {
    private static final int KEEP_DAYS = 14;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sf-usage-log");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean pruned;

    static Path dir() { return SharedInstance.home().resolve("logs"); }

    static void append(String project, String message) {
        String line = LocalDateTime.now().format(TS) + " [" + project + "] " + message.replace('\n', ' ') + System.lineSeparator();
        WRITER.execute(() -> {
            try {
                Path d = dir();
                Files.createDirectories(d);
                if (!pruned) { pruned = true; prune(d); }
                Files.writeString(d.resolve("sulfurframe-" + LocalDate.now() + ".log"), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        });
    }

    private static void prune(Path d) {
        LocalDate oldest = LocalDate.now().minusDays(KEEP_DAYS);
        try (var s = Files.list(d)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (!n.startsWith("sulfurframe-") || !n.endsWith(".log")) return;
                try {
                    if (LocalDate.parse(n.substring(12, n.length() - 4)).isBefore(oldest)) Files.deleteIfExists(p);
                } catch (Exception ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private UsageLog() {}
}
