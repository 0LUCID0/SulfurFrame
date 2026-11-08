package dev.sulfurframe.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * What the player is looking at, for the IDE: the block (position, state, block / block entity class, block entity
 * NBT if it can be written) or the entity (type, class, NBT). Lines "key=value" into a file. Runs on the game thread.
 * Mojang names (NeoForge/Forge dev, 26.x) and Yarn names (Fabric dev); everything by reflection. Java 8.
 */
final class LookAt {
    private static final String[] CLIENTS = {"net.minecraft.client.Minecraft", "net.minecraft.client.MinecraftClient"};
    private static final String[] HIT = {"hitResult", "crosshairTarget"};
    private static final String[] LEVEL = {"level", "world"};

    static void write(Instrumentation inst, final String file) {
        try {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                int which = -1;
                for (int i = 0; i < CLIENTS.length; i++) if (c.getName().equals(CLIENTS[i])) which = i;
                if (which < 0) continue;
                final Object mc = c.getMethod("getInstance").invoke(null);
                final int w = which;
                c.getMethod("execute", Runnable.class).invoke(mc, new Runnable() {
                    public void run() { out(file, describe(mc, w)); }
                });
                return;
            }
            out(file, list("error=no named Minecraft client"));
        } catch (Throwable t) {
            out(file, list("error=" + t));
        }
    }

    private static List<String> describe(Object mc, int w) {
        List<String> out = new ArrayList<String>();
        try {
            for (String n : new String[]{"screen", "currentScreen"}) {
                try { Object s = field(mc, n); out.add("screen=" + (s == null ? "none" : s.getClass().getSimpleName())); break; } catch (NoSuchFieldException ignored) { }
            }
            Object player = field(mc, "player");
            if (player != null) for (String n : new String[]{"getYRot", "getYaw"}) {
                try { out.add("yaw=" + player.getClass().getMethod(n).invoke(player)); break; } catch (NoSuchMethodException ignored) { }
            }
        } catch (Throwable ignored) { }
        try {
            Object hit = field(mc, HIT[w]);
            if (hit == null) { out.add("kind=none"); return out; }
            String kind = hit.getClass().getSimpleName();
            Object level = field(mc, LEVEL[w]);
            if (kind.contains("Block")) {
                Object pos = hit.getClass().getMethod("getBlockPos").invoke(hit);
                out.add("kind=block");
                out.add("pos=" + pos);
                Object state = call(level, "getBlockState", pos);
                out.add("state=" + state);
                Object block = call(state, "getBlock");
                out.add("blockClass=" + block.getClass().getName());
                Object be = call(level, "getBlockEntity", pos);
                if (be != null) {
                    out.add("blockEntityClass=" + be.getClass().getName());
                    String nbt = nbt(be, level);
                    if (nbt != null) out.add("nbt=" + nbt);
                }
            } else if (kind.contains("Entity")) {
                Object entity = hit.getClass().getMethod("getEntity").invoke(hit);
                out.add("kind=entity");
                out.add("entity=" + entity);
                out.add("entityClass=" + entity.getClass().getName());
                String nbt = nbt(entity, level);
                if (nbt != null) out.add("nbt=" + nbt);
            } else {
                out.add("kind=miss");
            }
        } catch (Throwable t) {
            out.add("error=" + t);
        }
        return out;
    }

    /** NBT of a block entity or entity, through whichever save method this version and mapping has. */
    private static String nbt(Object o, Object level) {
        Object registries = null;
        for (String n : new String[]{"registryAccess", "getRegistryManager"}) {
            try { registries = level.getClass().getMethod(n).invoke(level); break; } catch (Throwable ignored) { }
        }
        // block entities (1.20.5+): saveWithFullMetadata(HolderLookup.Provider) / createNbtWithIdentifyingData(WrapperLookup)
        for (String n : new String[]{"saveWithFullMetadata", "createNbtWithIdentifyingData", "saveWithoutMetadata", "createNbt"}) {
            for (Method m : o.getClass().getMethods()) {
                if (!m.getName().equals(n) || m.getParameterTypes().length != (registries == null ? 0 : 1)) continue;
                try { return String.valueOf(registries == null ? m.invoke(o) : m.invoke(o, registries)); } catch (Throwable ignored) { }
            }
        }
        // entities: saveWithoutId(CompoundTag) / writeNbt(NbtCompound)
        for (String n : new String[]{"saveWithoutId", "writeNbt"}) {
            for (Method m : o.getClass().getMethods()) {
                if (!m.getName().equals(n) || m.getParameterTypes().length != 1) continue;
                try { return String.valueOf(m.invoke(o, m.getParameterTypes()[0].getConstructor().newInstance())); } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static Object call(Object o, String name, Object... args) throws Exception {
        for (Method m : o.getClass().getMethods())
            if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                try { return m.invoke(o, args); } catch (IllegalArgumentException ignored) { }
            }
        throw new NoSuchMethodException(name);
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static List<String> list(String s) { List<String> l = new ArrayList<String>(); l.add(s); return l; }

    private static void out(String file, List<String> lines) {
        try { AnswerFile.write(file, lines); } catch (IOException ignored) { }
    }

    private LookAt() {}
}
