package dev.sulfurframe.agent;

import java.io.*;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Hotswap without a debugger: the IDE compiles, writes "binaryName\tpath/to/Class.class" lines to a list file and
 * attaches the agent with "redefine=<list file>". Loaded classes with those names get the new bytes through
 * Instrumentation.redefineClasses; classes not loaded yet need nothing (the class loader reads the new file later).
 *
 * A standard JVM only accepts changed method bodies. If the whole batch is refused (a method or field was added or
 * removed), each class is retried alone so the others still land, and the refused ones are reported as
 * "restart needed". The result goes to "<list file>.result" for the IDE to read. Java 8.
 */
final class Hotswap {
    /** Redefine the listed classes; the answer goes to "<list>.result" and is returned. */
    static List<String> redefine(Instrumentation inst, String listFile) {
        List<String> out = new ArrayList<String>();
        long t0 = System.nanoTime();
        try {
            Map<String, Path> wanted = new LinkedHashMap<String, Path>();
            for (String line : Files.readAllLines(Paths.get(listFile), StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) wanted.put(line.substring(0, tab), Paths.get(line.substring(tab + 1)));
            }
            List<ClassDefinition> defs = new ArrayList<ClassDefinition>();
            Set<String> loadedNames = new HashSet<String>();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                Path p = wanted.get(c.getName());
                if (p == null || c.getClassLoader() == null || !inst.isModifiableClass(c)) continue;
                defs.add(new ClassDefinition(c, Files.readAllBytes(p)));
                loadedNames.add(c.getName());
            }
            int ok = 0;
            List<String> restart = new ArrayList<String>();
            List<Class<?>> classes = new ArrayList<Class<?>>();
            for (ClassDefinition d : defs) classes.add(d.getDefinitionClass());
            List<ReflectionFix.Entry> reflected = ReflectionFix.before(inst, classes);   // fields that move keep working through reflection
            try {
                inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
                ok = defs.size();
            } catch (UnsupportedOperationException | ClassFormatError | VerifyError e) {
                for (ClassDefinition d : defs) {      // one by one: apply what can be applied, name what cannot
                    try { inst.redefineClasses(d); ok++; }
                    catch (Throwable t) {
                        restart.add(d.getDefinitionClass().getName() + " (" + reason(t) + ")");
                        SfHooks.log("hotswap: " + d.getDefinitionClass().getName() + " not redefined: " + t);   // the JVM's own words
                    }
                }
            }
            List<String> moved = ReflectionFix.after(reflected);
            out.add("redefined " + ok);
            out.add("not loaded " + (wanted.size() - loadedNames.size()));
            for (String w : wanted.keySet()) if (!loadedNames.contains(w)) out.add("not loaded: " + w);   // the IDE checks for mixins
            for (String r : restart) out.add("restart needed: " + r);
            out.addAll(moved);
            out.add("ms " + (System.nanoTime() - t0) / 1_000_000);
            SfHooks.log("hotswap: redefined " + ok + " class(es)" + (restart.isEmpty() ? "" : ", " + restart.size() + " need a restart"));
            if (!restart.isEmpty()) GameMessage.show(inst, "§eHotswap: " + ok + " applied, " + restart.size() + " need a restart");
            else if (ok > 0) GameMessage.show(inst, "§aHotswap: " + ok + (ok == 1 ? " class" : " classes") + " reloaded");
        } catch (Throwable t) {
            out.add("error " + t);
            SfHooks.log("hotswap failed: " + t);
        }
        try { AnswerFile.write(listFile + ".result", out); } catch (IOException ignored) { }
        return out;
    }

    static String reason(Throwable t) {
        String m = String.valueOf(t.getMessage());
        if (m.contains("superclass or interfaces")) return "superclass or interfaces changed";   // JBR too: back to Object
        if (m.contains("NestHost") || m.contains("NestMembers") || m.contains("Record") || m.contains("PermittedSubclasses"))
            return "nested classes or record components changed";
        if (m.contains("add a method") || m.contains("delete a method") || m.contains("schema") || m.contains("attempted"))
            return "methods or fields added/removed";
        return t.getClass().getSimpleName() + ": " + m;
    }

    private Hotswap() {}
}
