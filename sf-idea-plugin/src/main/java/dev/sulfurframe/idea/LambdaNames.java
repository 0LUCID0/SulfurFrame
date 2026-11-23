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

    private LambdaNames() {}
}
