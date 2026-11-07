package dev.sulfurframe.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/**
 * Reloads resources in the running game after the IDE copied changed files into the dev resource dir.
 *   assets (textures, models, lang, sounds...): the client's resource reload, what F3+T does
 *   data (recipes, loot tables, tags...): "/reload" on the integrated server, if a world is open
 * Named dev environments only (Mojang names: NeoForge/Forge dev and vanilla 26.x; Yarn: Fabric dev), like
 * {@link GameMessage}. Java 8, reflection only.
 */
final class ResourceReload {
    // client class, client reload method, server getter, server class, command-manager getter, source getter, run-command method
    private static final String[][] NAMES = {
            {"net.minecraft.client.Minecraft", "reloadResourcePacks", "getSingleplayerServer", "getCommands", "createCommandSourceStack", "performPrefixedCommand"},
            {"net.minecraft.client.MinecraftClient", "reloadResources", "getServer", "getCommandManager", "getCommandSource", "executeWithPrefix"},
    };

    static void reload(Instrumentation inst, boolean assets, boolean data) {
        try {
            for (final String[] n : NAMES) {
                Class<?> mc = null;
                for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(n[0])) { mc = c; break; }
                if (mc == null) continue;
                final Object client = mc.getMethod("getInstance").invoke(null);
                if (client == null) return;
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
                return;
            }
            SfHooks.log("resources: no named Minecraft client found (obfuscated game?)");
        } catch (Throwable t) {
            SfHooks.log("resources: reload failed: " + t);
        }
    }

    private ResourceReload() {}
}
