package dev.sulfurframe.idea;

import org.jetbrains.org.objectweb.asm.*;

import java.util.*;

/**
 * Did a hotswap change code that only runs when the game starts?
 *
 * Redefining a class swaps its methods, but code that already ran stays run: static initializers, constructors of
 * objects that already exist, a mod's onInitialize / constructor, and the register-everything methods they call
 * (new blocks, items, entities go into registries that are frozen after startup). The swap "works" and nothing
 * changes in the game, which is confusing; so those changes are detected and a restart is offered.
 *
 * Compares instructions only (no line numbers, no frames), so moving code around or editing comments does not count.
 * Uses the ASM bundled with IntelliJ.
 */
final class StartupCode {
    /**
     * Methods that run once at startup, by name: static initializers (ModBlocks.FOO = register(...)), the mod entry
     * points (onInitialize, onInitializeClient...) and the usual register/initialize helpers they call. Constructors
     * are left out: most objects are created again later and pick up the new code (a block's settings live in the
     * register call, which is covered).
     */
    static boolean startupMethod(String name) {
        return name.equals("<clinit>") || name.startsWith("onInitialize") || name.startsWith("register") || name.startsWith("initialize");
    }

    /** "Class.method" for every startup method whose code differs between the two versions of a class. */
    static List<String> changed(byte[] before, byte[] after) {
        if (before == null || after == null) return List.of();
        Map<String, String> a = startupBodies(before), b = startupBodies(after);
        var out = new ArrayList<String>();
        String cls = simpleName(after);
        for (var e : b.entrySet()) {
            String old = a.get(e.getKey());
            if (!e.getValue().equals(old)) out.add(cls + "." + e.getKey().substring(0, e.getKey().indexOf('(')));
        }
        return out.stream().distinct().toList();
    }

    /**
     * How a startup change can be applied in the running game (the agent's RuntimeRegister), or null if it cannot:
     *   statics  the static initializer ("&lt;clinit&gt;") and changed static no-argument register / initialize methods, run
     *            again in a renamed copy; only for a class that calls Registry.register itself (the copy keeps what
     *            is registered and adds what is new)
     *   entries  changed entry points (onInitialize, onInitializeClient, onInitializeServer), run again on the mod's
     *            entry point object after what they hooked up before is taken out
     *   called   classes the entry points call (internal names), whose earlier registrations are taken out as well
     *            (the caller keeps only the project's own classes)
     *   mode     "" (registration code: renamed copy), "enum" (new constants from the changed static initializer),
     *            "switchmap" (javac's holder of a switch over an enum: its arrays computed again) or "newstatics" (new
     *            static fields take the values the changed initializer gives them; statics then lists their names after
     *            "&lt;clinit&gt;"), see the agent's ClinitReplay
     */
    record Rerun(List<String> statics, List<String> entries, Set<String> called, String mode) {}

    static Rerun rerunPlan(byte[] before, byte[] after) {
        if (before == null || after == null) return null;
        Map<String, String> a = startupBodies(before), b = startupBodies(after);
        var access = new HashMap<String, Integer>();
        var calledByEntry = new LinkedHashSet<String>();
        boolean[] registers = {false}, isEnum = {false}, switchMap = {false};
        new ClassReader(after).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int acc, String name, String sig, String sup, String[] ifs) { isEnum[0] = (acc & Opcodes.ACC_ENUM) != 0; }
            @Override public FieldVisitor visitField(int acc, String name, String desc, String sig, Object value) {
                if ((acc & Opcodes.ACC_STATIC) != 0 && name.startsWith("$SwitchMap$")) switchMap[0] = true;
                return null;
            }
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                access.put(name + desc, acc);
                boolean entry = entryPoint(name, desc);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if (registryCall(owner, n)) registers[0] = true;
                        if (entry) calledByEntry.add(owner);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!b.getOrDefault("<clinit>()V", "").equals(a.getOrDefault("<clinit>()V", ""))) {
            if (isEnum[0]) return new Rerun(List.of("<clinit>"), List.of(), Set.of(), "enum");
            if (switchMap[0]) return new Rerun(List.of("<clinit>"), List.of(), Set.of(), "switchmap");
            // new static fields the static initializer sets: the redefinition leaves them null (a crash on first use);
            // registration classes get theirs from the shadow run below
            if (!registers[0]) {
                var added = new ArrayList<>(initializedStatics(after));
                added.removeAll(initializedStatics(before));
                if (!added.isEmpty()) {
                    var statics = new ArrayList<String>(List.of("<clinit>"));
                    statics.addAll(added);
                    return new Rerun(statics, List.of(), Set.of(), "newstatics");
                }
            }
        }
        var statics = new ArrayList<String>();
        var entries = new ArrayList<String>();
        boolean staticChange = false;
        for (var e : b.entrySet()) {
            if (e.getValue().equals(a.get(e.getKey()))) continue;
            String nameDesc = e.getKey(), name = nameDesc.substring(0, nameDesc.indexOf('('));
            int acc = access.getOrDefault(nameDesc, 0);
            if (entryPoint(name, nameDesc.substring(name.length())) && (acc & Opcodes.ACC_STATIC) == 0) { entries.add(name); continue; }
            if ((acc & Opcodes.ACC_STATIC) == 0) return null;            // an instance registrar: no object to run it on
            staticChange = true;
            if (!name.equals("<clinit>") && nameDesc.endsWith("()V")) statics.add(name);   // helpers with arguments run through their callers
        }
        if (staticChange) {
            if (!registers[0]) return null;
            statics.add(0, "<clinit>");
            // the static initializer makes the objects, a register method often registers them (TILE = new Block(...)
            // then register()): run those too, changed or not, so changed settings reach the registered objects
            if (!b.getOrDefault("<clinit>()V", "").equals(a.getOrDefault("<clinit>()V", "")))
                for (var e : access.entrySet()) {
                    String nd = e.getKey(), n = nd.substring(0, nd.indexOf('('));
                    if (nd.endsWith("()V") && (e.getValue() & Opcodes.ACC_STATIC) != 0 && (n.startsWith("register") || n.startsWith("initialize"))
                            && !statics.contains(n)) statics.add(n);
                }
        }
        if (statics.isEmpty() && entries.isEmpty()) return null;
        return new Rerun(statics, entries, entries.isEmpty() ? Set.of() : calledByEntry, "");
    }

    /** Static fields without a compile-time constant value (the static initializer gives them theirs). */
    static Set<String> initializedStatics(byte[] cls) {
        var out = new TreeSet<String>();
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int acc, String name, String desc, String sig, Object value) {
                if ((acc & Opcodes.ACC_STATIC) != 0 && (acc & Opcodes.ACC_SYNTHETIC) == 0 && value == null) out.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    /** Superclass and interfaces (internal names) that were added or removed between the two versions. */
    static Set<String> supertypeChanges(byte[] before, byte[] after) {
        if (before == null || after == null) return Set.of();
        Set<String> a = supertypes(before), b = supertypes(after);
        var out = new TreeSet<String>();
        for (String s : a) if (!b.contains(s)) out.add(s);
        for (String s : b) if (!a.contains(s)) out.add(s);
        return out;
    }

    private static Set<String> supertypes(byte[] cls) {
        var r = new ClassReader(cls);
        var out = new HashSet<String>(Arrays.asList(r.getInterfaces()));
        if (r.getSuperName() != null) out.add(r.getSuperName());
        return out;
    }

    /**
     * Internal names of the interfaces a mixin puts on its targets: the mixin itself if it is an interface (an accessor
     * or invoker), else the interfaces it implements. Code that casts to one breaks once the mixin is taken out.
     */
    static List<String> mixinInterfaces(byte[] cls) {
        var r = new ClassReader(cls);
        if ((r.getAccess() & Opcodes.ACC_INTERFACE) != 0) return List.of(r.getClassName());
        return List.of(r.getInterfaces());
    }

    /** Binary names of the classes a mixin class targets (@Mixin value = classes, targets = names). */
    static List<String> mixinTargets(byte[] cls) {
        var out = new ArrayList<String>();
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public void visit(String n, Object value) {
                                if (value instanceof Type type) out.add(type.getClassName());
                                else if (value instanceof String s) out.add(s.replace('/', '.'));
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    static boolean entryPoint(String name, String desc) {
        return desc.equals("()V") && (name.equals("onInitialize") || name.equals("onInitializeClient") || name.equals("onInitializeServer"));
    }

    /**
     * Registry.register / registerReference (Yarn and Mojang names): the calls the agent's shadow copy bridges so a
     * second run keeps what is registered. Other routes (vanilla Blocks/Items helpers, a registry's add) would throw
     * on the ids that are already there, so those changes still ask for a restart.
     */
    static boolean registryCall(String owner, String name) {
        return owner.endsWith("/Registry") && (name.equals("register") || name.equals("registerReference"));
    }

    /**
     * Did a compile-time constant change (a static final primitive or String with a value: javac copies it into every
     * class that reads it)? Returns the simple names the class is referred to by in source (Outer and Inner for
     * Outer$Inner), or an empty list. The users need recompiling, or they keep the old value after a swap.
     */
    static List<String> constantOwnerNames(byte[] before, byte[] after) {
        if (before == null || after == null || constants(before).equals(constants(after))) return List.of();
        String n = new ClassReader(after).getClassName();
        n = n.substring(n.lastIndexOf('/') + 1);
        return Arrays.stream(n.split("\\$")).filter(s -> !s.isEmpty() && !Character.isDigit(s.charAt(0))).distinct().toList();
    }

    /**
     * The class's simple names when a method or field other classes can use (not private) is gone or changed its
     * descriptor or static-ness: their callers, compiled against the old one, would fail at run time
     * (NoSuchMethodError / NoSuchFieldError) unless they are compiled again. Empty when nothing like that changed.
     */
    static List<String> apiOwnerNames(byte[] before, byte[] after) {
        if (before == null || after == null) return List.of();
        var now = members(after);
        for (String m : members(before)) if (!now.contains(m)) return simpleNames(after);
        return List.of();
    }

    private static List<String> simpleNames(byte[] cls) {
        String n = simpleName(cls);
        return Arrays.stream(n.split("\\$")).filter(s -> !s.isEmpty() && !Character.isDigit(s.charAt(0))).distinct().toList();
    }

    /** "m name desc static?" / "f name desc static?" of every non-private, non-synthetic member. */
    static Set<String> members(byte[] cls) {
        var out = new TreeSet<String>();
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == 0) out.add("f " + name + " " + desc + " " + ((access & Opcodes.ACC_STATIC) != 0));
                return null;
            }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0 && !name.equals("<clinit>"))
                    out.add("m " + name + " " + desc + " " + ((access & Opcodes.ACC_STATIC) != 0));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    /** name -> value of every non-private static final field with a ConstantValue (a private one has no other readers). */
    static Map<String, Object> constants(byte[] cls) {
        var out = new TreeMap<String, Object>();
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if (value != null && (access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0
                        && (access & Opcodes.ACC_PRIVATE) == 0) out.put(name, value);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    private static String simpleName(byte[] cls) {
        String n = new ClassReader(cls).getClassName();
        return n.substring(n.lastIndexOf('/') + 1);
    }

    /** name+descriptor -> a textual form of the instructions. */
    static Map<String, String> startupBodies(byte[] cls) { return bodies(cls, StartupCode::startupMethod); }

    /** name+descriptor -> a textual form of the instructions, for the methods whose name passes {@code which}. */
    static Map<String, String> bodies(byte[] cls, java.util.function.Predicate<String> which) {
        var bodies = new LinkedHashMap<String, String>();
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!which.test(name)) return null;
                var sb = new StringBuilder();
                bodies.put(name + desc, "");
                return new MethodVisitor(Opcodes.ASM9) {
                    final Map<Label, Integer> labels = new HashMap<>();
                    int label(Label l) { return labels.computeIfAbsent(l, k -> labels.size()); }
                    @Override public void visitInsn(int op) { sb.append(op).append(';'); }
                    @Override public void visitIntInsn(int op, int v) { sb.append(op).append(' ').append(v).append(';'); }
                    @Override public void visitVarInsn(int op, int v) { sb.append(op).append(" v").append(v).append(';'); }
                    @Override public void visitTypeInsn(int op, String t) { sb.append(op).append(' ').append(t).append(';'); }
                    @Override public void visitFieldInsn(int op, String o, String n, String d) { sb.append(op).append(' ').append(o).append('.').append(n).append(';'); }
                    @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) { sb.append(op).append(' ').append(o).append('.').append(n).append(d).append(';'); }
                    @Override public void visitInvokeDynamicInsn(String n, String d, Handle h, Object... args) { sb.append("indy ").append(n).append(d).append(';'); }
                    @Override public void visitJumpInsn(int op, Label l) { sb.append(op).append(" L").append(label(l)).append(';'); }
                    @Override public void visitLabel(Label l) { sb.append('L').append(label(l)).append(':'); }
                    @Override public void visitLdcInsn(Object v) { sb.append("ldc ").append(v).append(';'); }
                    @Override public void visitIincInsn(int v, int inc) { sb.append("iinc ").append(v).append(' ').append(inc).append(';'); }
                    @Override public void visitTableSwitchInsn(int min, int max, Label d, Label... ls) { sb.append("tswitch ").append(min).append('-').append(max).append(';'); }
                    @Override public void visitLookupSwitchInsn(Label d, int[] keys, Label[] ls) { sb.append("lswitch ").append(Arrays.toString(keys)).append(';'); }
                    @Override public void visitMultiANewArrayInsn(String d, int dims) { sb.append("multianew ").append(d).append(dims).append(';'); }
                    @Override public void visitEnd() { bodies.put(name + desc, sb.toString()); }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return bodies;
    }

    private StartupCode() {}
}
