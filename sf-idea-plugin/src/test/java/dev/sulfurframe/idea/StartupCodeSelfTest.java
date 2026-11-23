package dev.sulfurframe.idea;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.*;

/**
 * The decisions behind running changed startup code again (0.3.0), on classes compiled here in memory: which change is
 * run again and how (registration copy, enum, switch map, entry point) or left to a restart; which hierarchy changes
 * are held back; what a mixin targets; what a mixins.json change adds. The game side (does it work in Minecraft) is
 * the sandbox self-test's job; this catches a decision that changes by accident.
 *   java -cp out dev.sulfurframe.idea.StartupCodeSelfTest
 */
public class StartupCodeSelfTest {
    static final List<String> failures = new ArrayList<>();

    // stand-ins with the names the decisions look for
    static final String REGISTRY = "package net.minecraft.registry; public interface Registry<T> { static <T> T register(Registry<? super T> r, String id, T v) { return v; } }";
    static final String MIXIN = "package org.spongepowered.asm.mixin; public @interface Mixin { Class<?>[] value() default {}; String[] targets() default {}; }";

    public static void main(String[] a) throws Exception {
        // registration code: a holder whose static initializer calls Registry.register
        String holder = "package t; import net.minecraft.registry.Registry; public class Blocks {"
                + " static Registry<Object> R; public static final Object A = Registry.register(R, \"a\", new Object()); %s"
                + " static void register() { %s } static Object helper(String id) { return Registry.register(R, id, new Object()); } }";
        var b1 = compile("t.Blocks", String.format(holder, "", ""), REGISTRY);
        var b2 = compile("t.Blocks", String.format(holder, "public static final Object B = Registry.register(R, \"b\", new Object());", ""), REGISTRY);
        expect("new field registered in the static initializer -> copy runs <clinit> and the register method", plan(b1, b2, "t/Blocks"), "statics=[<clinit>, register] entries=[] mode=");
        var b3 = compile("t.Blocks", String.format(holder, "", "helper(\"c\");"), REGISTRY);
        expect("register() changed -> copy runs <clinit> and register", plan(b1, b3, "t/Blocks"), "statics=[<clinit>, register] entries=[] mode=");

        // static change in a class that registers nothing: nothing to run again, a restart
        String plain = "package t; public class Plain { static int N = %s; static void initialize() { N++; } }";
        expect("static initializer without Registry.register -> restart", plan(compile("t.Plain", String.format(plain, "1")), compile("t.Plain", String.format(plain, "2")), "t/Plain"), "null");

        // entry point: onInitialize changed, the project classes it calls are owners
        String entry = "package t; public class Mod { public void onInitialize() { Helper.go(); %s } } class Helper { static void go() {} }";
        var e1 = compile("t.Mod", String.format(entry, ""));
        var e2 = compile("t.Mod", String.format(entry, "System.out.println(1);"));
        var ep = StartupCode.rerunPlan(e1.get("t/Mod"), e2.get("t/Mod"));
        expect("onInitialize changed -> entry point run again", ep == null ? null : ep.entries() + " " + ep.mode(), "[onInitialize] ");
        expect("onInitialize's called classes include its helper", ep != null && ep.called().contains("t/Helper"), true);

        // an instance register method: no object to run it on
        String inst = "package t; import net.minecraft.registry.Registry; public class Reg { Registry<Object> r; void register() { Registry.register(r, \"%s\", new Object()); } }";
        expect("instance register method changed -> restart", plan(compile("t.Reg", String.format(inst, "a"), REGISTRY), compile("t.Reg", String.format(inst, "b"), REGISTRY), "t/Reg"), "null");

        // enum: a constant added; a switch over it (javac's $SwitchMap$ holder) computed again
        String kind = "package t; public enum Kind { A, B%s }";
        String user = "package t; public class Use { static String of(Kind k) { switch (k) { case A: return \"a\"; case B: return \"b\"; %s default: return \"?\"; } } }";
        var k1 = compile("t.Use", String.format(user, ""), String.format(kind, ""));
        var k2 = compile("t.Use", String.format(user, "case C: return \"c\";"), String.format(kind, ", C"));
        expect("enum constant added -> enum mode", plan(k1, k2, "t/Kind"), "statics=[<clinit>] entries=[] mode=enum");
        expect("switch over the enum changed -> switchmap mode", plan(k1, k2, "t/Use$1"), "statics=[<clinit>] entries=[] mode=switchmap");

        // hierarchy: what changed, and what is held back
        String h = "package t; public class H %s { }";
        var h0 = compile("t.H", String.format(h, ""), "package t; public interface Face {}", "package t; public class Base {}");
        var h1 = compile("t.H", String.format(h, "implements Face"), "package t; public interface Face {}", "package t; public class Base {}");
        var h2 = compile("t.H", String.format(h, "extends Base"), "package t; public interface Face {}", "package t; public class Base {}");
        var h3 = compile("t.H", "package t; public class H implements java.util.function.Supplier<String> { public String get() { return null; } }");
        expect("interface added", StartupCode.supertypeChanges(h0.get("t/H"), h1.get("t/H")), Set.of("t/Face"));
        expect("superclass changed", StartupCode.supertypeChanges(h0.get("t/H"), h2.get("t/H")), new TreeSet<>(Set.of("java/lang/Object", "t/Base")));
        Set<String> own = Set.of("t/H", "t/Face", "t/Base");
        expect("project types only -> sent", Hotswapper.foreignSupertypes(StartupCode.supertypeChanges(h0.get("t/H"), h2.get("t/H")), own), List.of());
        expect("JDK interface -> held back", Hotswapper.foreignSupertypes(StartupCode.supertypeChanges(h0.get("t/H"), h3.get("t/H")), own), List.of("java.util.function.Supplier"));
        expect("no change", StartupCode.supertypeChanges(h1.get("t/H"), h1.get("t/H")), Set.of());

        // mixin targets: classes and names
        var m = compile("t.M", "package t; import org.spongepowered.asm.mixin.Mixin; @Mixin(value = String.class, targets = \"a.b.C$D\") class M {}", MIXIN);
        expect("@Mixin targets", StartupCode.mixinTargets(m.get("t/M")), List.of("java.lang.String", "a.b.C$D"));

        // mixins.json: added only, removed, other keys
        var before = json("{\"package\":\"p\",\"client\":[\"A\",\"B\"],\"injectors\":{\"defaultRequire\":1}}");
        var added = new LinkedHashMap<String, List<String>>();
        expect("mixin added to client", Hotswapper.mixinsAdded(before, json("{\"package\":\"p\",\"client\":[\"A\",\"B\",\"C\"],\"mixins\":[\"S\"],\"injectors\":{\"defaultRequire\":1}}"), Set.of(), added), null);
        expect("  what was added", added, Map.of("client", List.of("C"), "mixins", List.of("S")));
        added.clear();
        expect("already added to this game -> nothing again", Hotswapper.mixinsAdded(before, json("{\"package\":\"p\",\"client\":[\"A\",\"B\",\"C\"],\"injectors\":{\"defaultRequire\":1}}"), Set.of("client:C"), added) == null && added.isEmpty(), true);
        expect("mixin removed -> restart", Hotswapper.mixinsAdded(before, json("{\"package\":\"p\",\"client\":[\"A\"],\"injectors\":{\"defaultRequire\":1}}"), Set.of(), new HashMap<>()), "mixin B removed: restart to apply");
        expect("package changed -> restart", Hotswapper.mixinsAdded(before, json("{\"package\":\"q\",\"client\":[\"A\",\"B\"],\"injectors\":{\"defaultRequire\":1}}"), Set.of(), new HashMap<>()), "package: only added mixins apply while the game runs");

        // lambda names: javac numbers lambdas per enclosing method; one added in front of another renumbers it, and an
        // existing object of the old lambda$a$0 would run the new lambda's body. The old ones keep their names.
        String lam = "package t; public class L { public static Runnable a() { %s return () -> System.out.print(\"%s\"); }"
                + " public static Runnable b() { return () -> System.out.print(\"b\"); } }";
        String front = "Runnable z = () -> System.out.print(\"z\"); z.run();";
        byte[] l1 = compile("t.L", String.format(lam, "", "a")).get("t/L");
        byte[] l2 = compile("t.L", String.format(lam, front, "a")).get("t/L");
        expect("javac renumbered (the hazard): lambda$a$0 is now z", body(l2, "lambda$a$0").contains("ldc z"), true);
        byte[] s2 = LambdaNames.stabilize(l1, l2);
        expect("lambda added in front -> the old one keeps lambda$a$0, the new one a fresh name", sortedNames(s2), List.of("lambda$a$0", "lambda$a$sf0", "lambda$b$0"));
        expect("  lambda$a$0 still has its body", body(s2, "lambda$a$0").contains("ldc a"), true);
        expect("renamed class runs each lambda's own body (z, then a, b)", runAll(s2, "t.L", "a", "b"), "zab");
        byte[] l3 = compile("t.L", String.format(lam, "", "a2")).get("t/L");
        expect("edited lambda keeps its name (existing objects run the edit)", LambdaNames.stabilize(l1, l3) == l3, true);
        byte[] l4 = compile("t.L", String.format(lam, "", "a") + "").get("t/L");
        expect("nothing changed -> same bytes", LambdaNames.stabilize(l1, l4) == l4, true);

        if (failures.isEmpty()) System.out.println("startup code checks passed");
        else { failures.forEach(f -> System.out.println("FAIL " + f)); System.exit(1); }
    }

    private static String plan(Map<String, byte[]> before, Map<String, byte[]> after, String cls) {
        var p = StartupCode.rerunPlan(before.get(cls), after.get(cls));
        return p == null ? "null" : "statics=" + p.statics() + " entries=" + p.entries() + " mode=" + p.mode();
    }

    private static List<String> sortedNames(byte[] cls) {
        return LambdaNames.lambdas(cls).stream().map(LambdaNames.Lambda::name).sorted().toList();
    }

    private static String body(byte[] cls, String lambda) {
        return LambdaNames.lambdas(cls).stream().filter(l -> l.name().equals(lambda)).map(LambdaNames.Lambda::body).findFirst().orElse("");
    }

    /** Load the class on its own and run the Runnables its static methods return, capturing what they print. */
    private static String runAll(byte[] cls, String name, String... methods) throws Exception {
        var loader = new ClassLoader(StartupCodeSelfTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
                if (!n.equals(name)) throw new ClassNotFoundException(n);
                return defineClass(n, cls, 0, cls.length);
            }
        };
        Class<?> c = loader.loadClass(name);
        var buf = new java.io.ByteArrayOutputStream();
        var was = System.out;
        System.setOut(new java.io.PrintStream(buf, true));
        try { for (String m : methods) ((Runnable) c.getMethod(m).invoke(null)).run(); }
        finally { System.setOut(was); }
        return buf.toString();
    }

    private static com.google.gson.JsonObject json(String s) { return com.google.gson.JsonParser.parseString(s).getAsJsonObject(); }

    private static void expect(String what, Object got, Object want) {
        if (Objects.equals(got, want)) System.out.println("PASS " + what + " -> " + got);
        else failures.add(what + ": got " + got + ", want " + want);
    }

    /** Compile the sources (the first is named {@code main}) in memory; internal name -> class bytes. */
    static Map<String, byte[]> compile(String main, String... sources) {
        var javac = ToolProvider.getSystemJavaCompiler();
        var out = new HashMap<String, ByteArrayOutputStream>();
        var units = new ArrayList<JavaFileObject>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i];
            String name = i == 0 ? main : name(src);
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

    /** "package p; ... class|interface|enum|@interface Name" -> "p.Name". */
    private static String name(String src) {
        var m = java.util.regex.Pattern.compile("package\\s+([\\w.]+);.*?(?:class|interface|enum)\\s+(\\w+)", java.util.regex.Pattern.DOTALL).matcher(src);
        if (!m.find()) throw new IllegalArgumentException(src);
        return m.group(1) + "." + m.group(2);
    }
}
