package dev.sulfurframe.agent;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import java.util.function.LongUnaryOperator;

/**
 * SulfurFrame javaagent. Java 8 bytecode, runs on every Java a moddable Minecraft uses (8 .. 25).
 *
 * The bytecode work lives in sf-impl.jar (our transformer + its own ASM), nested in this jar and loaded in a class
 * loader whose parent is the bootstrap loader, so it never meets the ASM that Fabric/Forge/Mixin bring.
 * Only {@link SfHooks} sits on the system class path, because the patched LWJGL methods call it.
 *
 * Use: -javaagent:sf-agent.jar[=codingFps=15]  or attach to a running game (Attach API; retransforms LWJGL).
 */
public final class SfAgent {
    private static volatile boolean installed;

    public static void premain(String args, Instrumentation inst) { install(args, inst, false); }

    public static void agentmain(String args, Instrumentation inst) { install(args, inst, true); }

    /** Must match LwjglTransformer.REGISTRY_KEY and its SLOT_* order. */
    static final String REGISTRY_KEY = "dev.sulfurframe.hooks";

    /**
     * The patched LWJGL code may not be able to see any class of ours: Fabric's Knot refuses classes from class-path
     * entries "not exposed to the game" (calling SfHooks directly crashed 1.21.1 + Fabric with NoClassDefFoundError),
     * and NeoForge/Forge keep LWJGL in named modules. So the hooks are handed over as JDK functional-interface
     * objects in a system property, which the patched code reads and calls through the JDK interface.
     */
    private static void registerHooks() {
        Object[] hooks = new Object[6];
        hooks[0] = (LongUnaryOperator) SfHooks::sdlWindowFromEvent;
        hooks[1] = (BiFunction<Object, Object, Object>) (got, event) -> SfHooks.sdlPollEvent((Boolean) got, event);
        hooks[2] = (LongPredicate) SfHooks::beforeGlfwSwap;
        hooks[3] = (LongConsumer) SfHooks::beforeSdlSwap;
        hooks[4] = (Runnable) SfHooks::beforeVkPresent;
        hooks[5] = (Runnable) SfHooks::beforeLwjgl2Update;
        System.getProperties().put(REGISTRY_KEY, hooks);
    }

    private static synchronized void install(String args, Instrumentation inst, boolean attached) {
        // "redefine=<list file>": hotswap request from the IDE (each request is a fresh attach)
        String redefine = null;
        if (args != null) for (String kv : args.split(",")) if (kv.startsWith("redefine=")) redefine = kv.substring("redefine=".length());
        if (redefine != null) {
            if (!installed) install(args.replace("redefine=" + redefine, "").replaceAll(",+$", ""), inst, attached);
            Hotswap.redefine(inst, redefine);
            return;
        }
        SfHooks.configure(args);
        if (installed) { SfHooks.log("already installed; settings updated (coding fps cap " + SfHooks.codingFps + ")"); return; }
        installed = true;
        try {
            registerHooks();   // before any class is patched
            ClassLoader impl = implLoader();
            Class<?> tc = Class.forName("dev.sulfurframe.agent.impl.LwjglTransformer", true, impl);
            Consumer<String> log = SfHooks::log;
            Consumer<ClassLoader> sink = SfHooks::lwjglLoader;
            Constructor<?> ctor = tc.getConstructor(Consumer.class, Consumer.class);
            ClassFileTransformer t = (ClassFileTransformer) ctor.newInstance(log, sink);
            inst.addTransformer(t, true);
            SfHooks.log("installed (" + (attached ? "attached" : "premain") + ", Java " + System.getProperty("java.version")
                    + "), coding fps cap " + SfHooks.codingFps);

            if (!attached) return;
            @SuppressWarnings("unchecked")
            Set<String> targets = (Set<String>) tc.getMethod("targetClassNames").invoke(null);
            List<Class<?>> loaded = new ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses())
                if (targets.contains(c.getName()) && inst.isModifiableClass(c)) loaded.add(c);
            if (!loaded.isEmpty()) inst.retransformClasses(loaded.toArray(new Class<?>[0]));
            SfHooks.log("retransformed " + loaded.size() + " loaded LWJGL classes " + loaded);
        } catch (Throwable e) {
            SfHooks.log("install failed: " + e);
        }
    }

    /** Extract the nested sf-impl.jar and load it with the bootstrap loader as parent. */
    private static ClassLoader implLoader() throws Exception {
        File tmp = File.createTempFile("sf-impl-", ".jar");
        tmp.deleteOnExit();
        try (InputStream in = SfAgent.class.getResourceAsStream("/sf-impl.jar")) {
            if (in == null) throw new IllegalStateException("sf-impl.jar missing from the agent jar");
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return new URLClassLoader(new URL[]{tmp.toURI().toURL()}, null);
    }

    private SfAgent() {}
}
