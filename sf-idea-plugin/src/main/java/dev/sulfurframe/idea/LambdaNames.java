package dev.sulfurframe.idea;

import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper;
import org.jetbrains.org.objectweb.asm.commons.SimpleRemapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps lambda method names stable across a hotswap.
 *
 * javac names a lambda's body method lambda$enclosingMethod$N with one counter for the whole class, so a lambda added
 * near the top of a class renumbers every lambda after it. A lambda object made before the swap (a listener, a stored
 * Supplier) is bound to its method by name: after the redefinition it calls a method that is gone (NoSuchMethodError)
 * or, worse, one that now holds another lambda's body. The JVM (JBR included) does not deal with that, so before the new
 * class is sent its lambda methods get the names they had:
 *   1. the same body (and descriptor) as an old lambda: that one's name
 *   2. otherwise the next old lambda of the same enclosing method and descriptor, in order: its name (an edited lambda,
 *      so existing objects run the edit)
 *   3. otherwise (a new lambda) a name no old or new lambda has, lambda$enclosing$sfN
 * Uses the ASM bundled with IntelliJ.
 */
final class LambdaNames {
    private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.*)\\$([^$]+)");

    record Lambda(String name, String enclosing, String desc, String body) {}

    /** {@code after} with its lambda methods renamed to match {@code before}; the same array if nothing changes. */
    static byte[] stabilize(byte[] before, byte[] after) {
        if (before == null || after == null) return after;
        List<Lambda> old = lambdas(before), now = lambdas(after);
        if (now.isEmpty()) return after;
        var rename = plan(old, now);
        if (rename.isEmpty()) return after;
        String owner = new ClassReader(after).getClassName();
        var mapping = new HashMap<String, String>();
        for (var l : now) if (rename.containsKey(l.name())) mapping.put(owner + "." + l.name() + l.desc(), rename.get(l.name()));
        ClassWriter w = new ClassWriter(0);
        new ClassReader(after).accept(new ClassRemapper(w, new SimpleRemapper(mapping)), 0);
        return w.toByteArray();
    }

    /** new name -> name it should have (only the ones that change). */
    static Map<String, String> plan(List<Lambda> old, List<Lambda> now) {
        var target = new LinkedHashMap<String, String>();   // new lambda name -> final name
        var usedOld = new HashSet<String>();
        for (var n : now)                                    // 1. same body
            for (var o : old)
                if (!usedOld.contains(o.name()) && o.desc().equals(n.desc()) && o.body().equals(n.body())) {
                    target.put(n.name(), o.name()); usedOld.add(o.name()); break;
                }
        for (var n : now) {                                  // 2. same place
            if (target.containsKey(n.name())) continue;
            for (var o : old)
                if (!usedOld.contains(o.name()) && o.enclosing().equals(n.enclosing()) && o.desc().equals(n.desc())) {
                    target.put(n.name(), o.name()); usedOld.add(o.name()); break;
                }
        }
        var taken = new HashSet<String>();                   // 3. new lambdas: their own name if no old lambda had it
        for (var o : old) taken.add(o.name());
        taken.addAll(target.values());
        for (var n : now) if (!target.containsKey(n.name()) && !taken.contains(n.name())) { target.put(n.name(), n.name()); taken.add(n.name()); }
        for (var n : now) taken.add(n.name());
        int k = 0;
        for (var n : now) {
            if (target.containsKey(n.name())) continue;
            String fresh;
            do fresh = "lambda$" + n.enclosing() + "$sf" + (k++); while (taken.contains(fresh));
            taken.add(fresh);
            target.put(n.name(), fresh);
        }
        var rename = new LinkedHashMap<String, String>();
        target.forEach((from, to) -> { if (!from.equals(to)) rename.put(from, to); });
        return rename;
    }

    /** The synthetic lambda body methods of a class, in order, with a textual form of their code. */
    static List<Lambda> lambdas(byte[] cls) {
        var out = new ArrayList<Lambda>();
        var bodies = StartupCode.bodies(cls, n -> LAMBDA.matcher(n).matches());
        for (var e : bodies.entrySet()) {
            String nameDesc = e.getKey(), name = nameDesc.substring(0, nameDesc.indexOf('('));
            Matcher m = LAMBDA.matcher(name);
            if (!m.matches()) continue;
            out.add(new Lambda(name, m.group(1), nameDesc.substring(name.length()), e.getValue()));
        }
        return out;
    }

    /**
     * A lambda (or a method reference to a method of the class) that the new version no longer has: objects made from
     * it before the swap (a listener, a draw callback of a screen that is open) still call its method by name, and after
     * the redefinition that is a NoSuchMethodError. So the old method goes into the new class as it was, with the
     * methods and fields of the class its code uses that the new version dropped (same name and type: the JVM keeps a
     * static field's value). They are unused by the new code and go away with the next restart's compile. Returns the
     * new class (the same array when nothing is missing); {@code kept} gets the methods put back.
     */
    static byte[] keepRemoved(byte[] before, byte[] after, List<String> kept) {
        if (before == null || after == null) return after;
        var old = Members.of(before);
        var now = Members.of(after);
        var keepMethods = new LinkedHashSet<String>();
        var keepFields = new LinkedHashSet<String>();
        var todo = new ArrayDeque<String>();
        for (String m : old.bound) if (!now.methods.containsKey(m) && old.methods.containsKey(m)) todo.add(m);
        // one kept by an earlier swap (or by an earlier pass of this one, the callers' recompile) has no call site left
        // in the old class, but the objects bound to it are still there: every lambda body counts
        for (String m : old.methods.keySet()) if (LAMBDA.matcher(m.substring(0, m.indexOf('('))).matches() && !now.methods.containsKey(m)) todo.add(m);
        while (!todo.isEmpty()) {
            String m = todo.poll();
            if (!keepMethods.add(m)) continue;
            for (String u : old.uses.getOrDefault(m, Set.of())) {
                if (u.startsWith("F ")) { String f = u.substring(2); if (!now.fields.containsKey(f) && old.fields.containsKey(f)) keepFields.add(f); }
                else if (!now.methods.containsKey(u) && old.methods.containsKey(u) && !keepMethods.contains(u)) todo.add(u);
            }
        }
        if (keepMethods.isEmpty()) return after;
        ClassWriter w = new ClassWriter(0);
        new ClassReader(after).accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override public void visitEnd() {
                for (String f : keepFields) {
                    var d = old.fields.get(f);
                    FieldVisitor fv = w.visitField(d.access, d.name, d.desc, d.signature, d.value);
                    if (fv != null) fv.visitEnd();
                }
                new ClassReader(before).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        return keepMethods.contains(name + desc) ? w.visitMethod(access, name, desc, signature, exceptions) : null;
                    }
                }, 0);
                super.visitEnd();
            }
        }, 0);
        keepMethods.forEach(m -> kept.add(m.substring(0, m.indexOf('('))));
        return w.toByteArray();
    }

    /** A class's methods and fields (name+desc), the methods its lambdas and method references are bound to, and what each method uses of its own class. */
    private static final class Members {
        record Field(int access, String name, String desc, String signature, Object value) {}
        final Map<String, Integer> methods = new HashMap<>();
        final Map<String, Field> fields = new HashMap<>();
        final Set<String> bound = new LinkedHashSet<>();
        final Map<String, Set<String>> uses = new HashMap<>();

        static Members of(byte[] cls) {
            var r = new ClassReader(cls);
            String owner = r.getClassName();
            var m = new Members();
            r.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    m.fields.put(name + desc, new Field(access, name, desc, signature, value));
                    return null;
                }
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    String self = name + desc;
                    m.methods.put(self, access);
                    var used = m.uses.computeIfAbsent(self, k -> new LinkedHashSet<>());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitFieldInsn(int op, String o, String n, String d) { if (o.equals(owner)) used.add("F " + n + d); }
                        @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) { if (o.equals(owner)) used.add(n + d); }
                        @Override public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                            boolean lambda = bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory");
                            for (Object a : args)
                                if (a instanceof Handle h && h.getOwner().equals(owner)) {
                                    String target = h.getName() + h.getDesc();
                                    if (h.getTag() >= Opcodes.H_GETFIELD && h.getTag() <= Opcodes.H_PUTSTATIC) used.add("F " + target);
                                    else { used.add(target); if (lambda) m.bound.add(target); }
                                }
                        }
                    };
                }
            }, 0);
            return m;
        }
    }

    private LambdaNames() {}
}
