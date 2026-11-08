package dev.sulfurframe.idea;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Follows the dev client's run/logs/latest.log (the game writes it itself, whatever launched it) and hands every new
 * line to listeners. The game rewrites the file on each start; a shrinking or replaced file is read from the top again.
 */
final class GameLog {
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private volatile Path file;
    private volatile boolean running = true;

    GameLog() { Thread.ofPlatform().daemon().name("sf-game-log").start(this::follow); }

    void addListener(Consumer<String> l) { listeners.add(l); }

    /** Start following this file (from its current end, unless it is new). */
    void follow(Path latestLog) { file = latestLog; }

    void stop() { running = false; }

    private void follow() {
        Path current = null;
        long pos = 0;
        Object key = null;
        var pending = new StringBuilder();
        while (running) {
            try {
                Thread.sleep(250);
                Path f = file;
                if (f == null || !Files.exists(f)) continue;
                Object k = Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
                long size = Files.size(f);
                if (!f.equals(current)) { current = f; pos = size; key = k; continue; }   // new target: only what comes next
                if (size < pos || (k != null && !k.equals(key))) { pos = 0; key = k; pending.setLength(0); }   // restarted game
                if (size == pos) continue;
                try (var raf = new RandomAccessFile(f.toFile(), "r")) {
                    raf.seek(pos);
                    byte[] buf = new byte[(int) Math.min(size - pos, 1 << 20)];
                    raf.readFully(buf);
                    pos += buf.length;
                    pending.append(new String(buf, StandardCharsets.UTF_8));
                }
                int nl;
                while ((nl = pending.indexOf("\n")) >= 0) {
                    String line = pending.substring(0, nl).replace("\r", "");
                    pending.delete(0, nl + 1);
                    for (var l : listeners) { try { l.accept(line); } catch (Throwable ignored) { } }
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException ignored) {
            }
        }
    }
}
