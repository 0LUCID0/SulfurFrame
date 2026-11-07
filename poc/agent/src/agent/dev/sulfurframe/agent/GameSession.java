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
        try { Files.write(Paths.get(file), Collections.singletonList(world), StandardCharsets.UTF_8); } catch (IOException ignored) { }
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
