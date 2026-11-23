package dev.sulfurframe.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/**
 * Reloads resources in the running game after the IDE copied changed files into the dev resource dir.
 *   assets (textures, models, lang, sounds...): the client's resource reload, what F3+T does
 *   data (recipes, loot tables, tags...): "/reload" on the integrated server, if a world is open
 * Named dev environments only (Mojang names: NeoForge/Forge dev and vanilla 26.x; Yarn: Fabric dev), like
 * {@link GameMessage}. Java 8, reflection only.
 *
 * Waits until the client is idle first: a reload started while a world is loading (or while another reload runs) left
 * the client stuck joining the world, its task queue no longer drained (seen in the self-test when a save arrived as the
 * quick-play world loaded).
 */
final class ResourceReload {
    // client class, client reload method, server getter, command-manager getter, source getter, run-command method, client world field
    private static final String[][] NAMES = {
            {"net.minecraft.client.Minecraft", "reloadResourcePacks", "getSingleplayerServer", "getCommands", "createCommandSourceStack", "performPrefixedCommand", "level"},
            {"net.minecraft.client.MinecraftClient", "reloadResources", "getServer", "getCommandManager", "getCommandSource", "executeWithPrefix", "world"},
    };
    private static final long IDLE_WAIT_MS = 120_000;

    static void reload(final Instrumentation inst, final boolean assets, final boolean data) {
        try {
            for (final String[] n : NAMES) {
                Class<?> found = null;
                for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(n[0])) { found = c; break; }
                if (found == null) continue;
                final Class<?> mc = found;
                final Object client = mc.getMethod("getInstance").invoke(null);
                if (client == null) return;
                if (idle(mc, client, n)) { start(inst, mc, client, n, assets, data); return; }
                SfHooks.log("resources: game is loading, reload waits until it is idle");
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            long until = System.currentTimeMillis() + IDLE_WAIT_MS;
                            while (!idle(mc, client, n) && System.currentTimeMillis() < until) Thread.sleep(250);
                            start(inst, mc, client, n, assets, data);
                        } catch (Throwable e) { SfHooks.log("resources: reload failed: " + e); }
                    }
                }, "sf-resource-reload");
                t.setDaemon(true);
                t.start();
                return;
            }
            SfHooks.log("resources: no named Minecraft client found (obfuscated game?)");
        } catch (Throwable t) {
            SfHooks.log("resources: reload failed: " + t);
        }
    }

    /** No reload overlay up, and not between starting the integrated server and the client's world arriving. */
    private static boolean idle(Class<?> mc, Object client, String[] n) {
        if (SfHooks.worldOpening) return false;
        try {
            if (mc.getMethod("getOverlay").invoke(client) != null) return false;
            Object server = mc.getMethod(n[2]).invoke(client);
            if (server == null) return true;
            for (Class<?> k = mc; k != null; k = k.getSuperclass()) {
                try { java.lang.reflect.Field f = k.getDeclaredField(n[6]); f.setAccessible(true); return f.get(client) != null; }
                catch (NoSuchFieldException ignored) { }
            }
            return true;
        } catch (Throwable t) {
            return true;   // cannot tell: reload as before
        }
    }

    private static void start(Instrumentation inst, Class<?> mc, final Object client, final String[] n, boolean assets, boolean data) {
        try {
            if (assets) {
                final Method reload = mc.getMethod(n[1]);
                mc.getMethod("execute", Runnable.class).invoke(client, new Runnable() {
                    public void run() {
                        try { reload.invoke(client); SfHooks.log("resources: client reload started (assets)"); }
                        catch (Throwable t) { SfHooks.log("resources: client reload failed: " + t); }
                    }
                });
            }
            if (data) {
                final Object server = mc.getMethod(n[2]).invoke(client);
                if (server == null) SfHooks.log("resources: no world open, data reload skipped");
                else {
                    server.getClass().getMethod("execute", Runnable.class).invoke(server, new Runnable() {
                        public void run() {
                            try {
                                Object commands = server.getClass().getMethod(n[3]).invoke(server);
                                Object source = server.getClass().getMethod(n[4]).invoke(server);
                                for (Method m : commands.getClass().getMethods()) {
                                    if (m.getName().equals(n[5]) && m.getParameterTypes().length == 2 && m.getParameterTypes()[1] == String.class) {
                                        m.invoke(commands, source, "reload");
                                        SfHooks.log("resources: /reload on the integrated server (data)");
                                        return;
                                    }
                                }
                                SfHooks.log("resources: no command method found for data reload");
                            } catch (Throwable t) { SfHooks.log("resources: data reload failed: " + t); }
                        }
                    });
                }
            }
            GameMessage.show(inst, "§aResources reloaded" + (assets && data ? " (assets + data)" : assets ? " (assets)" : " (data)"));
        } catch (Throwable t) {
            SfHooks.log("resources: reload failed: " + t);
        }
    }

    private ResourceReload() {}
}
