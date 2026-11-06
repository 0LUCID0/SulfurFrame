package dev.sulfurframe.agent.impl;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

/**
 * Inserts static hook calls into a few LWJGL methods. LWJGL class/method names are stable across releases and are
 * never remapped by mod loaders, unlike Minecraft's own classes, so one agent covers every game version and loader.
 *
 * Loaded in an isolated class loader (parent = bootstrap) together with its own copy of ASM, so it can never clash
 * with the ASM that Fabric/Forge/Mixin put on the game's class path. Talks to the game side only through
 * java.lang.instrument and class-name strings. Java 8 bytecode.
 */
public final class LwjglTransformer implements ClassFileTransformer {
    static final String HOOKS = "dev/sulfurframe/agent/SfHooks";

    enum Where { ENTRY, BEFORE_RETURN }

    /** hook descriptor: ENTRY hooks receive the listed args; BEFORE_RETURN hooks get (returnValue[, args]) and return it. */
    static final class Patch {
        final String owner, name, desc, hook, hookDesc; final Where where; final int[] argSlots; final int[] argOps;
        Patch(String owner, String name, String desc, Where where, String hook, String hookDesc, int[] argSlots, int[] argOps) {
            this.owner = owner; this.name = name; this.desc = desc; this.where = where; this.hook = hook; this.hookDesc = hookDesc;
            this.argSlots = argSlots; this.argOps = argOps;
        }
    }

    static final List<Patch> PATCHES = Arrays.asList(
            // SDL3 (Minecraft 26.x): key events of a child window carry windowID 0
            new Patch("org/lwjgl/sdl/SDLEvents", "SDL_GetWindowFromEvent", "(Lorg/lwjgl/sdl/SDL_Event;)J",
                    Where.BEFORE_RETURN, "sdlWindowFromEvent", "(J)J", new int[0], new int[0]),
            // SDL3: never reports focus for a child window -> synthesize focus events from real Win32 focus
            new Patch("org/lwjgl/sdl/SDLEvents", "SDL_PollEvent", "(Lorg/lwjgl/sdl/SDL_Event;)Z",
                    Where.BEFORE_RETURN, "sdlPollEvent", "(ZLjava/lang/Object;)Z", new int[]{0}, new int[]{Opcodes.ALOAD}),
            // frame presentation, every backend: cap the frame rate while the editor has focus
            new Patch("org/lwjgl/glfw/GLFW", "glfwSwapBuffers", "(J)V",
                    Where.ENTRY, "beforeGlfwSwap", "(J)V", new int[]{0}, new int[]{Opcodes.LLOAD}),
            new Patch("org/lwjgl/sdl/SDLVideo", "SDL_GL_SwapWindow", "(J)Z",
                    Where.ENTRY, "beforeSdlSwap", "(J)V", new int[]{0}, new int[]{Opcodes.LLOAD}),
            new Patch("org/lwjgl/vulkan/KHRSwapchain", "vkQueuePresentKHR", "(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I",
                    Where.ENTRY, "beforeVkPresent", "()V", new int[0], new int[0]),
            // LWJGL 2 (Minecraft 1.12.2 and older): frame presentation. Focus needs no patch there: LWJGL 2 tracks
            // WM_SETFOCUS/WM_KILLFOCUS itself, so Display.isActive() is right for a child window (verified on 1.12.2).
            // Display.update() delegates to update(true).
            new Patch("org/lwjgl/opengl/Display", "update", "(Z)V",
                    Where.ENTRY, "beforeLwjgl2Update", "()V", new int[0], new int[0]));

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
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String mName, String mDesc, String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, mName, mDesc, sig, ex);
                    for (final Patch p : PATCHES) {
                        if (p.owner.equals(name) && p.name.equals(mName) && p.desc.equals(mDesc)) {
                            hits[0]++;
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override public void visitCode() {
                                    super.visitCode();
                                    if (p.where == Where.ENTRY) { loadArgs(this, p); callHook(this, p); }
                                }
                                @Override public void visitInsn(int op) {
                                    if (p.where == Where.BEFORE_RETURN && op >= Opcodes.IRETURN && op <= Opcodes.ARETURN) {
                                        loadArgs(this, p);
                                        callHook(this, p);
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

    static void loadArgs(MethodVisitor mv, Patch p) {
        for (int i = 0; i < p.argSlots.length; i++) mv.visitVarInsn(p.argOps[i], p.argSlots[i]);
    }

    static void callHook(MethodVisitor mv, Patch p) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, p.hook, p.hookDesc, false);
    }
}
