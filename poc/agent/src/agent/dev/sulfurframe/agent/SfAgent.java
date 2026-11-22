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
        Object[] hooks = new Object[8];
        hooks[0] = (LongUnaryOperator) SfHooks::sdlWindowFromEvent;
        hooks[1] = (BiFunction<Object, Object, Object>) (got, event) -> SfHooks.sdlPollEvent((Boolean) got, event);
        hooks[2] = (LongPredicate) SfHooks::beforeGlfwSwap;
        hooks[3] = (LongPredicate) SfHooks::beforeSdlSwap;
        hooks[4] = (Runnable) SfHooks::beforeVkPresent;
        hooks[5] = (java.util.function.BooleanSupplier) SfHooks::beforeLwjgl2Update;
        hooks[6] = (Runnable) SfHooks::beforeGlfwCreateWindow;
        hooks[7] = (LongUnaryOperator) SfHooks::afterGlfwCreateWindow;
        System.getProperties().put(REGISTRY_KEY, hooks);
    }

    private static synchronized void install(String args, Instrumentation inst, boolean attached) {
        // "redefine=<list file>": hotswap request from the IDE (each request is a fresh attach)
        // "reload=assets+data": resources changed (the IDE copied them into the dev resource dir)
        // "world=<file>": write the open world's folder name; "quit": save and exit (IDE quick restart)
        // "register=<list file>": run changed registration code again, registering only what is new (RuntimeRegister)
        String redefine = null, register = null, reload = null, world = null, command = null, done = null, look = null, stats = null;
        boolean quit = false;
        StringBuilder rest = new StringBuilder();
        if (args != null) for (String kv : args.split(",")) {
            if (kv.startsWith("redefine=")) redefine = kv.substring("redefine=".length());
            else if (kv.startsWith("register=")) register = kv.substring("register=".length());
            else if (kv.startsWith("reload=")) reload = kv.substring("reload=".length());
            else if (kv.startsWith("world=")) world = kv.substring("world=".length());
            else if (kv.equals("quit")) quit = true;
            else if (kv.startsWith("cmd=")) command = new String(java.util.Base64.getDecoder().decode(kv.substring(4)), java.nio.charset.StandardCharsets.UTF_8);
            else if (kv.startsWith("done=")) done = kv.substring(5);
            else if (kv.startsWith("look=")) look = kv.substring(5);
            else if (kv.startsWith("stats=")) stats = kv.substring(6);
            else if (!kv.isEmpty()) rest.append(rest.length() == 0 ? "" : ",").append(kv);
        }
        if (redefine != null || register != null || reload != null || world != null || quit || command != null || look != null || stats != null) {
            if (!installed) install(rest.toString(), inst, attached);
            // with init code to run again, the redefinition happens inside that run (old listeners out first)
            if (register != null) RuntimeRegister.run(inst, register, redefine);
            else if (redefine != null) Hotswap.redefine(inst, redefine);
            if (reload != null) ResourceReload.reload(inst, reload.contains("assets"), reload.contains("data"));
            if (world != null) GameSession.writeWorld(inst, world);
            if (command != null) GameSession.serverCommand(inst, command, done);
            if (look != null) LookAt.write(inst, look);
            if (stats != null) GameStats.start(inst, stats);
            if (quit) GameSession.quit();
            return;
        }
        // "launch=<file>": started by the IDE's Run client; tell it which process is ours (pid into the file)
        if (args != null) for (String kv : args.split(",")) if (kv.startsWith("launch=")) { markLaunch(kv.substring("launch=".length())); SfHooks.quietStart = true; }
        SfHooks.configure(args);
        if (installed) { SfHooks.log("already installed; settings updated (coding fps cap " + SfHooks.codingFps + ")"); return; }
        installed = true;
        // options sync must run before Minecraft reads options.txt: only at startup, not when attached later
        if (!attached) ProfileSync.start(args);
        if ("sync".equals(ProfileSync.arg(args, "mode"))) { SfHooks.log("mode=sync: profile sync only, no LWJGL patches"); return; }
        try {
            registerHooks();   // before any class is patched
            ClassLoader impl = impl();
            Class<?> tc = Class.forName("dev.sulfurframe.agent.impl.LwjglTransformer", true, impl);
            Consumer<String> log = SfHooks::log;
            Consumer<ClassLoader> sink = SfHooks::lwjglLoader;
            Constructor<?> ctor = tc.getConstructor(Consumer.class, Consumer.class);
            ClassFileTransformer t = (ClassFileTransformer) ctor.newInstance(log, sink);
            inst.addTransformer(t, true);
            if (!attached) {   // before Minecraft loads: lets changed init code run again (RuntimeRegister)
                Class<?> rp = Class.forName("dev.sulfurframe.agent.impl.RegistryPatch", true, impl);
                inst.addTransformer((ClassFileTransformer) rp.getMethod("loadTimeHooks", Consumer.class).invoke(null, log), false);
            }
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

    private static void markLaunch(String file) {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();   // "pid@host" (Java 8)
            String pid = name.substring(0, name.indexOf('@'));
            writeContext(file + ".ctx");   // before the pid: the IDE reads it once the pid is there
            File tmp = new File(file + ".part");
            Files.write(tmp.toPath(), pid.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            Files.move(tmp.toPath(), new File(file).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            SfHooks.log("could not mark the launch: " + t);
        }
    }

    /**
     * Working folder and environment of this game, for the IDE's relaunch without Gradle (the command line it reads
     * from the OS; these two it cannot). One line each, values Base64 so any character survives:
     * "cwd\t&lt;dir&gt;" and "env\t&lt;name&gt;\t&lt;value&gt;".
     */
    private static void writeContext(String file) {
        try {
            java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
            java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
            StringBuilder sb = new StringBuilder("cwd\t").append(b64.encodeToString(System.getProperty("user.dir").getBytes(utf8))).append('\n');
            for (java.util.Map.Entry<String, String> e : System.getenv().entrySet())
                sb.append("env\t").append(b64.encodeToString(e.getKey().getBytes(utf8))).append('\t')
                        .append(b64.encodeToString(e.getValue().getBytes(utf8))).append('\n');
            File tmp = new File(file + ".part");
            Files.write(tmp.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            Files.move(tmp.toPath(), new File(file).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            SfHooks.log("could not write the launch context: " + t);
        }
    }

    private static ClassLoader impl;

    /** The loader of the nested sf-impl.jar (transformers and their ASM), made once. */
    static synchronized ClassLoader impl() throws Exception {
        if (impl == null) impl = implLoader();
        return impl;
    }

    /** Extract the nested sf-impl.jar and load it with the bootstrap loader as parent. */
    private static ClassLoader implLoader() throws Exception {
        // One file per agent build, reused: a temp file per start (deleteOnExit) piled up in %TEMP% because a game
        // that is killed never runs its exit hooks.
        byte[] bytes;
        try (InputStream in = SfAgent.class.getResourceAsStream("/sf-impl.jar")) {
            if (in == null) throw new IllegalStateException("sf-impl.jar missing from the agent jar");
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            bytes = buf.toByteArray();
        }
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        File jar = new File(System.getProperty("java.io.tmpdir"), "sf-impl-" + Long.toHexString(crc.getValue()) + ".jar");
        if (!jar.isFile() || jar.length() != bytes.length) {
            File tmp = File.createTempFile("sf-impl-", ".part", jar.getParentFile());
            Files.write(tmp.toPath(), bytes);
            try { Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception e) { if (!jar.isFile()) throw e; tmp.delete(); }   // another game won the race: use theirs
        }
        return new URLClassLoader(new URL[]{jar.toURI().toURL()}, null);
    }

    private SfAgent() {}
}
