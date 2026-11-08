package dev.sulfurframe.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * For the IDE's quick restart: which singleplayer world is open (its save folder name, what --quickPlaySingleplayer
 * takes), and a normal quit (the game saves the world and exits).
 *
 * The folder name is found without mapping tables: the integrated server holds the level storage access object
 * (Mojang names: storageSource / getLevelId, Yarn: session / getDirectoryName), so the server's fields are searched
 * for one with such a method. Java 8, reflection only.
 */
final class GameSession {
    private static final String[] CLIENTS = {"net.minecraft.client.Minecraft", "net.minecraft.client.MinecraftClient"};
    private static final String[] SERVER_GETTERS = {"getSingleplayerServer", "getServer"};
    private static final String[] LEVEL_ID = {"getLevelId", "getDirectoryName"};

    /** Writes the open world's folder name to {@code file} (empty if none or unknown). */
    static void writeWorld(Instrumentation inst, String file) {
        String world = "";
        try { world = openWorld(inst); } catch (Throwable t) { SfHooks.log("session: world lookup failed: " + t); }
        try { AnswerFile.write(file, Collections.singletonList(world)); } catch (IOException ignored) { }
        SfHooks.log("session: open world '" + world + "'");
    }

    static String openWorld(Instrumentation inst) throws Exception {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            boolean client = false;
            for (String n : CLIENTS) client |= c.getName().equals(n);
            if (!client) continue;
            Object mc = c.getMethod("getInstance").invoke(null);
            if (mc == null) return "";
            Object server = null;
            for (String g : SERVER_GETTERS) {
                try { server = c.getMethod(g).invoke(mc); break; } catch (NoSuchMethodException ignored) { }
            }
            if (server == null) return "";   // no world open
            for (Class<?> k = server.getClass(); k != null; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    for (String m : LEVEL_ID) {
                        Method get;
                        try { get = f.getType().getMethod(m); } catch (NoSuchMethodException e) { continue; }
                        if (get.getReturnType() != String.class) continue;
                        f.setAccessible(true);
                        Object access = f.get(server);
                        if (access != null) return (String) get.invoke(access);
                    }
                }
            }
            return "";
        }
        return "";
    }

    /**
     * Run a command on the integrated server (e.g. "save-all flush" before a world snapshot) and write "done" (or the
     * error) to {@code doneFile} once it has run on the server thread.
     */
    static void serverCommand(Instrumentation inst, final String command, final String doneFile) {
        try {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                int which = -1;
                for (int i = 0; i < CLIENTS.length; i++) if (c.getName().equals(CLIENTS[i])) which = i;
                if (which < 0) continue;
                Object mc = c.getMethod("getInstance").invoke(null);
                final Object server = mc == null ? null : c.getMethod(SERVER_GETTERS[which]).invoke(mc);
                if (server == null) { done(doneFile, "no world open"); return; }
                final String commands = which == 0 ? "getCommands" : "getCommandManager";
                final String source = which == 0 ? "createCommandSourceStack" : "getCommandSource";
                final String run = which == 0 ? "performPrefixedCommand" : "executeWithPrefix";
                server.getClass().getMethod("execute", Runnable.class).invoke(server, new Runnable() {
                    public void run() {
                        try {
                            Object cm = server.getClass().getMethod(commands).invoke(server);
                            Object src = server.getClass().getMethod(source).invoke(server);
                            for (Method m : cm.getClass().getMethods()) {
                                if (m.getName().equals(run) && m.getParameterTypes().length == 2 && m.getParameterTypes()[1] == String.class) {
                                    m.invoke(cm, src, command);
                                    SfHooks.log("session: ran /" + command);
                                    done(doneFile, "done");
                                    return;
                                }
                            }
                            done(doneFile, "no command method");
                        } catch (Throwable t) { done(doneFile, "failed: " + t); }
                    }
                });
                return;
            }
            done(doneFile, "no named Minecraft client");
        } catch (Throwable t) {
            done(doneFile, "failed: " + t);
        }
    }

    private static void done(String file, String text) {
        if (file == null) return;
        try { AnswerFile.write(file, Collections.singletonList(text)); } catch (IOException ignored) { }
    }

    /** Ask the game to quit normally (saves the world), through the window like the lifeboat does. */
    static void quit() {
        try {
            SfHooks.log("session: quit requested by the IDE (restart)");
            Lifeboat.requestQuit();
        } catch (Throwable t) {
            SfHooks.log("session: quit failed: " + t);
        }
    }

    private GameSession() {}
}
