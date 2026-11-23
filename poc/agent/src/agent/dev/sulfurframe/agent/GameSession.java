package dev.sulfurframe.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
     * Run a command on the integrated server (e.g. "setblock" in the self-test) and write "done" (or the
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
                            final List<String> said = new ArrayList<String>();
                            src = capturing(src, said);
                            for (Method m : cm.getClass().getMethods()) {
                                if (m.getName().equals(run) && m.getParameterTypes().length == 2 && m.getParameterTypes()[1] == String.class) {
                                    m.invoke(cm, src, command);
                                    SfHooks.log("session: ran /" + command + (said.isEmpty() ? "" : " -> " + said));
                                    done(doneFile, "done", String.join(" | ", said));
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

    private static void done(String file, String text) { done(file, text, null); }

    /** First line the outcome ("done" or why not), second line what the command said, if anything. */
    private static void done(String file, String text, String said) {
        if (file == null) return;
        try { AnswerFile.write(file, said == null || said.isEmpty() ? Collections.singletonList(text) : Arrays.asList(text, said)); } catch (IOException ignored) { }
    }

    /**
     * The command source with an output that also keeps what the command says (feedback and errors, as plain text):
     * the server's own output does not reliably reach the log. Same source otherwise (withOutput); unchanged if that
     * cannot be done.
     */
    private static Object capturing(final Object source, final List<String> said) {
        try {
            for (Method with : source.getClass().getMethods()) {
                if (!(with.getName().equals("withOutput") || with.getName().equals("withSource")) || with.getParameterTypes().length != 1) continue;
                final Class<?> outType = with.getParameterTypes()[0];
                if (!outType.isInterface()) continue;
                Object out = java.lang.reflect.Proxy.newProxyInstance(outType.getClassLoader(), new Class<?>[]{outType}, new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                        if (m.getReturnType() == boolean.class) return true;   // receive feedback, track output
                        if (args != null && args.length == 1 && args[0] != null && m.getReturnType() == void.class) {
                            Object text = args[0];
                            try { said.add(String.valueOf(text.getClass().getMethod("getString").invoke(text))); } catch (Throwable t) { said.add(String.valueOf(text)); }
                        }
                        if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (m.getName().equals("equals")) return proxy == args[0];
                        if (m.getName().equals("toString")) return "SulfurFrame command output";
                        return null;
                    }
                });
                return with.invoke(source, out);
            }
        } catch (Throwable ignored) { }
        return source;
    }

    /**
     * Leave the open singleplayer world (it saves) and open it again, without quitting the game: world data the server
     * reads only when a world opens (dynamic registries: world generation, enchantments, damage types...) is read
     * again. Writes "done" (or why not) to {@code doneFile} once the world is being opened.
     */
    static void rejoin(Instrumentation inst, final String doneFile) {
        try {
            final String world = openWorld(inst);
            if (world.isEmpty()) { done(doneFile, "no world open"); return; }
            for (Class<?> c : inst.getAllLoadedClasses()) {
                boolean isClient = false;
                for (String n : CLIENTS) isClient |= c.getName().equals(n);
                if (!isClient) continue;
                final Class<?> mcClass = c;
                final Object mc = c.getMethod("getInstance").invoke(null);
                final Runnable[] waitThenLeave = new Runnable[1];
                final Runnable leaveAndOpen = new Runnable() {
                    public void run() {
                        try {
                            if (mcClass.getMethod("getOverlay").invoke(mc) != null) {   // a reload slipped in: wait again
                                Thread again = new Thread(waitThenLeave[0], "sf-rejoin");
                                again.setDaemon(true);
                                again.start();
                                return;
                            }
                            for (String f : new String[]{"world", "level"}) {   // the client's world first, as the pause menu does
                                try {
                                    Field wf = mcClass.getField(f);
                                    Object w = wf.get(mc);
                                    if (w != null) w.getClass().getMethod("disconnect").invoke(w);
                                    break;
                                } catch (NoSuchFieldException ignored) { }
                            }
                            mcClass.getMethod("disconnect").invoke(mc);   // waits until the integrated server has saved and stopped
                            // as "Save and Quit to Title" does: the title screen, and the world opened from there on a
                            // later tick (opened in the same task, the client never joined the new server)
                            for (String title : new String[]{"net.minecraft.client.gui.screen.TitleScreen", "net.minecraft.client.gui.screens.TitleScreen"}) {
                                try {
                                    Object screen = Class.forName(title, true, mcClass.getClassLoader()).getConstructor().newInstance();
                                    for (Method m : mcClass.getMethods())
                                        if (m.getName().equals("setScreen") && m.getParameterTypes().length == 1) { m.invoke(mc, screen); break; }
                                    break;
                                } catch (ClassNotFoundException ignored) { }
                            }
                            // the world is opened once the title screen is up and no resource reload runs (a world
                            // opened during one never finished loading); a reload waiting for idle waits for us
                            final Runnable open = new Runnable() {
                                public void run() {
                                    try {
                                        final Runnable back = new Runnable() { public void run() { SfHooks.worldOpening = false; SfHooks.log("session: rejoin cancelled"); } };
                                        for (String[] n : new String[][]{{"createIntegratedServerLoader", "start"}, {"createWorldOpenFlows", "openWorld"}, {"createWorldOpenFlows", "loadLevel"}}) {
                                            Method create;
                                            try { create = mcClass.getMethod(n[0]); } catch (NoSuchMethodException e) { continue; }
                                            Object loader = create.invoke(mc);
                                            Method openWorld;
                                            try { openWorld = loader.getClass().getMethod(n[1], String.class, Runnable.class); } catch (NoSuchMethodException e) { continue; }
                                            openWorld.invoke(loader, world, back);
                                            SfHooks.log("session: left and opened '" + world + "' again (world data reloaded)");
                                            done(doneFile, "done");
                                            return;
                                        }
                                        SfHooks.worldOpening = false;
                                        done(doneFile, "no way to open the world found");
                                    } catch (Throwable t) {
                                        SfHooks.worldOpening = false;
                                        while (t.getCause() != null && t instanceof java.lang.reflect.InvocationTargetException) t = t.getCause();
                                        SfHooks.log("session: rejoin failed: " + t);
                                        done(doneFile, "failed: " + t);
                                    }
                                }
                            };
                            Thread later = new Thread(new Runnable() {
                                public void run() {
                                    try {
                                        long until = System.currentTimeMillis() + 60_000;
                                        int quiet = 0;   // no overlay for a second in a row
                                        while (System.currentTimeMillis() < until && quiet < 4) {
                                            Thread.sleep(250);
                                            quiet = mcClass.getMethod("getOverlay").invoke(mc) == null ? quiet + 1 : 0;
                                        }
                                        mcClass.getMethod("execute", Runnable.class).invoke(mc, open);
                                        // until the player is in the world again (or two minutes): reloads keep waiting
                                        long joined = System.currentTimeMillis() + 120_000;
                                        while (System.currentTimeMillis() < joined && worldOf(mcClass, mc) == null) Thread.sleep(250);
                                    } catch (Throwable t) {
                                        done(doneFile, "failed: " + t);
                                    } finally {
                                        SfHooks.worldOpening = false;
                                    }
                                }
                            }, "sf-rejoin-open");
                            later.setDaemon(true);
                            later.start();
                            return;
                        } catch (Throwable t) {
                            SfHooks.worldOpening = false;
                            while (t.getCause() != null && t instanceof java.lang.reflect.InvocationTargetException) t = t.getCause();
                            SfHooks.log("session: rejoin failed: " + t);
                            done(doneFile, "failed: " + t);
                        }
                    }
                };
                // A reload interrupted by leaving the world never finished, and the world then never finished opening.
                // So: no new reload from now on (worldOpening), wait until the client has been quiet for a second, and
                // leave only then.
                SfHooks.worldOpening = true;
                waitThenLeave[0] = new Runnable() {
                    public void run() {
                        try {
                            long until = System.currentTimeMillis() + 60_000;
                            int quiet = 0;
                            while (System.currentTimeMillis() < until && quiet < 4) {
                                Thread.sleep(250);
                                quiet = mcClass.getMethod("getOverlay").invoke(mc) == null ? quiet + 1 : 0;
                            }
                            mcClass.getMethod("execute", Runnable.class).invoke(mc, leaveAndOpen);
                        } catch (Throwable t) { SfHooks.worldOpening = false; done(doneFile, "failed: " + t); }
                    }
                };
                Thread wait = new Thread(waitThenLeave[0], "sf-rejoin");
                wait.setDaemon(true);
                wait.start();
                return;
            }
            done(doneFile, "no named Minecraft client");
        } catch (Throwable t) {
            done(doneFile, "failed: " + t);
        }
    }

    /**
     * Have the integrated server write the world out ("save-all flush" is a dedicated-server command and does not exist
     * in singleplayer): MinecraftServer.saveAll(suppressLogs, flush, force) (Mojang saveEverything). "done" to the file.
     */
    static void saveWorld(Instrumentation inst, final String doneFile) {
        try {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                int which = -1;
                for (int i = 0; i < CLIENTS.length; i++) if (c.getName().equals(CLIENTS[i])) which = i;
                if (which < 0) continue;
                Object mc = c.getMethod("getInstance").invoke(null);
                final Object server = mc == null ? null : c.getMethod(SERVER_GETTERS[which]).invoke(mc);
                if (server == null) { done(doneFile, "no world open"); return; }
                server.getClass().getMethod("execute", Runnable.class).invoke(server, new Runnable() {
                    public void run() {
                        try {
                            for (String n : new String[]{"saveAll", "saveEverything"}) {
                                Method m;
                                try { m = server.getClass().getMethod(n, boolean.class, boolean.class, boolean.class); } catch (NoSuchMethodException e) { continue; }
                                m.invoke(server, true, true, true);
                                done(doneFile, "done");
                                return;
                            }
                            done(doneFile, "no save method");
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

    private static Object worldOf(Class<?> mcClass, Object mc) {
        for (String f : new String[]{"world", "level"}) {
            try { return mcClass.getField(f).get(mc); } catch (Throwable ignored) { }
        }
        return null;
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
