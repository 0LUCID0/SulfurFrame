package dev.sulfurframe.agent;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * A class file that disappears from the project's build output while the game runs: a Gradle build started next to
 * the game (by hand, an IDE build, a coding agent checking its work) clears the output folder and writes it again,
 * and a class the game loads for the first time in that window was a NoClassDefFoundError that closed the game
 * (seen twice in a day on the multi-project mod). Fabric's class loader is patched as it loads
 * (RegistryPatch.classRescue): when it finds no bytes for a class that was in an output folder when the game started,
 * this waits for the file to come back (up to 30 s) and reads it then. Any other missing class (optional classes,
 * probes, really new names) is answered at once, as before. Java 8.
 */
final class ClassRescue {
    static final String KEY = "dev.sulfurframe.classRescue";
    private static volatile Set<String> known;   // "a/b/C.class" in the output folders at the first lookup

    static void install() {
        System.getProperties().put(KEY, new Function<Object[], Object>() {
            public Object apply(Object[] a) { return lookup(a[0], (String) a[1], (Boolean) a[2]); }
        });
    }

    private static Object lookup(Object delegate, String name, boolean allowFromParent) {
        try {
            Method raw = original(delegate.getClass());
            Object bytes = raw.invoke(delegate, name, allowFromParent);
            if (bytes != null) return bytes;
            String file = name.replace('.', '/') + ".class";
            if (!known().contains(file)) return null;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 30000) {
                Thread.sleep(250);
                bytes = raw.invoke(delegate, name, allowFromParent);
                if (bytes != null) {
                    SfHooks.log("class rescue: " + name + " was missing from the build output (a build rewriting it?); back after "
                            + (System.currentTimeMillis() - start) + " ms");
                    return bytes;
                }
            }
            SfHooks.log("class rescue: " + name + " stayed missing from the build output for 30 s");
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile Method original;

    private static Method original(Class<?> c) throws NoSuchMethodException {
        Method m = original;
        if (m == null) {
            m = c.getDeclaredMethod("sf$rawClassBytes0", String.class, boolean.class);
            m.setAccessible(true);
            original = m;
        }
        return m;
    }

    /** Class files in the class path's folders (the project's outputs; jars are left out), listed once. */
    private static Set<String> known() {
        Set<String> k = known;
        if (k != null) return k;
        synchronized (ClassRescue.class) {
            if (known != null) return known;
            Set<String> out = new HashSet<String>();
            String cp = System.getProperty("java.class.path", "");
            for (String e : cp.split(File.pathSeparator)) {
                File d = new File(e);
                if (d.isDirectory()) walk(d, "", out);
            }
            String groups = System.getProperty("fabric.classPathGroups", "");   // Loom: the mod output folders
            for (String e : groups.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                File d = new File(e);
                if (d.isDirectory()) walk(d, "", out);
            }
            known = out;
            return out;
        }
    }

    private static void walk(File dir, String prefix, Set<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) walk(f, prefix + f.getName() + "/", out);
            else if (f.getName().endsWith(".class")) out.add(prefix + f.getName());
        }
    }

    private ClassRescue() {}
}
