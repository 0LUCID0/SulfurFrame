package dev.sulfurframe.idea;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * options.txt as SulfurFrame prepares it before a quick-play launch: it must always carry a version: line, or the game
 * reads it as data version 0, its options fixers break on the synced key bindings and every option is reset.
 *   java -cp out dev.sulfurframe.idea.OptionsFileSelfTest
 */
public class OptionsFileSelfTest {
    public static void main(String[] a) throws Exception {
        var failures = new ArrayList<String>();
        // data version lookup: exact, between two entries (nearest older), newer than the table, below it
        expect(failures, "1.21.1 exact", TestWorld.nearestDataVersion("1.21.1"), 3955);
        expect(failures, "1.20.1 exact", TestWorld.nearestDataVersion("1.20.1"), 3465);
        expect(failures, "1.18.1 -> 1.17.1", TestWorld.nearestDataVersion("1.18.1"), 2730);
        expect(failures, "1.21.10 -> newest", TestWorld.nearestDataVersion("1.21.10"), 4189);
        expect(failures, "26.1 -> newest", TestWorld.nearestDataVersion("26.1"), 4189);
        expect(failures, "1.12.2 -> none", TestWorld.nearestDataVersion("1.12.2"), -1);

        Path dir = Files.createTempDirectory("sf-options-test");
        try {
            // 1. a fresh run folder: the file is created with version: and the onboarding switch
            Path fresh = dir.resolve("fresh");
            SharedInstance.skipAccessibilityOnboarding(fresh, "1.21.1", s -> { });
            check(failures, "fresh run folder", Files.readAllLines(fresh.resolve("options.txt")),
                    List.of("version:3955", "onboardAccessibility:false"));

            // 2. a file an older SulfurFrame made (no version:, synced keys added since): repaired, nothing else changed
            Path broken = dir.resolve("broken");
            Files.createDirectories(broken);
            Files.write(broken.resolve("options.txt"), List.of("onboardAccessibility:false", "key_key.jump:key.keyboard.space"));
            SharedInstance.skipAccessibilityOnboarding(broken, "1.21.1", s -> { });
            check(failures, "file without version: repaired", Files.readAllLines(broken.resolve("options.txt")),
                    List.of("version:3955", "onboardAccessibility:false", "key_key.jump:key.keyboard.space"));

            // 3. a file the game wrote: its own version stays, only the switch changes
            Path game = dir.resolve("game");
            Files.createDirectories(game);
            Files.write(game.resolve("options.txt"), List.of("version:3955", "onboardAccessibility:true", "fov:0.0"));
            SharedInstance.skipAccessibilityOnboarding(game, "1.21.1", s -> { });
            check(failures, "game's file keeps its version", Files.readAllLines(game.resolve("options.txt")),
                    List.of("version:3955", "onboardAccessibility:false", "fov:0.0"));
        } finally {
            try (var s = Files.walk(dir)) { for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }

        if (failures.isEmpty()) System.out.println("options.txt checks passed");
        else { failures.forEach(f -> System.out.println("FAIL " + f)); System.exit(1); }
    }

    private static void expect(List<String> failures, String what, int got, int want) {
        if (got == want) System.out.println("PASS " + what + " -> " + got);
        else failures.add(what + ": got " + got + ", want " + want);
    }

    private static void check(List<String> failures, String what, List<String> got, List<String> want) {
        if (got.equals(want)) System.out.println("PASS " + what + " -> " + got);
        else failures.add(what + ": got " + got + ", want " + want);
    }
}
