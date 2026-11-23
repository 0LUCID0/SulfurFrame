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
import java.util.List;

/**
 * New mixin classes, added to a mixins.json while the game runs ("mixins=&lt;list file&gt;": the name of an extra config
 * the IDE wrote into the resource output with just the new mixins, then their target classes, one per line).
 *
 * Mixin reads its configs at startup, but takes a config added later (Mixins.addConfiguration) for the classes it
 * transforms from then on. Targets that are already loaded are redefined from their original bytes: Mixin's hotswap
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

            Class<?> env = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true, mixinLoader);
            Object transformer = env.getMethod("getActiveTransformer").invoke(env.getMethod("getCurrentEnvironment").invoke(null));
            Method transform = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer", false, mixinLoader)
                    .getMethod("transformClassBytes", String.class, String.class, byte[].class);
            Object agentLoader = mixinAgentLoader(inst);
            for (String target : lines.subList(1, lines.size())) {
                if (target.isEmpty()) continue;
                Class<?> c = null;
                for (Class<?> k : inst.getAllLoadedClasses()) if (k.getName().equals(target) && k.getClassLoader() != null) { c = k; break; }
                if (c == null) { out.add("later " + target + " (not loaded yet: the mixin applies when it loads)"); continue; }
                String renamed = AccessWiden.mixinField(c);   // running its mixins again would rename and empty such fields
                if (renamed != null) { out.add("error " + target + " has the mixin field " + renamed + ": restart to apply"); continue; }
                byte[] original = originalBytes(agentLoader, c);
                if (original == null) { out.add("error no original bytes for " + target); continue; }
                // with Mixin's hotswap agent the redefinition is transformed by it (all mixins); without, transform here
                byte[] bytes = agentLoader != null ? original : (byte[]) transform.invoke(transformer, null, target, original);
                inst.redefineClasses(new ClassDefinition(c, bytes));
                out.add("applied to " + target);
            }
        } catch (Throwable t) {
            while (t.getCause() != null && t instanceof java.lang.reflect.InvocationTargetException) t = t.getCause();
            out.add("error " + t);
            SfHooks.log("mixins: " + t);
        } finally {
            try { AnswerFile.write(listFile + ".result", out); } catch (Exception ignored) { }
            SfHooks.log("mixins: " + out);
        }
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
