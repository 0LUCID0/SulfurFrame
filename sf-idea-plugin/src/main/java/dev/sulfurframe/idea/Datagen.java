package dev.sulfurframe.idea;

import dev.sulfurframe.idea.profile.ProjectProfile;
import dev.sulfurframe.idea.profile.Workspace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Data generation from the IDE: run the project's datagen task, see which generated files changed, and hand them to
 * the running game like saved resources (copied into its resource folder, then a client reload and/or /reload).
 *
 * The task comes from the build script's run configs (a config whose name says data: Loom "datagen", ModDevGradle
 * "data" / "clientData", NeoGradle "data"), else the loader's usual one (Fabric runDatagen, NeoForge/Forge runData).
 * The output folder comes from the datagen arguments (-Dfabric-api.datagen.output-dir=..., --output ...), else the
 * usual places (src/main/generated, src/generated/resources).
 */
final class Datagen {
    record Result(boolean ok, List<Path> changed, int added, int removed, long ms, List<Path> outputs) {}

    /** "runDatagen" etc. (without the project path). */
    static String task(Workspace.Target t) {
        try {
            String best = null;
            int bestRank = Integer.MAX_VALUE;
            for (String name : Workspace.runConfigs(t.dir()).keySet()) {
                String n = name.toLowerCase(Locale.ROOT);
                int rank = n.equals("datagen") ? 0 : n.equals("clientdata") ? 1 : n.equals("data") ? 2 : n.contains("datagen") ? 3 : n.contains("data") ? 4 : -1;
                if (rank >= 0 && rank < bestRank) { best = name; bestRank = rank; }
            }
            if (best != null) return "run" + Character.toUpperCase(best.charAt(0)) + best.substring(1);
        } catch (IOException ignored) { }
        return t.profile().loader() == ProjectProfile.Loader.FABRIC ? "runDatagen" : "runData";
    }

    /** Where the datagen writes, existing or not yet. */
    static List<Path> outputs(Workspace.Target t) {
        var out = new LinkedHashSet<Path>();
        String script = read(t.dir().resolve("build.gradle")) + read(t.dir().resolve("build.gradle.kts"));
        // -Dfabric-api.datagen.output-dir=${file("src/main/generated")}   /   "--output", file("src/generated/resources").absolutePath
        Matcher m = Pattern.compile("(?:datagen\\.output-dir=\\$\\{\\s*file\\(|--output[\"']\\s*,\\s*file\\()\\s*[\"']([^\"']+)[\"']").matcher(script);
        while (m.find()) out.add(t.dir().resolve(m.group(1)).normalize());
        for (String d : new String[]{"src/main/generated", "src/generated/resources"}) {
            Path p = t.dir().resolve(d);
            if (Files.isDirectory(p)) out.add(p.normalize());
        }
        if (out.isEmpty()) out.add(t.dir().resolve(t.profile().loader() == ProjectProfile.Loader.FABRIC ? "src/main/generated" : "src/generated/resources"));
        return List.copyOf(out);
    }

    /** Runs the datagen task (blocking) and reports what it changed under the output folders. */
    static Result run(Workspace.Target t, Consumer<String> log) {
        long t0 = System.currentTimeMillis();
        String task = task(t);
        var outs = outputs(t);
        Path wrapper = GameLauncher.findWrapper(t.buildRoot());
        if (wrapper == null) { log.accept("datagen: no gradlew for " + t.where()); return new Result(false, List.of(), 0, 0, 0, outs); }
        var before = snapshot(outs);
        String path = t.projectPath().equals(":") ? task : t.projectPath() + ":" + task;
        log.accept("datagen: " + path + " -> " + String.join(", ", outs.stream().map(p -> t.dir().relativize(p).toString().replace('\\', '/')).toList()));
        boolean ok;
        try {
            var pb = GradleJava.apply(new ProcessBuilder(wrapper.toString(), path, "--console=plain")
                    .directory(t.buildRoot().toFile()).redirectErrorStream(true), t);
            var p = pb.start();
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                boolean why = false;
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("* What went wrong")) { why = true; continue; }
                    if (line.startsWith("* Try")) why = false;
                    if (why && !line.isBlank()) log.accept("[datagen] " + line.strip());
                    else if (line.contains("BUILD") || line.contains("ERROR") || line.contains("Exception")) log.accept("[datagen] " + line.strip());
                }
            }
            ok = p.waitFor() == 0;
        } catch (IOException e) {
            log.accept("datagen: could not start Gradle: " + e);
            return new Result(false, List.of(), 0, 0, System.currentTimeMillis() - t0, outs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, List.of(), 0, 0, System.currentTimeMillis() - t0, outs);
        }
        var after = snapshot(outs);
        var changed = new ArrayList<Path>();
        int added = 0, removed = 0;
        for (var e : after.entrySet()) {
            Long old = before.get(e.getKey());
            if (old == null) added++;
            if (!e.getValue().equals(old)) changed.add(e.getKey());
        }
        for (Path f : before.keySet()) if (!after.containsKey(f)) { changed.add(f); removed++; }
        return new Result(ok, changed, added, removed, System.currentTimeMillis() - t0, outs);
    }

    /** file -> CRC32 of its content (datagen may rewrite unchanged files; only real changes count). */
    private static Map<Path, Long> snapshot(List<Path> dirs) {
        var out = new HashMap<Path, Long>();
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.walk(d)) {
                s.filter(Files::isRegularFile).filter(f -> !d.relativize(f).startsWith(".cache")).forEach(f -> {   // datagen bookkeeping, not a resource
                    try { var c = new CRC32(); c.update(Files.readAllBytes(f)); out.put(f.toAbsolutePath().normalize(), c.getValue()); }
                    catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
        return out;
    }

    private static String read(Path f) {
        try { return Files.exists(f) ? ProjectProfile.stripComments(Files.readString(f)) : ""; } catch (IOException e) { return ""; }
    }

    private Datagen() {}
}
