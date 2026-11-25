package dev.sulfurframe.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;

/**
 * A screenshot of the game for the IDE ("shot=<png>,done=<file>", the agent bridge's `sf shot`): the main framebuffer
 * read on the render thread with Minecraft's own screenshot code (Yarn ScreenshotRecorder.takeScreenshot, Mojang
 * Screenshot.takeScreenshot), written as PNG. It reads the framebuffer, not the screen, so a covered or embedded
 * window is fine. Mojang and Yarn names, reflection only. Java 8.
 */
final class GameShot {
    private static final String[][] NAMES = {
            // client, framebuffer getter, screenshot class
            {"net.minecraft.client.Minecraft", "getMainRenderTarget", "net.minecraft.client.Screenshot"},
            {"net.minecraft.client.MinecraftClient", "getFramebuffer", "net.minecraft.client.util.ScreenshotRecorder"}};

    static void take(Instrumentation inst, final String png, final String doneFile) {
        try {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                int which = -1;
                for (int i = 0; i < NAMES.length; i++) if (c.getName().equals(NAMES[i][0])) which = i;
                if (which < 0) continue;
                final Object mc = c.getMethod("getInstance").invoke(null);
                final String[] n = NAMES[which];
                final ClassLoader cl = c.getClassLoader();
                c.getMethod("execute", Runnable.class).invoke(mc, new Runnable() {
                    public void run() {
                        try {
                            Object fb = mc.getClass().getMethod(n[1]).invoke(mc);
                            Method grab = null;
                            for (Method m : Class.forName(n[2], true, cl).getMethods())
                                if (Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 1
                                        && m.getParameterTypes()[0].isInstance(fb) && m.getReturnType().getSimpleName().equals("NativeImage")) grab = m;
                            if (grab == null) { done(doneFile, "no screenshot method"); return; }
                            Object image = grab.invoke(null, fb);
                            try {
                                Path out = Paths.get(png);
                                Path tmp = out.resolveSibling(out.getFileName() + ".part");
                                Method write = null;
                                for (Method m : image.getClass().getMethods())
                                    if ((m.getName().equals("writeTo") || m.getName().equals("writeToFile")) && m.getParameterTypes().length == 1
                                            && m.getParameterTypes()[0] == Path.class) write = m;
                                if (write == null) { done(doneFile, "no image write method"); return; }
                                write.invoke(image, tmp);
                                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
                            } finally {
                                image.getClass().getMethod("close").invoke(image);
                            }
                            SfHooks.log("screenshot for the IDE: " + png);
                            done(doneFile, "done");
                        } catch (Throwable t) {
                            while (t.getCause() != null && t instanceof java.lang.reflect.InvocationTargetException) t = t.getCause();
                            done(doneFile, "failed: " + t);
                        }
                    }
                });
                return;
            }
            done(doneFile, "no named Minecraft client");
        } catch (Throwable t) {
            done(doneFile, "failed: " + t);
        }
    }

    private static void done(String file, String text) {
        if (file == null) return;
        try {
            Path f = Paths.get(file), tmp = f.resolveSibling(f.getFileName() + ".part");
            Files.write(tmp, Collections.singletonList(text), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { }
    }

    private GameShot() {}
}
