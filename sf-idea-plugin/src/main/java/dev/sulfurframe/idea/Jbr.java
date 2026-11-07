package dev.sulfurframe.idea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * JetBrains Runtime for the game, so hotswap can do more than method bodies.
 *
 * A standard JVM's redefineClasses only accepts changed method bodies. JBR has DCEVM built in: with
 * -XX:+AllowEnhancedClassRedefinition the same call also accepts added/removed methods and fields (and changed
 * hierarchies). So Run client starts the project's runClient task on a JBR of the Java version the game needs, with
 * that flag, through a Gradle init script (the project's build files stay untouched).
 *
 * Where a JBR comes from: IntelliJ's ~/.jdks (File | Project Structure | SDKs | download "JetBrains Runtime"), or the
 * IDE's own runtime when its version matches (IntelliJ 2026.x runs on JBR 25, which is what Minecraft 26.x needs).
 */
final class Jbr {
    /** java(.exe) of a JBR with exactly this feature version, or null. */
    static Path find(int major) {
        var candidates = new ArrayList<Path>();
        Path jdks = Path.of(System.getProperty("user.home"), ".jdks");
        if (Files.isDirectory(jdks)) {
            try (Stream<Path> s = Files.list(jdks)) { s.sorted(Comparator.reverseOrder()).forEach(candidates::add); } catch (IOException ignored) { }
        }
        candidates.add(Path.of(System.getProperty("java.home")));
        for (Path home : candidates) {
            if (isJbr(home, major)) {
                Path java = home.resolve("bin").resolve(System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
                if (Files.isRegularFile(java)) return java;
            }
        }
        return null;
    }

    /** From the JDK's release file: IMPLEMENTOR="JetBrains s.r.o." and JAVA_VERSION="21.x". */
    static boolean isJbr(Path home, int major) {
        try {
            var props = new Properties();
            try (var r = Files.newBufferedReader(home.resolve("release"), StandardCharsets.UTF_8)) { props.load(r); }
            String impl = strip(props.getProperty("IMPLEMENTOR", "")), ver = strip(props.getProperty("JAVA_VERSION", ""));
            return impl.contains("JetBrains") && (ver.equals(Integer.toString(major)) || ver.startsWith(major + "."));
        } catch (IOException e) {
            return false;
        }
    }

    private static String strip(String s) { return s.replace("\"", "").trim(); }

    /** Groovy init script: runClient-like JavaExec tasks run on the JBR given as -PsfJbr=..., with enhanced redefinition. */
    static final String INIT_SCRIPT = """
            def sfJbr = gradle.startParameter.projectProperties['sfJbr']
            if (sfJbr != null) allprojects {
                tasks.withType(JavaExec).configureEach { t ->
                    if (t.name ==~ /(?i).*runclient.*/) {
                        try { t.javaLauncher.set((org.gradle.jvm.toolchain.JavaLauncher) null) } catch (Throwable ignored) { }
                        t.executable = sfJbr
                        t.jvmArgs('-XX:+AllowEnhancedClassRedefinition')
                        // Mixin's own hotswap agent: re-applies a changed mixin to its targets when the mixin is redefined
                        t.doFirst {
                            def mixin = t.classpath.files.find { it.name ==~ /(sponge-)?mixin-[0-9].*\\.jar/ }
                            if (mixin != null) t.jvmArgs("-javaagent:" + mixin.absolutePath, '-Dmixin.hotSwap=true')
                        }
                    }
                }
            }
            """;

    private Jbr() {}
}
