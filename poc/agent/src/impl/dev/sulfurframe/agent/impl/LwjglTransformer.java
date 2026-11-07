package dev.sulfurframe.agent.impl;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

/**
 * Inserts hook calls into a few LWJGL methods. LWJGL class/method names are stable across releases and are never
 * remapped by mod loaders, unlike Minecraft's own classes, so one agent covers every game version and loader.
 *
 * The inserted code never names an agent class. Mod loaders decide which classes the game may see (Fabric's Knot
 * refuses classes from class-path entries "not exposed to the game", and NeoForge/Forge put LWJGL in named modules),
 * so instead the hooks are JDK functional-interface objects that the agent stores in an Object[] under the system
 * property key {@link #REGISTRY_KEY}. The patched code fetches one and calls it through the JDK interface, which
 * every class loader and module can see.
 *
 * Loaded in an isolated class loader (parent = bootstrap) together with its own copy of ASM, so it never clashes with
 * the ASM that Fabric/Forge/Mixin bring. Java 8 bytecode.
 */
public final class LwjglTransformer implements ClassFileTransformer {
    public static final String REGISTRY_KEY = "dev.sulfurframe.hooks";

    /** How a hook is called. The index is its slot in the registry array (see SfAgent). */
    enum Kind {
        /** before LRETURN: long -> LongUnaryOperator.applyAsLong -> long */
        LONG_FILTER_BEFORE_LRETURN,
        /** before IRETURN of a boolean method with arg0 an object: BiFunction.apply(Boolean, arg0) -> Boolean */
        BOOL_FILTER_WITH_ARG0_BEFORE_IRETURN,
        /** at entry, arg0 is a long: LongConsumer.accept(arg0) */
        LONG_ARG0_AT_ENTRY,
        /** at entry: Runnable.run() */
        RUN_AT_ENTRY,
        /** at entry, arg0 a long: if LongPredicate.test(arg0) return at once (void, or true for a boolean method) */
        LONG_SKIP_IF_AT_ENTRY,
        /** at entry of a void method: if BooleanSupplier.getAsBoolean() return at once */
        SKIP_IF_AT_ENTRY
    }

    static final class Patch {
        final String owner, name, desc; final Kind kind; final int slot;
        Patch(String owner, String name, String desc, Kind kind, int slot) {
            this.owner = owner; this.name = name; this.desc = desc; this.kind = kind; this.slot = slot;
        }
    }

    /** Registry slots, in the order SfAgent fills the array. */
    public static final int SLOT_SDL_WINDOW_FROM_EVENT = 0, SLOT_SDL_POLL_EVENT = 1, SLOT_GLFW_SWAP = 2,
            SLOT_SDL_SWAP = 3, SLOT_VK_PRESENT = 4, SLOT_LWJGL2_UPDATE = 5, SLOTS = 6;

    static final List<Patch> PATCHES = Arrays.asList(
            // SDL3 (Minecraft 26.x): key events of a child window carry windowID 0
            new Patch("org/lwjgl/sdl/SDLEvents", "SDL_GetWindowFromEvent", "(Lorg/lwjgl/sdl/SDL_Event;)J",
                    Kind.LONG_FILTER_BEFORE_LRETURN, SLOT_SDL_WINDOW_FROM_EVENT),
            // SDL3: never reports focus for a child window -> synthesize focus events from real Win32 focus
            new Patch("org/lwjgl/sdl/SDLEvents", "SDL_PollEvent", "(Lorg/lwjgl/sdl/SDL_Event;)Z",
                    Kind.BOOL_FILTER_WITH_ARG0_BEFORE_IRETURN, SLOT_SDL_POLL_EVENT),
            // frame presentation, every backend: cap the frame rate while the editor has focus
            new Patch("org/lwjgl/glfw/GLFW", "glfwSwapBuffers", "(J)V", Kind.LONG_SKIP_IF_AT_ENTRY, SLOT_GLFW_SWAP),
            new Patch("org/lwjgl/sdl/SDLVideo", "SDL_GL_SwapWindow", "(J)Z", Kind.LONG_SKIP_IF_AT_ENTRY, SLOT_SDL_SWAP),
            new Patch("org/lwjgl/vulkan/KHRSwapchain", "vkQueuePresentKHR",
                    "(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I", Kind.RUN_AT_ENTRY, SLOT_VK_PRESENT),
            // LWJGL 2 (Minecraft 1.12.2 and older): frame presentation. Focus needs no patch there: LWJGL 2 tracks
            // WM_SETFOCUS/WM_KILLFOCUS itself, so Display.isActive() is right for a child window (verified on 1.12.2).
            // Display.update() delegates to update(true).
            new Patch("org/lwjgl/opengl/Display", "update", "(Z)V", Kind.SKIP_IF_AT_ENTRY, SLOT_LWJGL2_UPDATE));

    static final Set<String> OWNERS = new HashSet<>();
    static { for (Patch p : PATCHES) OWNERS.add(p.owner); }

    /** Classes the agent should retransform when attached to a running game. */
    public static Set<String> targetClassNames() {
        Set<String> s = new HashSet<>();
        for (String o : OWNERS) s.add(o.replace('/', '.'));
        return s;
    }

    private final java.util.function.Consumer<String> log;
    private final java.util.function.Consumer<ClassLoader> lwjglLoaderSink;

    public LwjglTransformer(java.util.function.Consumer<String> log, java.util.function.Consumer<ClassLoader> lwjglLoaderSink) {
        this.log = log;
        this.lwjglLoaderSink = lwjglLoaderSink;
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (name == null || !OWNERS.contains(name)) return null;
        try {
            lwjglLoaderSink.accept(loader);
            ClassReader cr = new ClassReader(bytes);
            // COMPUTE_MAXS only: the inserted code is straight-line, so existing stack map frames stay valid
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            final int[] hits = {0};
            final int[] version = {0};
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public void visit(int v, int access, String n, String sig, String sup, String[] ifs) { version[0] = v & 0xFFFF; super.visit(v, access, n, sig, sup, ifs); }
                @Override
                public MethodVisitor visitMethod(int access, String mName, String mDesc, String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, mName, mDesc, sig, ex);
                    for (final Patch p : PATCHES) {
                        if (p.owner.equals(name) && p.name.equals(mName) && p.desc.equals(mDesc)) {
                            hits[0]++;
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override public void visitCode() {
                                    super.visitCode();
                                    if (p.kind == Kind.LONG_ARG0_AT_ENTRY) {
                                        fetchHook(this, p.slot, "java/util/function/LongConsumer");
                                        visitVarInsn(Opcodes.LLOAD, 0);
                                        visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/LongConsumer", "accept", "(J)V", true);
                                    } else if (p.kind == Kind.RUN_AT_ENTRY) {
                                        fetchHook(this, p.slot, "java/lang/Runnable");
                                        visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
                                    } else if (p.kind == Kind.SKIP_IF_AT_ENTRY) {
                                        fetchHook(this, p.slot, "java/util/function/BooleanSupplier");
                                        visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BooleanSupplier", "getAsBoolean", "()Z", true);
                                        Label go = new Label();
                                        visitJumpInsn(Opcodes.IFEQ, go);
                                        super.visitInsn(Opcodes.RETURN);
                                        visitLabel(go);
                                        if (version[0] >= Opcodes.V1_6) visitFrame(Opcodes.F_SAME, 0, null, 0, null);   // Java 5 classes (LWJGL 2): no stack maps
                                    } else if (p.kind == Kind.LONG_SKIP_IF_AT_ENTRY) {
                                        // if (hook.test(arg0)) return;   The frame at the join point is the method's
                                        // entry frame (nothing stored, empty stack): F_SAME, valid as the first frame.
                                        fetchHook(this, p.slot, "java/util/function/LongPredicate");
                                        visitVarInsn(Opcodes.LLOAD, 0);
                                        visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/LongPredicate", "test", "(J)Z", true);
                                        Label go = new Label();
                                        visitJumpInsn(Opcodes.IFEQ, go);
                                        if (p.desc.endsWith(")Z")) { super.visitInsn(Opcodes.ICONST_1); super.visitInsn(Opcodes.IRETURN); }   // "swapped fine"
                                        else super.visitInsn(Opcodes.RETURN);
                                        visitLabel(go);
                                        if (version[0] >= Opcodes.V1_6) visitFrame(Opcodes.F_SAME, 0, null, 0, null);   // Java 5 classes (LWJGL 2): no stack maps
                                    }
                                }
                                @Override public void visitInsn(int op) {
                                    if (p.kind == Kind.LONG_FILTER_BEFORE_LRETURN && op == Opcodes.LRETURN) {
                                        // [J] -> [op, J] -> applyAsLong -> [J]
                                        fetchHook(this, p.slot, "java/util/function/LongUnaryOperator");
                                        super.visitInsn(Opcodes.DUP_X2);   // [op, J, op]
                                        super.visitInsn(Opcodes.POP);      // [op, J]
                                        visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/LongUnaryOperator", "applyAsLong", "(J)J", true);
                                    } else if (p.kind == Kind.BOOL_FILTER_WITH_ARG0_BEFORE_IRETURN && op == Opcodes.IRETURN) {
                                        // [Z] -> [Boolean] -> [fn, Boolean, arg0] -> apply -> Boolean -> [Z]
                                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                                        fetchHook(this, p.slot, "java/util/function/BiFunction");
                                        super.visitInsn(Opcodes.SWAP);
                                        visitVarInsn(Opcodes.ALOAD, 0);
                                        visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply",
                                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
                                        visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                                    }
                                    super.visitInsn(op);
                                }
                            };
                        }
                    }
                    return mv;
                }
            }, 0);
            if (hits[0] == 0) return null;
            log.accept("patched " + name + " (" + hits[0] + " method(s))");
            return cw.toByteArray();
        } catch (Throwable t) {
            log.accept("FAILED to patch " + name + ": " + t);
            return null;   // leave the class untouched rather than break the game
        }
    }

    /** ((Object[]) System.getProperties().get(REGISTRY_KEY))[slot], cast to the JDK interface. */
    static void fetchHook(MethodVisitor mv, int slot, String iface) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
        mv.visitLdcInsn(REGISTRY_KEY);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitIntInsn(Opcodes.BIPUSH, slot);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitTypeInsn(Opcodes.CHECKCAST, iface);
    }
}
