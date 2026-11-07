package dev.sulfurframe.idea.profile;

import dev.sulfurframe.idea.profile.ProjectProfile.Loader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks ProjectProfile against loader-format fixtures (shaped after each loader's official MDK/template) and,
 * when given, real project directories. No test framework: prints PASS/FAIL, exits non-zero on failure.
 *
 *   java -cp out dev.sulfurframe.idea.profile.ProfileSelfTest <fixtures dir> [real project dirs...]
 */
public class ProfileSelfTest {
    record Case(String dir, String mc, Loader loader, String loaderVersion, int java) {}

    public static void main(String[] a) throws Exception {
        Path fx = Path.of(a[0]);
        var cases = List.of(
                new Case("neoforge-mdk", "1.21.1", Loader.NEOFORGE, "21.1.265", 21),
                new Case("forge-mdk-1.20.1", "1.20.1", Loader.FORGE, "47.4.28", 17),
                new Case("forge-fg2-1.12.2", "1.12.2", Loader.FORGE_LEGACY, "14.23.5.2860", 8),
                new Case("quilt", "1.20.1", Loader.QUILT, "0.26.4", 17),
                new Case("fabric-kts-inline", "1.21.1", Loader.FABRIC, "0.17.2", 21),
                new Case("fabric-catalog", "1.20.4", Loader.FABRIC, "0.15.11", 17));
        var failures = new ArrayList<String>();
        for (var c : cases) {
            var p = ProjectProfile.detect(fx.resolve(c.dir()));
            boolean ok = c.mc().equals(p.minecraftVersion()) && c.loader() == p.loader()
                    && c.loaderVersion().equals(p.loaderVersion()) && c.java() == p.javaMajor();
            System.out.println((ok ? "PASS " : "FAIL ") + c.dir() + " -> " + p);
            if (!ok) { failures.add(c.dir()); p.evidence().forEach(e -> System.out.println("       " + e)); }
        }
        for (int i = 1; i < a.length; i++) {
            var p = ProjectProfile.detect(Path.of(a[i]));
            System.out.println((p.isComplete() ? "OK   " : "??   ") + a[i] + " -> " + p);
            p.evidence().forEach(e -> System.out.println("       " + e));
        }
        System.out.println(failures.isEmpty() ? "all fixtures passed" : "FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
