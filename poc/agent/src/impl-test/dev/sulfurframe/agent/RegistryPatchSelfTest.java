package dev.sulfurframe.agent;

import dev.sulfurframe.agent.impl.RegistryPatch;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.*;
import java.util.function.Function;

/**
 * The bytecode RegistryPatch writes, loaded into this JVM (its verifier checks the frames) and run: the load-time
 * Registry and key binding hooks, the renamed registration copy with its bridged Registry.register calls, and the enum
 * initializer copy defined as a hidden nestmate (the new constant made through the private constructor). Plus the
 * rules that say which listener or key binding belongs to a mod. Stand-in classes carry the names the patches look for.
 *   ./gradlew :agent:runRegistryPatchSelfTest
 */
public class RegistryPatchSelfTest {
    static final List<String> failures = new ArrayList<>();
    static final List<String> hookCalls = new ArrayList<>();

    static final String REGISTRY = "package net.minecraft.registry; public interface Registry<T> {"
            + " static <T> T register(Registry<? super T> r, String id, T v) { return v; } }";

    public static void main(String[] a) throws Throwable {
        // ---- Registry hook, patched as the class loads
        var reg = compile(REGISTRY);
        byte[] hooked = RegistryPatch.loadTimeHooks(s -> { }).transform(null, "net/minecraft/registry/Registry", null, null, reg.get("net/minecraft/registry/Registry"));
        expect("Registry.register patched as it loads", hooked != null, true);
        var l1 = new Loader(Map.of("net.minecraft.registry.Registry", hooked));
        var register = l1.loadClass("net.minecraft.registry.Registry").getMethod("register",
                l1.loadClass("net.minecraft.registry.Registry"), String.class, Object.class);
        expect("no hook: registers as before", register.invoke(null, null, "x", "v"), "v");
        withHook(() -> {
            expect("hook: an id that is there -> the existing entry", register.invoke(null, null, "old", "new object"), "existing");
            expect("hook: a new id -> registered as before", register.invoke(null, null, "new", "fresh"), "fresh");
            return null;
        });

        // ---- key binding hook: Fabric refuses once the options exist; while a run lasts the hook takes the binding
        var kb = compile("package net.fabricmc.fabric.impl.client.keybinding; public class KeyBindingRegistryImpl {"
                + " public static Object registerKeyBinding(Object b) { throw new IllegalStateException(\"GameOptions has already been initialised\"); } }");
        String kbName = "net/fabricmc/fabric/impl/client/keybinding/KeyBindingRegistryImpl";
        var l2 = new Loader(Map.of(kbName.replace('/', '.'), RegistryPatch.loadTimeHooks(s -> { }).transform(null, kbName, null, null, kb.get(kbName))));
        var registerKey = l2.loadClass(kbName.replace('/', '.')).getMethod("registerKeyBinding", Object.class);
        String refused;
        try { registerKey.invoke(null, "k"); refused = "taken"; } catch (java.lang.reflect.InvocationTargetException e) { refused = e.getCause().getMessage(); }
        expect("no hook: Fabric's refusal stays", refused, "GameOptions has already been initialised");
        System.getProperties().put(RegistryPatch.KEY_HOOK, (Function<Object, Object>) b -> b + " taken");
        try { expect("hook: binding taken", registerKey.invoke(null, "k"), "k taken"); }
        finally { System.getProperties().remove(RegistryPatch.KEY_HOOK); }

        // ---- entity renderer guard: an entity without a renderer is not drawn instead of crashing the game
        var disp = compile("package net.minecraft.client.render.entity; public class EntityRenderDispatcher {"
                + " public Object getRenderer(Object e) { return null; }"
                + " public boolean shouldRender(Object e, Object frustum, double x, double y, double z) { return getRenderer(e).hashCode() > 0; }"
                + " public void render(Object e, double x, double y, double z, float a, float b, Object m, Object v, int light) { getRenderer(e).toString(); } }");
        String dn = "net/minecraft/client/render/entity/EntityRenderDispatcher";
        var l5 = new Loader(Map.of(dn.replace('/', '.'), RegistryPatch.loadTimeHooks(s -> { }).transform(null, dn, null, null, disp.get(dn))));
        Class<?> dc = l5.loadClass(dn.replace('/', '.'));
        Object d = dc.getConstructor().newInstance();
        expect("no renderer -> shouldRender false, no crash", dc.getMethod("shouldRender", Object.class, Object.class, double.class, double.class, double.class)
                .invoke(d, "entity", null, 0d, 0d, 0d), false);
        String rendered;
        try { dc.getMethod("render", Object.class, double.class, double.class, double.class, float.class, float.class, Object.class, Object.class, int.class)
                .invoke(d, "entity", 0d, 0d, 0d, 0f, 0f, null, null, 0); rendered = "skipped"; }
        catch (java.lang.reflect.InvocationTargetException e) { rendered = "crashed: " + e.getCause(); }
        expect("no renderer -> render skipped, no crash", rendered, "skipped");

        // ---- freeze watch: freeze() first tells a watcher (to find who freezes registries during a run)
        var reg2 = compile("package net.minecraft.registry; public class SimpleRegistry { public boolean frozen; public SimpleRegistry freeze() { frozen = true; return this; } }");
        String rn = "net/minecraft/registry/SimpleRegistry";
        var l6 = new Loader(Map.of(rn.replace('/', '.'), RegistryPatch.loadTimeHooks(s -> { }).transform(null, rn, null, null, reg2.get(rn))));
        Class<?> rc = l6.loadClass(rn.replace('/', '.'));
        Object r1 = rc.getConstructor().newInstance();
        rc.getMethod("freeze").invoke(r1);   // no watcher: just freezes
        List<Object> seen = new ArrayList<>();
        System.getProperties().put(RegistryPatch.FREEZE_WATCH, (java.util.function.Consumer<Object>) seen::add);
        try { rc.getMethod("freeze").invoke(r1); } finally { System.getProperties().remove(RegistryPatch.FREEZE_WATCH); }
        expect("freeze watch: told who froze, then froze as before", seen.size() == 1 && seen.get(0) == r1 && rc.getField("frozen").getBoolean(r1), true);

        // ---- class rescue: Fabric's class loader asks the hook for a class's bytes (the hook calls the original)
        var knot = compile("package net.fabricmc.loader.impl.launch.knot; public class KnotClassDelegate {"
                + " public int calls; private byte[] getRawClassByteArray(String name, boolean parent) throws java.io.IOException {"
                + " calls++; return name.equals(\"there\") ? new byte[]{1, 2} : null; }"
                + " public byte[] get(String name) throws java.io.IOException { return getRawClassByteArray(name, false); } }");
        String kn = "net/fabricmc/loader/impl/launch/knot/KnotClassDelegate";
        var l7 = new Loader(Map.of(kn.replace('/', '.'), RegistryPatch.loadTimeHooks(s -> { }).transform(null, kn, null, null, knot.get(kn))));
        Class<?> kc = l7.loadClass(kn.replace('/', '.'));
        Object kd = kc.getConstructor().newInstance();
        Object plain = kc.getMethod("get", String.class).invoke(kd, "there");   // no hook: the original answers
        List<String> asked = new ArrayList<>();
        System.getProperties().put(RegistryPatch.CLASS_RESCUE, (java.util.function.Function<Object[], Object>) args -> {
            asked.add((String) args[1]);
            try {
                var orig = args[0].getClass().getDeclaredMethod("sf$rawClassBytes0", String.class, boolean.class);
                orig.setAccessible(true);
                Object b = orig.invoke(args[0], args[1], args[2]);
                return b != null ? b : new byte[]{9};   // the rescue's answer for a missing one
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        Object viaHook, rescued;
        try { viaHook = kc.getMethod("get", String.class).invoke(kd, "there"); rescued = kc.getMethod("get", String.class).invoke(kd, "gone"); }
        finally { System.getProperties().remove(RegistryPatch.CLASS_RESCUE); }
        expect("class rescue: original without the hook, the hook's answer with it", java.util.Arrays.equals((byte[]) plain, new byte[]{1, 2})
                && java.util.Arrays.equals((byte[]) viaHook, new byte[]{1, 2}) && java.util.Arrays.equals((byte[]) rescued, new byte[]{9})
                && asked.equals(List.of("there", "gone")) && kc.getField("calls").getInt(kd) == 3, true);

        // ---- renamed registration copy: its Registry.register calls go through a bridge that asks the hook
        var holder = compile("package t; import net.minecraft.registry.Registry; public class Holder {"
                + " public static final Object A = Registry.register(null, \"old\", new Object());"
                + " public static final Object B = Registry.register(null, \"new\", \"fresh\");"
                + " static Object helper(String id) { return Registry.register(null, id, \"h\"); } }", REGISTRY);
        byte[] shadow = RegistryPatch.shadow(holder.get("t/Holder"), "t/Holder$SfRegister1");
        var l3 = new Loader(Map.of("net.minecraft.registry.Registry", reg.get("net/minecraft/registry/Registry"), "t.Holder$SfRegister1", shadow));
        withHook(() -> {
            Class<?> c = Class.forName("t.Holder$SfRegister1", true, l3);
            expect("copy: an id that is there -> the existing entry", c.getField("A").get(null), "existing");
            expect("copy: a new id -> registered", c.getField("B").get(null), "fresh");
            expect("copy: both calls asked the hook", new ArrayList<>(hookCalls), List.of("register old", "register new"));
            return null;
        });

        // ---- enum: the changed initializer in a hidden nestmate copy makes the new constant with the private constructor
        var oldKind = compile("package t; public enum Kind { A, B }");
        var newKind = compile("package t; public enum Kind { A, B, C }");
        var l4 = new Loader(Map.of("t.Kind", oldKind.get("t/Kind")));
        Class<?> kind = l4.loadClass("t.Kind");
        Class<?> copy = hiddenNestmate(kind, RegistryPatch.clinitCopy(newKind.get("t/Kind"), "t/Kind$SfInit1"));
        Object c = staticOf(copy, "C");   // the copy is not public: read like ClinitReplay does
        expect("enum copy: new constant made, of the real enum class", c != null && c.getClass() == kind, true);
        expect("enum copy: its name and declared ordinal", ((Enum<?>) c).name() + " " + ((Enum<?>) c).ordinal(), "C 2");
        Object[] values = (Object[]) staticOf(copy, "$VALUES");
        expect("enum copy: its own $VALUES (via its copied $values)", values.length, 3);

        // ---- whose listener / key binding is it
        Runnable lambda = () -> { };
        expect("a lambda belongs to the class it was written in", EntryRerun.owned(lambda, List.of(RegistryPatchSelfTest.class.getName())), true);
        expect("a lambda does not belong to a class with a shorter name", EntryRerun.owned(lambda, List.of("dev.sulfurframe.agent.Registry")), false);
        expect("anonymous class belongs to its outer class", EntryRerun.owned(new Object() { }, List.of(RegistryPatchSelfTest.class.getName())), true);
        expect("key binding id with the mod id", EntryRerun.mine("key.showcase.sf_test", "showcase"), true);
        expect("category with the mod id", EntryRerun.mine("category.showcase", "showcase"), true);
        expect("another mod whose id starts the same", EntryRerun.mine("key.showcasey.x", "showcase"), false);

        if (failures.isEmpty()) System.out.println("registry patch checks passed");
        else { failures.forEach(f -> System.out.println("FAIL " + f)); System.exit(1); }
    }

    interface Body { Object run() throws Throwable; }

    /** With the registration hook in place: "old" is there (answered "existing"), anything else is new. */
    private static void withHook(Body body) throws Throwable {
        hookCalls.clear();
        System.getProperties().put(RegistryPatch.HOOK_KEY, (Function<Object[], Object>) call -> {
            hookCalls.add(call[0] + " " + call[2]);
            return "old".equals(call[2]) ? "existing" : null;
        });
        try { body.run(); } finally { System.getProperties().remove(RegistryPatch.HOOK_KEY); }
    }

    /** As the agent does it: the JDK's trusted lookup narrowed to the class (privateLookupIn cannot define hidden classes). */
    private static Class<?> hiddenNestmate(Class<?> host, byte[] bytes) throws Throwable {
        Class<?> uc = Class.forName("sun.misc.Unsafe");
        Field uf = uc.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Object u = uf.get(null);
        Field impl = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        Object base = uc.getMethod("staticFieldBase", Field.class).invoke(u, impl);
        long off = (Long) uc.getMethod("staticFieldOffset", Field.class).invoke(u, impl);
        MethodHandles.Lookup trusted = (MethodHandles.Lookup) uc.getMethod("getObject", Object.class, long.class).invoke(u, base, off);
        return trusted.in(host).defineHiddenClass(bytes, true, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
    }

    private static Object staticOf(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void expect(String what, Object got, Object want) {
        if (Objects.equals(got, want)) System.out.println("PASS " + what + " -> " + got);
        else failures.add(what + ": got " + got + ", want " + want);
    }

    /** Defines the given classes itself (its own copies), everything else from the parent. */
    static final class Loader extends ClassLoader {
        final Map<String, byte[]> classes;
        Loader(Map<String, byte[]> classes) { super(RegistryPatchSelfTest.class.getClassLoader()); this.classes = classes; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null && classes.containsKey(name)) { byte[] b = classes.get(name); c = defineClass(name, b, 0, b.length); }
                if (c == null) c = super.loadClass(name, false);
                return c;
            }
        }
    }

    /** Compile the sources in memory; internal name -> class bytes. */
    static Map<String, byte[]> compile(String... sources) {
        var javac = ToolProvider.getSystemJavaCompiler();
        var out = new HashMap<String, ByteArrayOutputStream>();
        var units = new ArrayList<JavaFileObject>();
        for (String src : sources) {
            var m = java.util.regex.Pattern.compile("package\\s+([\\w.]+);.*?(?:class|interface|enum)\\s+(\\w+)", java.util.regex.Pattern.DOTALL).matcher(src);
            if (!m.find()) throw new IllegalArgumentException(src);
            String name = m.group(1) + "." + m.group(2);
            units.add(new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore) { return src; }
            });
        }
        var fm = new ForwardingJavaFileManager<>(javac.getStandardFileManager(null, null, null)) {
            @Override public JavaFileObject getJavaFileForOutput(Location l, String className, JavaFileObject.Kind k, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + ".class"), k) {
                    @Override public OutputStream openOutputStream() { var b = new ByteArrayOutputStream(); out.put(className.replace('.', '/'), b); return b; }
                };
            }
        };
        var diag = new DiagnosticCollector<JavaFileObject>();
        if (!javac.getTask(null, fm, diag, List.of("--release", "21", "-proc:none"), null, units).call())
            throw new IllegalStateException("compile failed: " + diag.getDiagnostics());
        var bytes = new HashMap<String, byte[]>();
        out.forEach((k, v) -> bytes.put(k, v.toByteArray()));
        return bytes;
    }
}
