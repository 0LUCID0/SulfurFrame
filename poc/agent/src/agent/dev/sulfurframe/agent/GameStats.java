package dev.sulfurframe.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Once asked ("stats=<file>"), writes the game's numbers to that file every second, for the IDE's status line:
 * fps, integrated server tick time (ms, when a world is open), heap used/max. Mojang and Yarn names, reflection only.
 * Java 8.
 */
final class GameStats {
    private static volatile String file;
    private static Thread thread;

    static synchronized void start(final Instrumentation inst, String target) {
        file = target;
        if (thread != null) return;
        thread = new Thread(new Runnable() { public void run() { loop(inst); } }, "sulfurframe-stats");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop(Instrumentation inst) {
        Object mc = null;
        Method fps = null, server = null;
        int looks = 0;
        while (true) {
            try {
                Thread.sleep(1000);
                // the game's numbers need its class by name (a dev run, Mojang or Yarn names); an obfuscated vanilla
                // game has none, but heap and the fullscreen state are still reported. Looked for again every 10 s
                if (mc == null && looks++ % 10 == 0) {
                    for (Class<?> c : inst.getAllLoadedClasses()) {
                        if (!c.getName().equals("net.minecraft.client.Minecraft") && !c.getName().equals("net.minecraft.client.MinecraftClient")) continue;
                        mc = c.getMethod("getInstance").invoke(null);
                        fps = first(c, "getFps", "getCurrentFps");
                        server = first(c, "getSingleplayerServer", "getServer");
                    }
                }
                List<String> out = new ArrayList<String>();
                if (mc != null && fps != null) out.add("fps=" + fps.invoke(mc));
                Object s = mc == null || server == null ? null : server.invoke(mc);
                if (s != null) {
                    Method tick = first(s.getClass(), "getCurrentSmoothedTickTime", "getAverageTickTime", "getAverageTickTimeNanos");
                    if (tick != null) {
                        Object v = tick.invoke(s);
                        double ms = v instanceof Long ? ((Long) v) / 1e6 : ((Number) v).doubleValue();
                        out.add("tickMs=" + String.format(java.util.Locale.ROOT, "%.1f", ms));
                    }
                }
                Runtime rt = Runtime.getRuntime();
                out.add("heapMb=" + (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
                out.add("maxMb=" + rt.maxMemory() / (1024 * 1024));
                int fs = GlfwCursorFix.fullscreen;
                if (fs >= 0) out.add("fullscreen=" + (fs == 1));
                AnswerFile.write(file, out);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static Method first(Class<?> c, String... names) {
        for (String n : names) {
            try { Method m = c.getMethod(n); m.setAccessible(true); return m; } catch (Throwable ignored) { }
        }
        return null;
    }

    private GameStats() {}
}
