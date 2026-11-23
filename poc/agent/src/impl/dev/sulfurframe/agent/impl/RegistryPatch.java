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
                if (redefined == null && KEY_BINDINGS.equals(name)) return keyBindingHook(bytes, log);
                if (redefined == null && ("net/minecraft/client/render/entity/EntityRenderDispatcher".equals(name)
                        || "net/minecraft/client/renderer/entity/EntityRenderDispatcher".equals(name))) return rendererGuard(bytes, log);
                if (redefined == null && name != null && WIDEN.containsKey(name)) {   // access widened while the game runs
                    try { return widen(bytes, WIDEN.get(name)); } catch (Throwable t) { log.accept("access widener: " + name + ": " + t); return null; }
                }
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

    /**
     * A class to define as a hidden nestmate of {@code cls}'s class: its static fields (same names and types) and its
     * static initializer, which fills those copies instead of the real ones. Methods the initializer calls on the class
     * itself stay calls into the real class, except the enum's $values(), which is copied too (it reads the copies).
     * Being a nestmate, the copy may call the real class's private constructor: that is how new enum constants are
     * made (reflection refuses to create enum objects). Used for enums and for javac's switch map holders, whose
     * static initializers already ran with the old set of constants.
     */
    public static byte[] clinitCopy(byte[] cls, final String copyName) {
        final ClassReader r = new ClassReader(cls);
        final String real = r.getClassName();
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        final List<String> statics = new ArrayList<String>();
        final int[] version = {Opcodes.V17};
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int access, String name, String sig, String sup, String[] ifs) { version[0] = v; }
            @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if ((access & Opcodes.ACC_STATIC) != 0) statics.add(name + ":" + desc);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        w.visit(version[0], Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC, copyName, null, "java/lang/Object", null);
        for (String f : statics) {
            int c = f.indexOf(':');
            w.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC, f.substring(0, c), f.substring(c + 1), null, null).visitEnd();
        }
        final ClassWriter out = w;
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                boolean values = name.equals("$values") && (access & Opcodes.ACC_STATIC) != 0;
                if (!name.equals("<clinit>") && !values) return null;
                MethodVisitor mv = out.visitMethod(name.equals("<clinit>") ? Opcodes.ACC_STATIC : Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                        name, desc, null, ex);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitFieldInsn(int op, String owner, String n, String d) {
                        boolean own = owner.equals(real) && statics.contains(n + ":" + d) && (op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC);
                        super.visitFieldInsn(op, own ? copyName : owner, n, d);
                    }
                    @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        boolean own = op == Opcodes.INVOKESTATIC && owner.equals(real) && n.equals("$values");
                        super.visitMethodInsn(op, own ? copyName : owner, n, d, itf && !own);
                    }
                    @Override public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
                        super.visitFrame(type, nLocal, local, nStack, stack);   // types of the real class stay valid
                    }
                };
            }
        }, 0);
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * Access widener entries added while the game runs, by owner (internal name): {access, kind, name, desc}. Fabric
     * Loader applies only the wideners it read at startup, so classes that load later get these from the load-time
     * transformer; loaded ones are redefined with them (AccessWiden).
     */
    static final java.util.Map<String, List<String[]>> WIDEN = new java.util.concurrent.ConcurrentHashMap<String, List<String[]>>();

    public static void addWidening(String owner, String access, String kind, String name, String desc) {
        List<String[]> l = WIDEN.get(owner);
        if (l == null) { l = new java.util.concurrent.CopyOnWriteArrayList<String[]>(); WIDEN.put(owner, l); }
        l.add(new String[]{access, kind, name, desc});
    }

    /** All entries for this owner so far (as {access, kind, name, desc}); empty if none. */
    public static List<String[]> wideningsOf(String owner) {
        List<String[]> l = WIDEN.get(owner);
        return l == null ? new ArrayList<String[]>() : l;
    }

    /**
     * The class with access widener entries applied, as Fabric's widener does it for compiling and running:
     * accessible -> public (a widened member's class too), extendable -> public (protected for methods) and not final,
     * mutable -> a field not final.
     */
    public static byte[] widen(byte[] cls, final List<String[]> entries) {
        ClassReader r = new ClassReader(cls);
        ClassWriter w = new ClassWriter(0);
        final String self = r.getClassName();
        r.accept(new ClassVisitor(Opcodes.ASM9, w) {
            int classAccess(int access) {
                for (String[] e : entries) {
                    if (e[0].equals("mutable")) continue;
                    boolean ext = e[0].equals("extendable") && e[1].equals("class");
                    access = pub(access);
                    if (ext) access &= ~Opcodes.ACC_FINAL;
                }
                return access;
            }
            @Override public void visit(int v, int access, String name, String sig, String sup, String[] ifs) { super.visit(v, classAccess(access), name, sig, sup, ifs); }
            @Override public void visitInnerClass(String name, String outer, String inner, int access) {
                super.visitInnerClass(name, outer, inner, name.equals(self) ? classAccess(access) : access);
            }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                for (String[] e : entries)
                    if (e[1].equals("method") && e[2].equals(name) && e[3].equals(desc)) {
                        if (e[0].equals("accessible")) access = pub(access);
                        if (e[0].equals("extendable")) access = (access & Opcodes.ACC_PUBLIC) != 0 ? access & ~Opcodes.ACC_FINAL
                                : (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) | Opcodes.ACC_PROTECTED;
                    }
                return super.visitMethod(access, name, desc, sig, ex);
            }
            @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                for (String[] e : entries)
                    if (e[1].equals("field") && e[2].equals(name) && e[3].equals(desc)) {
                        if (e[0].equals("accessible")) access = pub(access);
                        if (e[0].equals("mutable")) access &= ~Opcodes.ACC_FINAL;
                    }
                return super.visitField(access, name, desc, sig, value);
            }
        }, 0);
        return w.toByteArray();
    }

    private static int pub(int access) { return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC; }

    /**
     * An entity type registered while the game runs can be in the world before the client has its renderer (the
     * renderer comes with the client entry point and a resource reload); Minecraft then crashes in
     * EntityRenderDispatcher.shouldRender ("entityRenderer is null"). As the dispatcher loads, shouldRender and render
     * first check getRenderer: no renderer, not drawn (yet). Load time only, like the other hooks.
     */
    private static byte[] rendererGuard(byte[] bytes, java.util.function.Consumer<String> log) {
        try {
            ClassReader r = new ClassReader(bytes);
            final String owner = r.getClassName();
            final String[] getRenderer = {null};
            r.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String m, String desc, String sig, String[] ex) {
                    if (m.equals("getRenderer") && Type.getArgumentTypes(desc).length == 1 && (access & Opcodes.ACC_STATIC) == 0) getRenderer[0] = desc;
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            if (getRenderer[0] == null) return null;
            final String entityType = Type.getArgumentTypes(getRenderer[0])[0].getInternalName();
            ClassWriter w = new ClassWriter(r, ClassWriter.COMPUTE_MAXS);
            final int[] patched = {0};
            r.accept(new ClassVisitor(Opcodes.ASM9, w) {
                @Override public MethodVisitor visitMethod(int access, String m, final String desc, String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, m, desc, sig, ex);
                    Type[] a = Type.getArgumentTypes(desc);
                    final Type ret = Type.getReturnType(desc);
                    boolean should = m.equals("shouldRender") && ret.getSort() == Type.BOOLEAN && a.length == 5;
                    boolean render = m.equals("render") && ret.getSort() == Type.VOID && a.length > 3 && a[0].getInternalName().equals(entityType);
                    if ((access & Opcodes.ACC_STATIC) != 0 || !(should || render) || !a[0].getInternalName().equals(entityType)) return mv;
                    patched[0]++;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            Label go = new Label();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitVarInsn(Opcodes.ALOAD, 1);
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "getRenderer", getRenderer[0], false);
                            mv.visitJumpInsn(Opcodes.IFNONNULL, go);
                            if (ret.getSort() == Type.BOOLEAN) { mv.visitInsn(Opcodes.ICONST_0); mv.visitInsn(Opcodes.IRETURN); }
                            else mv.visitInsn(Opcodes.RETURN);
                            mv.visitLabel(go);
                            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }
                    };
                }
            }, 0);
            if (patched[0] == 0) return null;
            log.accept("runtime registration: entity renderer guard in " + owner.replace('/', '.') + " (" + patched[0] + " method(s))");
            return w.toByteArray();
        } catch (Throwable t) {
            log.accept("runtime registration: no entity renderer guard: " + t);
            return null;
        }
    }

    static final String KEY_BINDINGS = "net/fabricmc/fabric/impl/client/keybinding/KeyBindingRegistryImpl";
    public static final String KEY_HOOK = "dev.sulfurframe.keyBinding";

    /**
     * Fabric's KeyBindingRegistryImpl.registerKeyBinding refuses a binding once the game options exist. While a client
     * entry point runs again, it first asks a hook (a JDK Function in a system property) that takes the binding.
     */
    private static byte[] keyBindingHook(byte[] bytes, java.util.function.Consumer<String> log) {
        try {
            ClassReader r = new ClassReader(bytes);
            ClassWriter w = new ClassWriter(r, ClassWriter.COMPUTE_MAXS);
            final int[] patched = {0};
            r.accept(new ClassVisitor(Opcodes.ASM9, w) {
                @Override public MethodVisitor visitMethod(int access, String m, final String desc, String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, m, desc, sig, ex);
                    Type[] a = Type.getArgumentTypes(desc);
                    if (!m.equals("registerKeyBinding") || (access & Opcodes.ACC_STATIC) == 0 || a.length != 1 || a[0].getSort() != Type.OBJECT) return mv;
                    patched[0]++;
                    final String argType = a[0].getInternalName(), retType = Type.getReturnType(desc).getInternalName();
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            Label skip = new Label();
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                            mv.visitLdcInsn(KEY_HOOK);
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            mv.visitInsn(Opcodes.DUP);
                            mv.visitJumpInsn(Opcodes.IFNULL, skip);
                            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function");
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                            mv.visitTypeInsn(Opcodes.CHECKCAST, retType);
                            mv.visitInsn(Opcodes.ARETURN);
                            mv.visitLabel(skip);
                            mv.visitFrame(Opcodes.F_FULL, 1, new Object[]{argType}, 1, new Object[]{"java/lang/Object"});
                            mv.visitInsn(Opcodes.POP);
                        }
                    };
                }
            }, 0);
            if (patched[0] == 0) return null;
            log.accept("runtime registration: Fabric's registerKeyBinding hooked as it loaded");
            return w.toByteArray();
        } catch (Throwable t) {
            log.accept("runtime registration: could not hook registerKeyBinding: " + t);
            return null;
        }
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
