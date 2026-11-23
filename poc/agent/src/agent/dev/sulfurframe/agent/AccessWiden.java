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
import java.util.*;
import java.util.regex.Pattern;

/**
 * Access widener entries added while the game runs ("widen=&lt;list file&gt;", lines "access kind owner name desc" as in
 * the widener). Fabric Loader applies only the wideners it read at startup, so:
 *   - classes that load later get them from the agent's load-time transformer (RegistryPatch.WIDEN)
 *   - loaded classes are redefined from their original bytes with every entry for them so far; Mixin's hotswap agent
 *     transforms the redefinition with the class's mixins again (without it, Mixin's transformer is run here)
 *
 * Guard: Fabric's Mixin gives fields that mixins add unique names with a counter ("fd395724$modid$name$7"); running the
 * mixins again names them anew and their values are lost (this crashed the game with SimpleRegistry). A loaded class
 * with such fields is left to a restart. Java 8, reflection only.
 */
final class AccessWiden {
    private static final Pattern MIXIN_FIELD = Pattern.compile("[0-9a-f]{6,}\\$[^$]+\\$[^$]+\\$\\d+");

    static void run(Instrumentation inst, String listFile) {
        List<String> out = new ArrayList<String>();
        try {
            Class<?> patch = Class.forName("dev.sulfurframe.agent.impl.RegistryPatch", true, SfAgent.impl());
            Method add = patch.getMethod("addWidening", String.class, String.class, String.class, String.class, String.class);
            Method of = patch.getMethod("wideningsOf", String.class);
            Method widen = patch.getMethod("widen", byte[].class, List.class);
            Set<String> owners = new LinkedHashSet<String>();
            for (String line : Files.readAllLines(Paths.get(listFile), StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 3) continue;
                String access = p[0].startsWith("transitive-") ? p[0].substring("transitive-".length()) : p[0];
                add.invoke(null, p[2], access, p[1], p.length > 3 ? p[3] : "", p.length > 4 ? p[4] : "");
                owners.add(p[2]);
            }
            Object agentLoader = mixinAgentLoader(inst);
            for (String owner : owners) {
                String name = owner.replace('/', '.');
                Class<?> c = null;
                for (Class<?> k : inst.getAllLoadedClasses()) if (k.getName().equals(name) && k.getClassLoader() != null) { c = k; break; }
                if (c == null) { out.add("later " + name + " (widened when it loads)"); continue; }
                String renamed = mixinField(c);
                if (renamed != null) { out.add("restart needed: " + name + " (mixin field " + renamed + " would lose its value)"); continue; }
                byte[] original = originalBytes(agentLoader, c);
                if (original == null) { out.add("restart needed: " + name + " (no original bytes)"); continue; }
                byte[] bytes = (byte[]) widen.invoke(null, original, of.invoke(null, owner));
                if (agentLoader == null) bytes = mixinTransform(c, name, bytes);
                inst.redefineClasses(new ClassDefinition(c, bytes));
                out.add("widened " + name);
            }
        } catch (Throwable t) {
            while (t.getCause() != null && t instanceof java.lang.reflect.InvocationTargetException) t = t.getCause();
            out.add("error " + t);
        } finally {
            try { AnswerFile.write(listFile + ".result", out); } catch (Exception ignored) { }
            SfHooks.log("access widener: " + out);
        }
    }

    /** A field of the class named like one a mixin added under a unique name, or null. */
    static String mixinField(Class<?> c) {
        for (Field f : c.getDeclaredFields()) if (MIXIN_FIELD.matcher(f.getName()).matches()) return f.getName();
        return null;
    }

    /** Without Mixin's hotswap agent: the class's mixins applied here (they would be lost otherwise). */
    private static byte[] mixinTransform(Class<?> c, String name, byte[] bytes) throws Exception {
        Class<?> env;
        try { env = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true, c.getClassLoader()); } catch (ClassNotFoundException e) { return bytes; }
        Object transformer = env.getMethod("getActiveTransformer").invoke(env.getMethod("getCurrentEnvironment").invoke(null));
        Method t = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer", false, env.getClassLoader())
                .getMethod("transformClassBytes", String.class, String.class, byte[].class);
        return (byte[]) t.invoke(transformer, null, name, bytes);
    }

    private static Object mixinAgentLoader(Instrumentation inst) {
        try {
            for (Class<?> agent : inst.getAllLoadedClasses()) {
                if (!agent.getName().equals("org.spongepowered.tools.agent.MixinAgent")) continue;
                Field i = agent.getDeclaredField("instrumentation");
                i.setAccessible(true);
                if (i.get(null) == null) return null;
                Field l = agent.getDeclaredField("classLoader");
                l.setAccessible(true);
                return l.get(null);
            }
        } catch (Throwable ignored) { }
        return null;
    }

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

    private AccessWiden() {}
}
