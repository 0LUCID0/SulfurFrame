package dev.sulfurframe.agent;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Registers new blocks, items and other registry entries while the game runs, after the IDE hotswapped a class whose
 * static initializer or register method changed ("register=&lt;list file&gt;", one line per class:
 * "binary name \t new class file \t method;method"). For each class:
 *
 *   1. the registries are unfrozen (Minecraft freezes them after startup)
 *   2. a renamed copy of the new class (the shadow) runs its static initializer and the listed register methods;
 *      its Registry.register calls answer ids that are already there with the existing entry, so only new ones are
 *      added (RegistryPatch.shadow)
 *   3. static fields the hotswap added to the real class (null so far) take the shadow's values
 *   4. the registries are frozen again; new items get their model id, creative tabs rebuild; resources reload
 *
 * Runs on the render thread with the integrated server parked. Fabric's registry sync does the rest itself (block state
 * ids, block items). Singleplayer dev clients only; Yarn and Mojang names, Java 8, reflection only.
 *
 * Checked on Fabric 1.21.1 (experiment, 2026-11-22): what broke without the fix-ups was the item model (the item
 * renderer fills its model ids once, at startup) and the creative tabs (built once, cached).
 */
final class RuntimeRegister {
    // 0 client class, 1 registry root holder, 2 root field, 3 SimpleRegistry class, 4 frozen, 5 intrusive holders,
    // 6 key->entry map, 7 reference type field, 8 item renderer getter, 9 item models getter, 10 put model,
    // 11 model id class, 12 inventory variant, 13 creative tab holder, 14 cached parameters field, 15 key -> id method
    private static final String[][] NAMES = {
            {"net.minecraft.client.MinecraftClient", "net.minecraft.registry.Registries", "REGISTRIES", "net.minecraft.registry.SimpleRegistry",
                    "frozen", "intrusiveValueToEntry", "keyToEntry", "referenceType", "getItemRenderer", "getModels", "putModel",
                    "net.minecraft.client.util.ModelIdentifier", "ofInventoryVariant", "net.minecraft.item.ItemGroups", "displayContext", "getValue"},
            {"net.minecraft.client.Minecraft", "net.minecraft.core.registries.BuiltInRegistries", "REGISTRY", "net.minecraft.core.MappedRegistry",
                    "frozen", "unregisteredIntrusiveHolders", "byKey", "type", "getItemRenderer", "getItemModelShaper", "register",
                    "net.minecraft.client.resources.model.ModelResourceLocation", "inventory", "net.minecraft.world.item.CreativeModeTabs", "CACHED_PARAMETERS", "location"},
    };
    private static int shadows;
    /** Registries whose entries are intrusive holders, seen the first time (by identity). */
    private static final Map<Object, Boolean> INTRUSIVE = new IdentityHashMap<Object, Boolean>();

    /**
     * @param redefineList the hotswap's own list, or null: redefined here, after the entry points' old listeners are
     *                     out (a listener whose lambda method the new class no longer has would crash the next tick)
     */
    static void run(final Instrumentation inst, final String listFile, final String redefineList) {
        final List<String> out = new ArrayList<String>();
        try {
            final List<String[]> classes = new ArrayList<String[]>();
            for (String line : Files.readAllLines(Paths.get(listFile), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", -1);
                if (p.length >= 2) classes.add(p);
            }
            String[] names = null;
            Class<?> mc = null;
            for (String[] n : NAMES) { mc = loaded(inst, n[0]); if (mc != null) { names = n; break; } }
            if (mc == null) { finish(listFile, out, "error no named Minecraft client (obfuscated game?)"); return; }
            final String[] n = names;
            final Class<?> mcClass = mc;
            final Object client = mc.getMethod("getInstance").invoke(null);
            mc.getMethod("execute", Runnable.class).invoke(client, new Runnable() {
                public void run() {
                    long t0 = System.nanoTime();
                    CountDownLatch release = new CountDownLatch(1);
                    try {
                        parkServer(mcClass, client, release);
                        registerAll(inst, mcClass, client, n, classes, redefineList, out);
                    } catch (Throwable t) {
                        out.add("error " + cause(t));
                        java.io.StringWriter trace = new java.io.StringWriter();
                        unwrap(t).printStackTrace(new java.io.PrintWriter(trace));
                        SfHooks.log("runtime registration failed: " + trace);
                    } finally {
                        release.countDown();
                    }
                    out.add("ms " + (System.nanoTime() - t0) / 1_000_000);
                    finish(listFile, out, null);
                }
            });
        } catch (Throwable t) {
            finish(listFile, out, "error " + cause(t));
        }
    }

    private static void finish(String listFile, List<String> out, String extra) {
        if (extra != null) { out.add(extra); SfHooks.log("runtime registration: " + extra); }
        try { AnswerFile.write(listFile + ".result", out); } catch (Exception ignored) { }
    }

    /** The integrated server waits in one of its tasks until {@code release}, so nothing reads a registry mid-change. */
    private static void parkServer(Class<?> mc, Object client, final CountDownLatch release) throws Exception {
        Object server = null;
        for (String getter : new String[]{"getServer", "getSingleplayerServer"}) {
            try { server = mc.getMethod(getter).invoke(client); break; } catch (NoSuchMethodException ignored) { }
        }
        if (server == null) return;
        final CountDownLatch parked = new CountDownLatch(1);
        server.getClass().getMethod("execute", Runnable.class).invoke(server, new Runnable() {
            public void run() {
                parked.countDown();
                try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
        });
        if (!parked.await(5, TimeUnit.SECONDS)) SfHooks.log("runtime registration: server did not pause, going on");
    }

    private static void registerAll(Instrumentation inst, Class<?> mc, Object client, String[] n, List<String[]> classes, String redefineList,
                                    List<String> out) throws Throwable {
        ClassLoader gameLoader = mc.getClassLoader();
        Class<?> simple = Class.forName(n[3], false, gameLoader);
        final Field frozen = field(simple, n[4]), intrusive = field(simple, n[5]), keyToEntry = field(simple, n[6]);
        final Class<?> rootHolder = Class.forName(n[1], true, gameLoader);
        final String idMethod = n[15];

        // 1. unfreeze
        final List<Object> unfrozen = new ArrayList<Object>();
        for (Object reg : (Iterable<?>) field(rootHolder, n[2]).get(null)) {
            if (!simple.isInstance(reg) || !frozen.getBoolean(reg)) continue;
            if (!INTRUSIVE.containsKey(reg)) INTRUSIVE.put(reg, intrusive(reg, keyToEntry, n[7]));
            unfrozen.add(reg);
        }
        reopen(unfrozen, frozen, intrusive, false);
        SfHooks.log("runtime registration: " + unfrozen.size() + " registries unfrozen");
        final List<Object[]> added = new ArrayList<Object[]>();   // {registry, "namespace:path"}
        final List<Object[]> pairs = new ArrayList<Object[]>();   // {new object, existing object}: settings to hand on
        final Map<Object, String> ids = new IdentityHashMap<Object, String>();
        final int[] kept = {0};
        // {"register" | "registerReference", registry, id (Identifier, RegistryKey or String), value} -> existing or null
        Function<Object[], Object> hook = new Function<Object[], Object>() {
            public Object apply(Object[] call) {
                try {
                    // frozen again since the run began (seen between two classes): open it again, or add() throws
                    if (frozen.getBoolean(call[1])) {
                        frozen.setBoolean(call[1], false);
                        if (Boolean.TRUE.equals(INTRUSIVE.get(call[1])) && intrusive.get(call[1]) == null) intrusive.set(call[1], new IdentityHashMap<Object, Object>());
                        SfHooks.log("runtime registration: " + call[1] + " was frozen again during the run, opened again");
                    }
                    String id = idOf(call[2], idMethod);
                    Object ref = entry(keyToEntry, call[1], id, idMethod);
                    if (ref != null) {
                        kept[0]++;
                        Object existing = ref.getClass().getMethod("value").invoke(ref);
                        if (call[3] != null && call[3] != existing) {
                            boolean seen = false;
                            for (Object[] p : pairs) seen |= p[1] == existing;
                            if (!seen) { pairs.add(new Object[]{call[3], existing}); ids.put(existing, registryName(rootHolder, call[1]) + " " + id); }
                        }
                        return "registerReference".equals(call[0]) ? ref : existing;
                    }
                    for (Object[] a : added) if (a[0] == call[1] && a[1].equals(id)) return null;   // bridge and Registry hook both ask
                    added.add(new Object[]{call[1], id});
                } catch (Throwable t) { SfHooks.log("runtime registration: hook: " + t); }
                return null;
            }
        };
        System.getProperties().put("dev.sulfurframe.registry", hook);
        // who else freezes a registry during the run (seen, cause unknown): log the caller once per registry
        final boolean[] ourFreeze = {false};
        final Set<Object> reported = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        System.getProperties().put("dev.sulfurframe.freezeWatch", new java.util.function.Consumer<Object>() {
            public void accept(Object registry) {
                if (ourFreeze[0] || !unfrozen.contains(registry) || !reported.add(registry)) return;
                StringBuilder at = new StringBuilder();
                StackTraceElement[] st = new Throwable().getStackTrace();
                for (int i = 1; i < st.length && i < 14; i++) at.append("\n\tat ").append(st[i]);
                SfHooks.log("runtime registration: " + registry + " frozen during the run by thread '" + Thread.currentThread().getName() + "'" + at);
            }
        });
        try {
            // entry points: their old registrations out, before the classes change under them
            Map<String[], Object[]> entries = new LinkedHashMap<String[], Object[]>();
            for (String[] c : classes) {
                if (c.length < 4 || c[3].isEmpty()) continue;
                Class<?> real = loadedClass(inst, c[0]);
                if (real == null) continue;
                List<String> owners = new ArrayList<String>(Arrays.asList(c.length > 4 && !c[4].isEmpty() ? c[4].split(",") : new String[0]));
                if (!owners.contains(c[0])) owners.add(c[0]);
                entries.put(c, EntryRerun.prepare(real, c[3].split(";"), owners));
            }
            boolean redefined = true;   // not all classes changed: the old static code stays, entry points still run (listeners back)
            if (redefineList != null)
                for (String r : Hotswap.redefine(inst, redefineList)) if (r.startsWith("restart needed") || r.startsWith("error")) redefined = false;
            if (!redefined) out.add("skipped static code: the classes could not all be redefined");
            reopen(unfrozen, frozen, intrusive, true);   // seen frozen again before the code ran (a new item had no holder map)
            // 2.-3. shadow runs (new static fields take their values), entry points run again
            for (String[] c : classes) {
                Class<?> real = loadedClass(inst, c[0]);
                if (real == null) { out.add("skipped " + c[0] + " (not loaded)"); continue; }
                String mode = c.length > 5 ? c[5] : "";
                reopen(unfrozen, frozen, intrusive, true);   // before each class: something froze them between two before
                if (redefined && (mode.equals("enum") || mode.equals("switchmap"))) ClinitReplay.run(real, c[1], mode, out);
                else if (redefined && c.length > 2 && !c[2].isEmpty()) shadowRun(real, c, out);
                if (entries.containsKey(c)) { reopen(unfrozen, frozen, intrusive, true); EntryRerun.run(entries.get(c), out); }
            }
        } finally {
            System.getProperties().remove("dev.sulfurframe.registry");
            ourFreeze[0] = true;
            // 4. freeze again; holders made for objects that were not registered (the shadow's copies of existing ones) go
            for (Object reg : unfrozen) {
                try {
                    if (intrusive.get(reg) != null) intrusive.set(reg, new IdentityHashMap<Object, Object>());
                    reg.getClass().getMethod("freeze").invoke(reg);
                } catch (Throwable t) {
                    out.add("error freezing " + reg + ": " + cause(t));
                    frozen.setBoolean(reg, true);
                }
            }
        }
        System.getProperties().remove("dev.sulfurframe.freezeWatch");
        out.add("kept " + kept[0]);
        boolean redraw = false;
        try { redraw = SettingsTransfer.apply(pairs, ids, out); }
        catch (Throwable t) { out.add("warning settings: " + cause(t)); }
        if (redraw) redrawChunks(mc, client, out);
        boolean entries = false;
        for (String[] c : classes) entries |= c.length > 3 && !c[3].isEmpty();
        Set<String> shortIds = new LinkedHashSet<String>();
        for (Object[] a : added) {
            out.add("registered " + registryName(rootHolder, a[0]) + " " + a[1]);
            shortIds.add((String) a[1]);
        }
        SfHooks.log("runtime registration: " + added.size() + " new " + shortIds + ", " + kept[0] + " already there");
        if (added.isEmpty() && !entries) return;
        if (!added.isEmpty()) fixUpClient(mc, client, n, rootHolder, keyToEntry, added, out);
        boolean clientEntry = false;
        for (String[] c : classes) clientEntry |= c.length > 3 && c[3].contains("onInitializeClient");
        // new entries: models, translations (client) and loot tables, tags (server); init code alone: renderers and
        // the like (client), and no /reload, which holds the server for seconds
        boolean commands = out.contains("data reload needed");   // /reload: command tree, loot tables, data listeners
        if (!added.isEmpty()) ResourceReload.reload(inst, true, true);
        else if (clientEntry || commands) ResourceReload.reload(inst, clientEntry, commands);
        GameMessage.show(inst, added.isEmpty() ? "§aInit code ran again" : "§aRegistered " + added.size() + " new: " + String.join(", ", shortIds));
    }

    /** A block's look or light changed: the client builds its chunk meshes again (what F3+A does). */
    private static void redrawChunks(Class<?> mc, Object client, List<String> out) {
        try {
            for (String[] n : new String[][]{{"worldRenderer", "reload"}, {"levelRenderer", "allChanged"}}) {
                Field f;
                try { f = field(mc, n[0]); } catch (NoSuchFieldException e) { continue; }
                Object renderer = f.get(client);
                if (renderer != null) renderer.getClass().getMethod(n[1]).invoke(renderer);
                return;
            }
        } catch (Throwable t) { out.add("warning chunks not redrawn: " + cause(t)); }
    }

    /** Unfrozen, and intrusive ones with a holder map (new blocks and items need one in their constructor). */
    private static void reopen(List<Object> regs, Field frozen, Field intrusive, boolean report) throws IllegalAccessException {
        int again = 0;
        for (Object reg : regs) {
            boolean was = frozen.getBoolean(reg);
            frozen.setBoolean(reg, false);
            if (Boolean.TRUE.equals(INTRUSIVE.get(reg)) && intrusive.get(reg) == null) { intrusive.set(reg, new IdentityHashMap<Object, Object>()); was = true; }
            if (was) again++;
        }
        if (report && again > 0) SfHooks.log("runtime registration: " + again + " registries were frozen again in between, opened again");
    }

    /** The shadow copy runs the static initializer and the listed static methods; new static fields take its values. */
    private static void shadowRun(Class<?> real, String[] line, List<String> out) throws Throwable {
        String name = line[0];
        byte[] bytes = Files.readAllBytes(Paths.get(line[1]));
        String shadowName = name.replace('.', '/') + "$SfRegister" + (++shadows);
        Class<?> patch = Class.forName("dev.sulfurframe.agent.impl.RegistryPatch", true, SfAgent.impl());
        byte[] shadowBytes = (byte[]) patch.getMethod("shadow", byte[].class, String.class).invoke(null, bytes, shadowName);
        Object lookup = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class)
                .invoke(null, real, MethodHandles.lookup());
        Class<?> shadow = (Class<?>) lookup.getClass().getMethod("defineClass", byte[].class).invoke(lookup, shadowBytes);
        Class.forName(shadow.getName(), true, real.getClassLoader());   // its static initializer
        for (String m : line[2].split(";")) {
                if (m.equals("<clinit>")) continue;
                Method method = shadow.getDeclaredMethod(m);
                if (!Modifier.isStatic(method.getModifiers())) continue;
                method.setAccessible(true);
                method.invoke(null);
            }
        int adopted = 0;
        Object unsafe = null;
        for (Field f : real.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            if (f.get(null) != null) continue;
            Field sf;
            try { sf = shadow.getDeclaredField(f.getName()); } catch (NoSuchFieldException e) { continue; }
            sf.setAccessible(true);
            Object v = sf.get(null);
            if (v == null) continue;
            if (Modifier.isFinal(f.getModifiers())) {   // reflection cannot set a static final field: Unsafe can
                if (unsafe == null) { Field u = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); u.setAccessible(true); unsafe = u.get(null); }
                Class<?> uc = unsafe.getClass();
                Object base = uc.getMethod("staticFieldBase", Field.class).invoke(unsafe, f);
                long off = (Long) uc.getMethod("staticFieldOffset", Field.class).invoke(unsafe, f);
                uc.getMethod("putObjectVolatile", Object.class, long.class, Object.class).invoke(unsafe, base, off, v);
            } else f.set(null, v);
            adopted++;
        }
        out.add("ran " + name + (adopted > 0 ? " (" + adopted + " new field(s))" : ""));
    }

    /** New items get a model id (the item renderer lists them once, at startup); creative tabs rebuild when next opened. */
    private static void fixUpClient(Class<?> mc, Object client, String[] n, Class<?> rootHolder, Field kte, List<Object[]> added, List<String> out) {
        ClassLoader l = mc.getClassLoader();
        try {
            Object items = rootHolder.getField("ITEM").get(null);
            Object models = null;
            Method put = null, variant = null;
            for (Object[] a : added) {
                if (a[0] != items) continue;
                if (models == null) {
                    Object renderer = mc.getMethod(n[8]).invoke(client);
                    models = renderer.getClass().getMethod(n[9]).invoke(renderer);
                    for (Method m : Class.forName(n[11], false, l).getMethods()) if (m.getName().equals(n[12]) && m.getParameterTypes().length == 1) variant = m;
                    for (Method m : models.getClass().getMethods()) if (m.getName().equals(n[10]) && m.getParameterTypes().length == 2) put = m;
                }
                if (put == null || variant == null) continue;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) kte.get(items)).entrySet()) {
                    Object id = e.getKey().getClass().getMethod(n[15]).invoke(e.getKey());
                    if (!a[1].equals(String.valueOf(id))) continue;
                    put.invoke(models, e.getValue().getClass().getMethod("value").invoke(e.getValue()), variant.invoke(null, id));
                }
            }
        } catch (Throwable t) { out.add("warning item models: " + cause(t)); }
        try {
            field(Class.forName(n[13], true, l), n[14]).set(null, null);   // the creative screen rebuilds when its parameters differ
        } catch (Throwable t) { out.add("warning creative tabs: " + cause(t)); }
    }

    /** "namespace:path" of an Identifier / ResourceLocation, a RegistryKey / ResourceKey, or a String ("minecraft:" if bare). */
    static String idOf(Object id, String keyToId) throws Exception {
        if (id instanceof String) return ((String) id).indexOf(':') < 0 ? "minecraft:" + id : (String) id;
        for (Method m : id.getClass().getMethods())
            if (m.getName().equals(keyToId) && m.getParameterTypes().length == 0 && m.getReturnType() != String.class) return String.valueOf(m.invoke(id));
        return String.valueOf(id);
    }

    /** The registry's entry (holder reference) for this id, or null. */
    static Object entry(Field keyToEntry, Object registry, String id, String keyToId) throws Exception {
        for (Map.Entry<?, ?> e : ((Map<?, ?>) keyToEntry.get(registry)).entrySet())
            if (id.equals(String.valueOf(e.getKey().getClass().getMethod(keyToId).invoke(e.getKey())))) return e.getValue();
        return null;
    }

    /** "block", "item"...: the registry's field name in Registries / BuiltInRegistries. */
    private static String registryName(Class<?> rootHolder, Object registry) {
        for (Field f : rootHolder.getFields()) {
            try { if (Modifier.isStatic(f.getModifiers()) && f.get(null) == registry) return f.getName().toLowerCase(Locale.ROOT); }
            catch (Throwable ignored) { }
        }
        return String.valueOf(registry);
    }

    /** A registry whose entries are intrusive holders (blocks, items, fluids, entity types, game events). */
    private static boolean intrusive(Object reg, Field keyToEntry, String typeField) {
        try {
            Map<?, ?> m = (Map<?, ?>) keyToEntry.get(reg);
            if (m.isEmpty()) return false;
            Object ref = m.values().iterator().next();
            return String.valueOf(field(ref.getClass(), typeField).get(ref)).equals("INTRUSIVE");
        } catch (Throwable t) { return false; }
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

    private static String cause(Throwable t) { return unwrap(t).toString(); }

    private static Throwable unwrap(Throwable t) {
        while (t.getCause() != null && (t instanceof java.lang.reflect.InvocationTargetException || t instanceof ExceptionInInitializerError)) t = t.getCause();
        return t;
    }

    private static Class<?> loadedClass(Instrumentation inst, String name) {
        for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(name) && c.getClassLoader() != null) return c;
        return null;
    }

    private RuntimeRegister() {}
}
