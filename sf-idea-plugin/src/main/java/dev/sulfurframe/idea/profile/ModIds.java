package dev.sulfurframe.idea.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The mod IDs a project builds, from its own metadata (nothing is executed): fabric.mod.json / quilt.mod.json "id",
 * META-INF/mods.toml and neoforge.mods.toml modId, mcmod.info "modid" (1.7-1.12 Forge).
 * The options sync leaves these mods' key bindings alone in the project's dev runs, so the game always shows the
 * defaults from the code.
 */
public final class ModIds {
    private static final Pattern ID = Pattern.compile(
            "\"id\"\\s*:\\s*\"([a-z0-9_.-]+)\"|modId\\s*=\\s*\"([a-z0-9_.-]+)\"|\"modid\"\\s*:\\s*\"([a-z0-9_.-]+)\"");
    private static final Set<String> FILES = Set.of("fabric.mod.json", "quilt.mod.json", "mods.toml", "neoforge.mods.toml", "mcmod.info");

    public static Set<String> of(Workspace.Target t) {
        var ids = new TreeSet<>(of(t.dir()));
        if (!t.task().equals("runClient") || ids.isEmpty()) {
            try (Stream<Path> s = Files.walk(t.buildRoot(), 4)) {
                s.filter(Files::isDirectory)
                 .filter(d -> d.getFileName() != null && d.getFileName().toString().equals("src"))
                 .filter(d -> {
                     for (Path part : t.buildRoot().relativize(d)) {
                         String n = part.toString();
                         if (n.equals("build") || n.equals("run") || n.equals("out") || n.equals("node_modules") || n.startsWith(".")) return false;
                     }
                     return true;
                 })
                 .forEach(src -> ids.addAll(of(src.getParent())));
            } catch (IOException ignored) { }
        }
        return ids;
    }

    public static Set<String> of(Path projectDir) {
        var ids = new TreeSet<String>();
        Path src = projectDir.resolve("src");
        if (!Files.isDirectory(src)) return ids;
        try (Stream<Path> s = Files.walk(src, 6)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                if (!FILES.contains(f.getFileName().toString())) continue;
                Matcher m = ID.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) ids.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
            }
        } catch (IOException ignored) { }
        // ${mod_id}-style placeholders resolved by processResources are not IDs: drop anything that is not one
        ids.removeIf(id -> id.isBlank() || id.contains("$"));
        return ids;
    }

    private ModIds() {}
}
