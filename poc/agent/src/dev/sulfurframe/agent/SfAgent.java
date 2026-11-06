package dev.sulfurframe.agent;

import java.lang.classfile.*;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static java.lang.constant.ConstantDescs.*;

/**
 * SulfurFrame javaagent: the smallest game-side patch that makes an embedded (WS_CHILD) Minecraft usable.
 *
 * SDL3 decides keyboard focus by "foreground window == my window", which a child window can never be. So SDL never
 * gives the embedded game keyboard focus: key events carry windowID 0, Minecraft drops them, and because the window
 * "is not focused", pauseOnLostFocus keeps reopening the pause menu.
 *
 * Three one-line patches, each "call a hook right before return", written with the JDK's Class-File API (no ASM):
 *   Window.isFocused()                    -> SfHooks.focused(original)       real Win32 keyboard focus
 *   SDLEventHandler.getWindowHandle(evt)  -> SfHooks.windowHandle(original)  windowID 0 -> the game window
 *   FramerateLimitTracker.getFramerateLimit() -> SfHooks.fpsLimit(original) fps cap while the editor has focus
 *
 * Works from the command line (-javaagent:sf-agent.jar) and attached to a running game (Attach API, retransform).
 */
public final class SfAgent {
    static final ClassDesc HOOKS = ClassDesc.of("dev.sulfurframe.agent.SfHooks");

    record Patch(String clazz, String method, String desc, Opcode ret, String hook, MethodTypeDesc hookType) {}

    static final List<Patch> PATCHES = List.of(
            new Patch("com/mojang/blaze3d/platform/Window", "isFocused", "()Z",
                    Opcode.IRETURN, "focused", MethodTypeDesc.of(CD_boolean, CD_boolean)),
            new Patch("com/mojang/blaze3d/platform/SDLEventHandler", "getWindowHandle", "(Lorg/lwjgl/sdl/SDL_Event;)J",
                    Opcode.LRETURN, "windowHandle", MethodTypeDesc.of(CD_long, CD_long)),
            new Patch("com/mojang/blaze3d/platform/FramerateLimitTracker", "getFramerateLimit", "()I",
                    Opcode.IRETURN, "fpsLimit", MethodTypeDesc.of(CD_int, CD_int)));

    public static void premain(String args, Instrumentation inst) { install(args, inst, false); }

    public static void agentmain(String args, Instrumentation inst) { install(args, inst, true); }

    private static volatile boolean installed;

    private static synchronized void install(String args, Instrumentation inst, boolean attached) {
        SfHooks.configure(args);
        if (installed) { SfHooks.log("already installed; updated settings only (coding fps cap " + SfHooks.codingFps + ")"); return; }
        installed = true;
        var targets = Set.copyOf(PATCHES.stream().map(Patch::clazz).toList());
        inst.addTransformer(new Transformer(targets), true);
        SfHooks.log("installed (" + (attached ? "attached" : "premain") + "), coding fps cap " + SfHooks.codingFps);
        if (!attached) return;
        var loaded = new ArrayList<Class<?>>();
        for (Class<?> c : inst.getAllLoadedClasses())
            if (targets.contains(c.getName().replace('.', '/')) && inst.isModifiableClass(c)) loaded.add(c);
        try {
            inst.retransformClasses(loaded.toArray(Class[]::new));
            SfHooks.log("retransformed " + loaded.size() + " loaded classes");
        } catch (Throwable t) {
            SfHooks.log("retransform failed: " + t);
        }
    }

    static final class Transformer implements ClassFileTransformer {
        private final Set<String> targets;

        Transformer(Set<String> targets) { this.targets = targets; }

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
            if (name == null || !targets.contains(name)) return null;
            try {
                var patches = PATCHES.stream().filter(p -> p.clazz().equals(name)).toList();
                // only an extra invokestatic before a return: control flow and frame shapes stay the same, but the API
                // regenerates stack maps, so give it a resolver that can see the game's classes
                var cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                        ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofClassLoading(
                                loader != null ? loader : ClassLoader.getSystemClassLoader()))));
                ClassModel model = cf.parse(bytes);
                int[] hits = {0};
                byte[] out = cf.transformClass(model, ClassTransform.transformingMethodBodies(
                        m -> patches.stream().anyMatch(p -> m.methodName().equalsString(p.method()) && m.methodType().equalsString(p.desc())),
                        (cob, el) -> {
                            if (el instanceof ReturnInstruction r) {
                                for (var p : patches) {
                                    if (r.opcode() == p.ret()) { cob.invokestatic(HOOKS, p.hook(), p.hookType()); hits[0]++; break; }
                                }
                            }
                            cob.with(el);
                        }));
                SfHooks.log("patched " + name + " (" + hits[0] + " return site(s))");
                return out;
            } catch (Throwable t) {
                SfHooks.log("FAILED to patch " + name + ": " + t);
                return null;   // leave the class untouched rather than break the game
            }
        }
    }

    private SfAgent() {}
}
