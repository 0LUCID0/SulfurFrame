package dev.sulfurframe.agent.impl;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * Bytecode for registering new entries while the game runs (see the agent's RuntimeRegister).
 *
 * shadow() makes a copy of a class under another name, so its static initializer and register methods can run once
 * more without touching the loaded class (whose statics already hold the registered objects). In the copy, every
 * Registry.register / registerReference call goes through a bridge method that first asks a hook "is this id already
 * registered?": the hook answers with the existing entry, so the re-run keeps what is there and only adds what is new.
 *
 * The registry class itself is not touched: retransforming it on the JetBrains Runtime (enhanced redefinition) reset
 * the fields Fabric API's mixins add to every registry, and the game crashed. Like LwjglTransformer, the inserted code
 * never names an agent class (Fabric's Knot refuses them): the hook is a JDK Function in a system property, present
 * only while a re-run is going on.
 */
public final class RegistryPatch {
    public static final String HOOK_KEY = "dev.sulfurframe.registry";

    private RegistryPatch() {}

    /** The class renamed to {@code newInternalName}, registry calls bridged, nest attributes dropped. */
    public static byte[] shadow(byte[] cls, final String newInternalName) {
        ClassReader r = new ClassReader(cls);
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        final List<String[]> bridges = new ArrayList<String[]>();   // {bridge name, owner, method, descriptor}
        ClassVisitor bridged = new ClassVisitor(Opcodes.ASM9, w) {
            @Override public void visitNestHost(String host) { }
            @Override public void visitNestMember(String member) { }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, ex)) {
                    @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC && bridgeable(owner, n, d)) {
                            String b = null;
                            for (String[] e : bridges) if (e[1].equals(owner) && e[2].equals(n) && e[3].equals(d)) b = e[0];
                            if (b == null) { b = "sf$register" + bridges.size(); bridges.add(new String[]{b, owner, n, d}); }
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, newInternalName, b, d, false);
                        } else super.visitMethodInsn(op, owner, n, d, itf);
                    }
                };
            }
            @Override public void visitEnd() {
                for (String[] e : bridges) bridge(cv, e[0], e[1], e[2], e[3]);
                super.visitEnd();
            }
        };
        r.accept(new ClassRemapper(bridged, new SimpleRemapper(r.getClassName(), newInternalName)), 0);
        return w.toByteArray();
    }

    /** Set (to "true") once the Registry interface was patched as it loaded: re-running whole entry points relies on it. */
    public static final String HOOKED_KEY = "dev.sulfurframe.registryHooked";

    /**
     * For a game started with the agent (premain): as the Registry interface loads, its static register /
     * registerReference methods first ask the same hook as the shadow's bridges. Code run again anywhere (an entry
     * point and the helpers it calls) then keeps what is registered. Load time only: the class arrives here with Fabric
     * API's mixins already applied, and nothing is retransformed (on the JetBrains Runtime that reset mixin fields).
     */
    public static ClassFileTransformer loadTimeHooks(final java.util.function.Consumer<String> log) {
        return new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
                if (redefined != null || name == null || !(name.equals("net/minecraft/registry/Registry") || name.equals("net/minecraft/core/Registry"))) return null;
                try {
                    ClassReader r = new ClassReader(bytes);
                    ClassWriter w = new ClassWriter(r, ClassWriter.COMPUTE_MAXS);
                    final int[] patched = {0};
                    r.accept(new ClassVisitor(Opcodes.ASM9, w) {
                        @Override public MethodVisitor visitMethod(int access, final String m, final String desc, String sig, String[] ex) {
                            MethodVisitor mv = super.visitMethod(access, m, desc, sig, ex);
                            if ((access & Opcodes.ACC_STATIC) == 0 || !bridgeable(name, m, desc)) return mv;
                            patched[0]++;
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override public void visitCode() {
                                    super.visitCode();
                                    Type[] a = Type.getArgumentTypes(desc);
                                    Label skip = new Label();
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                                    mv.visitLdcInsn(HOOK_KEY);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    mv.visitInsn(Opcodes.DUP);
                                    mv.visitJumpInsn(Opcodes.IFNULL, skip);
                                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function");
                                    mv.visitInsn(Opcodes.ICONST_4);
                                    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                                    mv.visitInsn(Opcodes.DUP); mv.visitInsn(Opcodes.ICONST_0); mv.visitLdcInsn(m); mv.visitInsn(Opcodes.AASTORE);
                                    for (int i = 0; i < 3; i++) {
                                        mv.visitInsn(Opcodes.DUP); mv.visitInsn(Opcodes.ICONST_1 + i); mv.visitVarInsn(Opcodes.ALOAD, i); mv.visitInsn(Opcodes.AASTORE);
                                    }
                                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                                    mv.visitInsn(Opcodes.DUP);
                                    mv.visitJumpInsn(Opcodes.IFNULL, skip);
                                    mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getReturnType(desc).getInternalName());
                                    mv.visitInsn(Opcodes.ARETURN);
                                    mv.visitLabel(skip);
                                    mv.visitFrame(Opcodes.F_FULL, 3, new Object[]{a[0].getInternalName(), a[1].getInternalName(), a[2].getInternalName()},
                                            1, new Object[]{"java/lang/Object"});
                                    mv.visitInsn(Opcodes.POP);
                                }
                            };
                        }
                    }, 0);
                    if (patched[0] == 0) return null;
                    System.setProperty(HOOKED_KEY, "true");
                    log.accept("runtime registration: " + patched[0] + " register method(s) of " + name.replace('/', '.') + " hooked as it loaded");
                    return w.toByteArray();
                } catch (Throwable t) {
                    log.accept("runtime registration: could not hook " + name + ": " + t);
                    return null;
                }
            }
        };
    }

    /** Registry.register(registry, id-or-key, value) and Registry.registerReference(...): three objects in, one out. */
    static boolean bridgeable(String owner, String name, String desc) {
        if (!owner.endsWith("/Registry") || !(name.equals("register") || name.equals("registerReference"))) return false;
        Type[] a = Type.getArgumentTypes(desc);
        return a.length == 3 && a[0].getSort() == Type.OBJECT && a[1].getSort() == Type.OBJECT && a[2].getSort() == Type.OBJECT
                && Type.getReturnType(desc).getSort() == Type.OBJECT;
    }

    /**
     * private static R bridge(A a, B b, C c) {
     *     Object h = System.getProperties().get(HOOK_KEY);
     *     if (h != null) { Object r = ((Function) h).apply(new Object[]{name, a, b, c}); if (r != null) return (R) r; }
     *     return Registry.name(a, b, c);
     * }
     */
    private static void bridge(ClassVisitor cv, String bridge, String owner, String name, String desc) {
        Type[] a = Type.getArgumentTypes(desc);
        Type ret = Type.getReturnType(desc);
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, bridge, desc, null, null);
        mv.visitCode();
        Label original = new Label();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
        mv.visitLdcInsn(HOOK_KEY);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitJumpInsn(Opcodes.IFNULL, original);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function");
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitInsn(Opcodes.DUP); mv.visitInsn(Opcodes.ICONST_0); mv.visitLdcInsn(name); mv.visitInsn(Opcodes.AASTORE);
        for (int i = 0; i < 3; i++) {
            mv.visitInsn(Opcodes.DUP); mv.visitInsn(Opcodes.ICONST_1 + i); mv.visitVarInsn(Opcodes.ALOAD, i); mv.visitInsn(Opcodes.AASTORE);
        }
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitJumpInsn(Opcodes.IFNULL, original);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ret.getInternalName());
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(original);
        mv.visitFrame(Opcodes.F_FULL, 4, new Object[]{a[0].getInternalName(), a[1].getInternalName(), a[2].getInternalName(), "java/lang/Object"},
                0, new Object[0]);
        for (int i = 0; i < 3; i++) mv.visitVarInsn(Opcodes.ALOAD, i);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, true);   // Registry is an interface (Yarn and Mojang)
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
