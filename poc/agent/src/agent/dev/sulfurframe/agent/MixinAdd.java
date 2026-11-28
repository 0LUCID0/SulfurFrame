package dev.sulfurframe.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * New mixin classes, added to a mixins.json while the game runs ("mixins=&lt;list file&gt;": the name of an extra config
 * the IDE wrote into the resource output with just the new mixins, then their target classes, one per line).
 *
 * Mixin reads its configs at startup, but takes a config added later (Mixins.addConfiguration) for the classes it
 * transforms from then on, once it selects again (forced here: see run). Targets that are already loaded are redefined from their original bytes: Mixin's hotswap
 * agent (on the game JVM since Run client) transforms a redefined class with every mixin it knows, the new ones
 * included. Without that agent the bytes are run through Mixin's transformer here. Java 8, reflection only.
 */
final class MixinAdd {
    static void run(Instrumentation inst, String listFile) {
        List<String> out = new ArrayList<String>();
        try {
            List<String> lines = Files.readAllLines(Paths.get(listFile), StandardCharsets.UTF_8);
            String config = lines.get(0);
            Class<?> mixins = loaded(inst, "org.spongepowered.asm.mixin.Mixins");
            if (mixins == null) { out.add("error Mixin is not in this game"); return; }
            ClassLoader mixinLoader = mixins.getClassLoader();
            mixins.getMethod("addConfiguration", String.class).invoke(null, config);
            out.add("config " + config);
            // Mixin selects configs added later only while it has transformed nothing in this phase (transformedCount
            // 0): set it back, so the next class it transforms (the redefinitions below, or the next class loaded)
            // takes the new config in
            try {
                Class<?> env = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true, mixinLoader);
                Object processor = field(env.getMethod("getActiveTransformer").invoke(env.getMethod("getCurrentEnvironment").invoke(null)), "processor");
                Field count = processor.getClass().getDeclaredField("transformedCount");
                count.setAccessible(true);
                count.setInt(processor, 0);
            } catch (Throwable t) {
                out.add("note the new config waits for Mixin's next selection (" + t + ")");
            }

            reapply(inst, mixinLoader, lines.subList(1, lines.size()), out);
        } catch (Throwable t) {
            while (t.getCause() != null && t instanceof java.lang.reflect.InvocationTargetException) t = t.getCause();
            out.add("error " + t);
            SfHooks.log("mixins: " + t);
        } finally {
            try { AnswerFile.write(listFile + ".result", out); } catch (Exception ignored) { }
            SfHooks.log("mixins: " + out);
        }
    }

    /**
     * Mixin classes taken out of a mixins.json while the game runs ("unmixins=&lt;list file&gt;": their full names, one
     * per line). Each leaves every config Mixin holds (the game's and SulfurFrame's hot ones) and its targets' class
     * info, and the loaded targets are redefined from their original bytes, transformed with the mixins that remain:
     * the injected handlers, unique fields and members it added go, an overwritten method gets its own code back. A
     * target whose mixin fields were renamed is refused before anything changes.
     */
    static void remove(Instrumentation inst, String listFile) {
        List<String> out = new ArrayList<String>();
        try {
            List<String> names = new ArrayList<String>();
            for (String l : Files.readAllLines(Paths.get(listFile), StandardCharsets.UTF_8)) if (!l.trim().isEmpty()) names.add(l.trim());
            Class<?> mixins = loaded(inst, "org.spongepowered.asm.mixin.Mixins");
            if (mixins == null) { out.add("error Mixin is not in this game"); return; }
            ClassLoader mixinLoader = mixins.getClassLoader();
            Class<?> env = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true, mixinLoader);
            Object transformer = env.getMethod("getActiveTransformer").invoke(env.getMethod("getCurrentEnvironment").invoke(null));
            Object processor = field(transformer, "processor");
            List<Object> configs = new ArrayList<Object>();
            configs.addAll((List<?>) field(processor, "configs"));
            configs.addAll((List<?>) field(processor, "pendingConfigs"));
            // find them all first: nothing changes unless every loaded target can be done again
            Map<String, List<Object[]>> found = new LinkedHashMap<String, List<Object[]>>();   // name -> (config, MixinInfo)
            Set<String> targets = new LinkedHashSet<String>();
            for (String name : names) {
                List<Object[]> where = new ArrayList<Object[]>();
                for (Object config : configs)
                    for (Object info : (List<?>) field(config, "mixins"))
                        if (name.equals(call(info, "getClassName"))) {
                            where.add(new Object[]{config, info});
                            for (Object tc : (List<?>) call(info, "getTargetClasses")) targets.add(((String) tc).replace('/', '.'));
                        }
                if (where.isEmpty()) out.add("gone " + name + " (Mixin does not hold it)");
                else found.put(name, where);
            }
            for (String target : targets) {
                Class<?> c = loadedTarget(inst, target);
                String renamed = c == null ? null : AccessWiden.mixinField(c);
                if (renamed != null) { out.add("error " + target + " has the mixin field " + renamed + ": restart to apply"); return; }
            }
            Class<?> configClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig", false, mixinLoader);
            Set<?> global = (Set<?>) staticField(configClass, "globalMixinList");
            Map<?, ?> classInfos = (Map<?, ?>) staticField(Class.forName("org.spongepowered.asm.mixin.transformer.ClassInfo", false, mixinLoader), "cache");
            for (Map.Entry<String, List<Object[]>> e : found.entrySet()) {
                for (Object[] ci : e.getValue()) {
                    Object config = ci[0], info = ci[1];
                    ((List<?>) field(config, "mixins")).remove(info);
                    ((List<?>) field(config, "pendingMixins")).remove(info);
                    for (Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) field(config, "mixinMapping")).entrySet().iterator(); it.hasNext(); ) {
                        List<?> forTarget = (List<?>) it.next().getValue();
                        forTarget.remove(info);
                        if (forTarget.isEmpty()) it.remove();   // hasMixinsFor is "has a key": an empty list still sends the target through
                    }
                    for (Object tc : (List<?>) call(info, "getTargetClasses")) {
                        Object target = classInfos.get(((String) tc).replace('.', '/'));
                        if (target == null) continue;
                        Object set = field(target, "mixins");
                        if (set instanceof Set) try { ((Set<?>) set).remove(info); } catch (UnsupportedOperationException ignored) { }
                        Object applied = field(target, "appliedMixins");
                        if (applied instanceof Set) ((Set<?>) applied).remove(info);
                    }
                }
                global.remove(e.getKey());   // added again later, Mixin takes it anew
                out.add("removed " + e.getKey());
            }
            reapply(inst, mixinLoader, new ArrayList<String>(targets), out);
        } catch (Throwable t) {
            while (t.getCause() != null && t instanceof java.lang.reflect.InvocationTargetException) t = t.getCause();
            out.add("error " + t);
            SfHooks.log("mixins: " + t);
        } finally {
            try { AnswerFile.write(listFile + ".result", out); } catch (Exception ignored) { }
            SfHooks.log("mixins: " + out);
        }
    }

    /**
     * Loaded targets redefined from their original bytes: Mixin's hotswap agent (on the game JVM since Run client)
     * transforms a redefined class with every mixin it holds now; without that agent the bytes go through Mixin's
     * transformer here.
     */
    private static void reapply(Instrumentation inst, ClassLoader mixinLoader, List<String> targets, List<String> out) throws Exception {
        Class<?> env = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true, mixinLoader);
        Object transformer = env.getMethod("getActiveTransformer").invoke(env.getMethod("getCurrentEnvironment").invoke(null));
        Method transform = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer", false, mixinLoader)
                .getMethod("transformClassBytes", String.class, String.class, byte[].class);
        Object agentLoader = mixinAgentLoader(inst);
        for (String target : targets) {
            if (target.isEmpty()) continue;
            Class<?> c = loadedTarget(inst, target);
            if (c == null) { out.add("later " + target + " (not loaded yet: it is transformed when it loads)"); continue; }
            String renamed = AccessWiden.mixinField(c);   // running its mixins again would rename and empty such fields
            if (renamed != null) { out.add("error " + target + " has the mixin field " + renamed + ": restart to apply"); continue; }
            byte[] original = originalBytes(agentLoader, c);
            if (original == null) { out.add("error no original bytes for " + target); continue; }
            byte[] bytes = agentLoader != null ? original : (byte[]) transform.invoke(transformer, null, target, original);
            inst.redefineClasses(new ClassDefinition(c, bytes));
            out.add("applied to " + target);
        }
    }

    private static Class<?> loadedTarget(Instrumentation inst, String name) {
        for (Class<?> k : inst.getAllLoadedClasses()) if (k.getName().equals(name) && k.getClassLoader() != null) return k;
        return null;
    }

    /** An instance field by name, looked up through the superclasses. */
    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }

    /** A no-argument method of a class that may not be public (MixinInfo is package-private: its public methods too). */
    private static Object call(Object o, String name) throws Exception {
        Method m = o.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(o);
    }

    private static Object staticField(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    /** Mixin's hotswap agent class loader (holds the targets' original bytes), or null if that agent is not active. */
    private static Object mixinAgentLoader(Instrumentation inst) {
        try {
            Class<?> agent = loaded(inst, "org.spongepowered.tools.agent.MixinAgent");
            if (agent == null) return null;
            Field instrumentation = agent.getDeclaredField("instrumentation");
            instrumentation.setAccessible(true);
            if (instrumentation.get(null) == null) return null;
            Field loader = agent.getDeclaredField("classLoader");
            loader.setAccessible(true);
            return loader.get(null);
        } catch (Throwable t) { return null; }
    }

    /** What the class was before any mixin: as Mixin's agent registered it, else the class file itself. */
    private static byte[] originalBytes(Object agentLoader, Class<?> c) throws Exception {
        if (agentLoader != null) {
            try {
                Method m = agentLoader.getClass().getDeclaredMethod("getOriginalTargetBytecode", String.class);
                m.setAccessible(true);
                byte[] b = (byte[]) m.invoke(agentLoader, c.getName());
                if (b != null) return b;
            } catch (Throwable ignored) { }
        }
        InputStream in = c.getClassLoader().getResourceAsStream(c.getName().replace('.', '/') + ".class");
        if (in == null) return null;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[1 << 14];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return buf.toByteArray();
        } finally { in.close(); }
    }

    private static Class<?> loaded(Instrumentation inst, String name) {
        for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(name)) return c;
        return null;
    }

    private MixinAdd() {}
}
