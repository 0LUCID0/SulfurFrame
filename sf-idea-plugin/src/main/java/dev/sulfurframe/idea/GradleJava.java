package dev.sulfurframe.idea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Which JDK runs Gradle itself (gradlew reads JAVA_HOME).
 *
 * The IDE passes its own environment on, so gradlew gets the system JAVA_HOME. That breaks when the system JDK is
 * older than what the build's plugins need: Loom for Minecraft 26.x refuses a Java 21 Gradle ("Dependency requires
 * at least JVM runtime version 25"). So when JAVA_HOME is older than the game's Java (or unset), we hand gradlew a
 * JDK that is new enough. A project that pins its Gradle JVM itself (org.gradle.java.home, or a daemon toolchain) gets
 * its daemon from Gradle, but gradlew's own launcher still starts on JAVA_HOME: that one only has to be able to start
 * Gradle 9 (Java 17), so a Java 8 JAVA_HOME is replaced there too.
 */
final class GradleJava {
    /** The oldest Java that starts the Gradle 9 launcher (gradlew) at all. */
    static final int LAUNCHER_MIN = 17;

    /** JDK home for JAVA_HOME, or null to keep the environment as it is. */
    static Path pick(Path buildRoot, int major, Path jbrJava) {
        int need = needed(buildRoot, major);
        String env = System.getenv("JAVA_HOME");
        if (env != null && !env.isBlank() && version(Path.of(env)) >= need) return null;
        major = need;
        var candidates = new ArrayList<Path>();
        if (jbrJava != null) candidates.add(jbrJava.getParent().getParent());
        addChildren(candidates, Path.of(System.getProperty("user.home"), ".jdks"));
        candidates.add(Path.of(System.getProperty("java.home")));
        for (String pf : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramW6432")}) {
            if (pf == null) continue;
            for (String vendor : new String[]{"Eclipse Adoptium", "Java", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft"})
                addChildren(candidates, Path.of(pf, vendor));
        }
        Path best = null;
        int bestVer = Integer.MAX_VALUE;
        for (Path home : candidates) {   // the lowest version that is new enough: closest to what the build expects
            int v = version(home);
            if (v >= major && v < bestVer && Files.isRegularFile(home.resolve("bin").resolve(exe()))) { best = home; bestVer = v; }
        }
        return best;
    }

    /** Same choice for any gradlew run of this target (class path scan, hotswap compile): one daemon for all. */
    static ProcessBuilder apply(ProcessBuilder pb, dev.sulfurframe.idea.profile.Workspace.Target t) {
        Path jdk = pick(t.buildRoot(), t.profile().javaMajor(), Jbr.find(t.profile().javaMajor()));
        if (jdk != null) pb.environment().put("JAVA_HOME", jdk.toString());
        return pb;
    }

    /**
     * The build chose its Gradle JVM: org.gradle.java.home, or Gradle's daemon toolchain file
     * (gradle/gradle-daemon-jvm.properties, toolchainVersion=25), where Gradle itself picks a matching local JDK. A
     * Loom 1.19+ build for Minecraft 1.21.x needs a Java 25 Gradle though its game is Java 21: by the game's version
     * alone we would hand it JDK 21.
     */
    static int needed(Path root, int major) {
        return pinned(root) ? LAUNCHER_MIN : major;
    }

    private static boolean pinned(Path root) {
        return property(root, "org.gradle.java.home") != null || Files.isRegularFile(root.resolve("gradle").resolve("gradle-daemon-jvm.properties"));
    }

    /** A Gradle property from the user's ~/.gradle/gradle.properties (wins, as in Gradle) or the build's; null if unset. */
    static String property(Path root, String key) {
        for (Path f : new Path[]{Path.of(System.getProperty("user.home"), ".gradle", "gradle.properties"), root.resolve("gradle.properties")}) {
            try {
                if (!Files.isRegularFile(f)) continue;
                var props = new Properties();
                try (var r = Files.newBufferedReader(f, StandardCharsets.ISO_8859_1)) { props.load(r); }
                if (props.getProperty(key) != null) return props.getProperty(key).trim();
            } catch (IOException ignored) { }
        }
        return null;
    }

    /** Feature version from the JDK's release file (JAVA_VERSION="25.0.1", or "1.8.0_x" -> 8); 0 if unknown. */
    static int version(Path home) {
        try {
            var props = new Properties();
            try (var r = Files.newBufferedReader(home.resolve("release"), StandardCharsets.UTF_8)) { props.load(r); }
            String v = props.getProperty("JAVA_VERSION", "").replace("\"", "").trim();
            if (v.startsWith("1.")) v = v.substring(2);
            int end = 0;
            while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
            return end == 0 ? 0 : Integer.parseInt(v.substring(0, end));
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static void addChildren(List<Path> out, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) { s.sorted(Comparator.reverseOrder()).forEach(out::add); } catch (IOException ignored) { }
    }

    private static String exe() { return System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java"; }

    private GradleJava() {}
}
