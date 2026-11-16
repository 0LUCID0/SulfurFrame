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

    /** name -> value of every static final field with a ConstantValue. */
    static Map<String, Object> constants(byte[] cls) {
        var out = new TreeMap<String, Object>();
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if (value != null && (access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0) out.put(name, value);
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
    static Map<String, String> startupBodies(byte[] cls) {
        var bodies = new LinkedHashMap<String, String>();
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!startupMethod(name)) return null;
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
