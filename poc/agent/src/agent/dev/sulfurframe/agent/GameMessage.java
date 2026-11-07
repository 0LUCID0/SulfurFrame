package dev.sulfurframe.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shows a short line above the hotbar (the "action bar", where "Now playing" appears) in the running game.
 *
 * Only named dev environments, which is where hotswap is used: Mojang names (NeoForge/Forge dev, and vanilla 26.x,
 * which ships unobfuscated) and Yarn names (Fabric dev). Runs on the game thread through Minecraft.execute. Shows
 * nothing at the title screen (no HUD) or in an obfuscated game. Java 8, reflection only.
 */
final class GameMessage {
    /** Mojang mapping / Yarn mapping, in that order. */
    private static final String[][] NAMES = {
            // client class, HUD field, HUD class method, text class, text factory
            {"net.minecraft.client.Minecraft", "gui", "setOverlayMessage", "net.minecraft.network.chat.Component", "literal"},
            {"net.minecraft.client.MinecraftClient", "inGameHud", "setOverlayMessage", "net.minecraft.text.Text", "literal"},
    };

    static void show(Instrumentation inst, final String text) {
        try {
            for (String[] n : NAMES) {
                Class<?> mc = loaded(inst, n[0]);
                if (mc == null) continue;
                ClassLoader l = mc.getClassLoader();
                final Object client = mc.getMethod("getInstance").invoke(null);
                if (client == null) return;
                final Field hudField = field(mc, n[1]);
                final Class<?> textClass = Class.forName(n[3], false, l);
                final Method literal = textClass.getMethod(n[4], String.class);
                final String setter = n[2];
                Runnable r = new Runnable() {
                    public void run() {
                        try {
                            Object hud = hudField.get(client);
                            if (hud == null) return;
                            Method set = hud.getClass().getMethod(setter, textClass, boolean.class);
                            set.invoke(hud, literal.invoke(null, text), false);
                            SfHooks.log("in-game message shown: " + text.replaceAll("§.", ""));
                        } catch (Throwable t) { SfHooks.log("in-game message failed: " + t); }
                    }
                };
                mc.getMethod("execute", Runnable.class).invoke(client, r);
                return;
            }
        } catch (Throwable t) {
            SfHooks.log("in-game message unavailable: " + t);
        }
    }

    private static Class<?> loaded(Instrumentation inst, String name) {
        for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(name)) return c;
        return null;
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private GameMessage() {}
}
