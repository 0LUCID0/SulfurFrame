package dev.sulfurframe.idea;

import org.jetbrains.org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * An access widener changed, and the game is restarted without Gradle ({@link QuickRelaunch}): Fabric Loader applies
 * the new widener itself when the game starts (checked: a field widened only in the output folder reads public in the
 * game), but Loom's Minecraft jar on the compile class path still has the old one applied, so javac rejects code that
 * uses a newly widened member. This writes the classes the changed widener touches, with its access applied, into a
 * folder that goes first on fast compile's class path. Compile only: what the game runs comes from Loader.
 *
 * Understood: "accessWidener v1|v2" and "classTweaker v1" files; accessible / extendable / mutable and their
 * transitive- forms on class, method and field lines (inject-interface and other lines are skipped). For javac only
 * visibility and final matter: accessible and extendable make the target (and its owner class) public, extendable and
 * mutable drop final.
 */
final class AwOverlay {
    record Entry(String access, String kind, String owner, String name, String desc) {}

    /** The widener's entries (empty for a header alone); null if the header is not one this understands. */
    static List<Entry> parse(String text) {
        var out = new ArrayList<Entry>();
        boolean header = false;
        for (String raw : text.split("\r?\n")) {
            String line = raw.replaceAll("#.*", "").trim();
            if (line.isEmpty()) continue;
            String[] t = line.split("\\s+");
            if (!header) {
                header = t[0].equals("accessWidener") || t[0].equals("classTweaker");
                if (!header) return null;
                continue;
            }
            String access = t[0].startsWith("transitive-") ? t[0].substring("transitive-".length()) : t[0];
            if (!Set.of("accessible", "extendable", "mutable").contains(access) || t.length < 3) continue;
            String kind = t[1];
            if (kind.equals("class")) out.add(new Entry(access, kind, t[2], null, null));
            else if ((kind.equals("method") || kind.equals("field")) && t.length >= 5) out.add(new Entry(access, kind, t[2], t[3], t[4]));
        }
        return out;
    }

    /**
     * Write the classes {@code wideners} touch, with their access applied, into {@code dir} (emptied first).
     * @return how many classes were written; -1 if a widener could not be read or a class was not found
     */
    static int build(List<Path> wideners, List<Path> classpath, Path dir) throws IOException {
        var entries = new ArrayList<Entry>();
        for (Path w : wideners) {
            var e = parse(Files.readString(w));
            if (e == null) return -1;
            entries.addAll(e);
        }
        clear(dir);
        Files.createDirectories(dir);
        if (entries.isEmpty()) return 0;
        var byOwner = new LinkedHashMap<String, List<Entry>>();
        var widenedClasses = new HashSet<String>();   // made public (and maybe non-final) as classes
        for (Entry e : entries) {
            byOwner.computeIfAbsent(e.owner(), k -> new ArrayList<>()).add(e);
            widenedClasses.add(e.owner());                 // a widened member needs a visible owner too (for javac)
        }
        // an inner class's access is also recorded in its outer class's InnerClasses table: rewrite the outers too
        for (String c : new ArrayList<>(byOwner.keySet()))
            for (int i = c.lastIndexOf('$'); i > 0; i = c.lastIndexOf('$', i - 1)) byOwner.putIfAbsent(c.substring(0, i), new ArrayList<>());
        var finalDropped = new HashSet<String>();
        for (Entry e : entries) if (e.kind().equals("class") && e.access().equals("extendable")) finalDropped.add(e.owner());

        int written = 0;
        var jars = new ArrayList<Path>();
        for (Path p : classpath) if (p.toString().endsWith(".jar") && Files.isRegularFile(p)) jars.add(p);
        jars.sort(Comparator.comparing(p -> !p.getFileName().toString().startsWith("minecraft")));   // Minecraft first
        for (var o : byOwner.entrySet()) {
            byte[] bytes = find(jars, o.getKey() + ".class");
            if (bytes == null) return -1;
            var members = o.getValue();
            var cw = new ClassWriter(0);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override public void visit(int v, int access, String name, String sig, String sup, String[] ifs) {
                    if (widenedClasses.contains(name)) access = pub(access, finalDropped.contains(name));
                    super.visit(v, access, name, sig, sup, ifs);
                }
                @Override public void visitInnerClass(String name, String outer, String inner, int access) {
                    if (widenedClasses.contains(name)) access = pub(access, finalDropped.contains(name));
                    super.visitInnerClass(name, outer, inner, access);
                }
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    for (Entry e : members)
                        if (e.kind().equals("method") && e.name().equals(name) && e.desc().equals(desc))
                            access = e.access().equals("mutable") ? access : pub(access, e.access().equals("extendable"));
                    return super.visitMethod(access, name, desc, sig, ex);
                }
                @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                    for (Entry e : members)
                        if (e.kind().equals("field") && e.name().equals(name) && e.desc().equals(desc)) {
                            if (e.access().equals("accessible")) access = pub(access, false);
                            if (e.access().equals("mutable")) access &= ~Opcodes.ACC_FINAL;
                        }
                    return super.visitField(access, name, desc, sig, value);
                }
            }, 0);
            Path to = dir.resolve(o.getKey() + ".class");
            Files.createDirectories(to.getParent());
            Files.write(to, cw.toByteArray());
            written++;
        }
        return written;
    }

    private static int pub(int access, boolean dropFinal) {
        access = (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        return dropFinal ? access & ~Opcodes.ACC_FINAL : access;
    }

    private static byte[] find(List<Path> jars, String entry) throws IOException {
        for (Path j : jars)
            try (var z = new ZipFile(j.toFile())) {
                var e = z.getEntry(entry);
                if (e != null) try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); }
            }
        return null;
    }

    static void clear(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) { for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }

    private AwOverlay() {}
}
