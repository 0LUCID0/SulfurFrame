package dev.sulfurframe.idea;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sulfurframe.idea.profile.ProjectProfile;
import dev.sulfurframe.idea.profile.Workspace;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Mods every dev client should have (Mod Menu and the like), without touching the project: Modrinth projects picked
 * once in the IDE, downloaded for the target's Minecraft version (with their required dependencies) into
 * ~/.sulfurframe/devmods/&lt;mc&gt;/, and handed to Fabric Loader with -Dfabric.addMods. Fabric and Quilt only for now.
 *
 * A mod the project already brings (named in its build script or on its class path) is skipped: Fabric refuses two
 * copies of one mod id. Without network the last downloaded files are used.
 */
final class DevMods {
    /** One configured mod: Modrinth slug, and whether it is used. */
    record Entry(String slug, boolean on) {}

    enum Tier { REQUIRED, OPTIONAL, CUSTOM }

    /** A catalog mod: what it is for, on which loaders, and whether it is on unless turned off. */
    record Known(String slug, Tier tier, Set<ProjectProfile.Loader> loaders, String purpose) {}

    private static final Set<ProjectProfile.Loader> FABRIC = EnumSet.of(ProjectProfile.Loader.FABRIC, ProjectProfile.Loader.QUILT);
    private static final Set<ProjectProfile.Loader> ALL = EnumSet.of(ProjectProfile.Loader.FABRIC, ProjectProfile.Loader.QUILT,
            ProjectProfile.Loader.NEOFORGE, ProjectProfile.Loader.FORGE);

    /**
     * Built-in catalog (slugs and loaders checked on Modrinth). Required = on by default; optional = listed, off.
     * NeoForge/Forge need nothing required: their mod list and config screens are built in.
     * No WorldEdit: it is a big, opinionated mod; projects that want it add it themselves.
     */
    static final List<Known> CATALOG = List.of(
            new Known("modmenu", Tier.REQUIRED, FABRIC, "Mod list and config screens (Fabric has none built in)"),
            new Known("emi", Tier.OPTIONAL, ALL, "Recipe and item viewer: check your items and recipes"),
            new Known("jei", Tier.OPTIONAL, EnumSet.of(ProjectProfile.Loader.FABRIC, ProjectProfile.Loader.NEOFORGE, ProjectProfile.Loader.FORGE), "Recipe and item viewer (JEI)"),
            new Known("jade", Tier.OPTIONAL, ALL, "Shows the block / entity you look at and its mod"),
            new Known("spark", Tier.OPTIONAL, ALL, "Profiler: CPU, tick and memory reports"),
            new Known("mixintrace", Tier.OPTIONAL, FABRIC, "Adds mixin information to crash reports"),
            new Known("sodium", Tier.OPTIONAL, EnumSet.of(ProjectProfile.Loader.FABRIC, ProjectProfile.Loader.QUILT, ProjectProfile.Loader.NEOFORGE),
                    "Check your mod with the common renderer replacement (it may conflict with rendering code)"));

    static final String KEY = "sulfurframe.devmods";               // IDE-wide choices
    static final String PROJECT_KEY = "sulfurframe.devmods.project"; // a project's own choices (replace the IDE-wide ones)

    static Known known(String slug) { return CATALOG.stream().filter(k -> k.slug().equals(slug)).findFirst().orElse(null); }

    /** Stored choices of one scope (project null = IDE-wide); null if that scope has none of its own. */
    static List<Entry> stored(com.intellij.openapi.project.Project project) {
        String raw;
        try {
            raw = project == null ? com.intellij.ide.util.PropertiesComponent.getInstance().getValue(KEY)
                    : com.intellij.ide.util.PropertiesComponent.getInstance(project).getValue(PROJECT_KEY);
        } catch (Throwable outsideIde) {
            return null;
        }
        if (raw == null) return null;
        var out = new ArrayList<Entry>();
        for (String part : raw.split(",")) {
            String[] p = part.trim().split(":");
            if (!p[0].isBlank()) out.add(new Entry(p[0].trim(), p.length < 2 || !p[1].equals("off")));
        }
        return out;
    }

    /** project null = IDE-wide; entries null = remove that scope's own choices (a project then follows the IDE). */
    static void store(com.intellij.openapi.project.Project project, List<Entry> entries) {
        var props = project == null ? com.intellij.ide.util.PropertiesComponent.getInstance() : com.intellij.ide.util.PropertiesComponent.getInstance(project);
        String key = project == null ? KEY : PROJECT_KEY;
        if (entries == null) { props.unsetValue(key); return; }
        var sb = new StringBuilder();
        for (Entry e : entries) sb.append(sb.length() == 0 ? "" : ",").append(e.slug()).append(e.on() ? ":on" : ":off");
        props.setValue(key, sb.toString());
    }

    /** The catalog's defaults: every catalog mod, required ones on. */
    static List<Entry> defaults() {
        return CATALOG.stream().map(k -> new Entry(k.slug(), k.tier() == Tier.REQUIRED)).toList();
    }

    /** What applies to a project: its own choices if it has them, else the IDE-wide ones, else the defaults; catalog mods missing from a stored list keep their default. */
    static List<Entry> effective(com.intellij.openapi.project.Project project) {
        List<Entry> base = project == null ? null : stored(project);
        if (base == null) base = stored(null);
        if (base == null) return defaults();
        var out = new ArrayList<>(base);
        for (Entry d : defaults()) if (out.stream().noneMatch(e -> e.slug().equals(d.slug()))) out.add(d);
        return out;
    }
    /** "https://modrinth.com/mod/mod-menu" or "modmenu" -> the slug. */
    static String slugOf(String input) {
        String s = input.trim();
        int i = s.indexOf("modrinth.com/");
        if (i >= 0) {
            String[] parts = s.substring(i + "modrinth.com/".length()).split("[/?#]");
            if (parts.length >= 2) s = parts[1];
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** JVM arguments for the launch (empty when nothing applies). Blocking: may download. */
    static List<String> jvmArgs(com.intellij.openapi.project.Project project, Workspace.Target t, Consumer<String> log) {
        var loader = t.profile().loader();
        var enabled = effective(project).stream().filter(Entry::on).map(Entry::slug)
                .filter(s -> { var k = known(s); return k == null || k.loaders().contains(loader); }).toList();
        if (enabled.isEmpty()) return List.of();
        if (loader != ProjectProfile.Loader.FABRIC && loader != ProjectProfile.Loader.QUILT) {
            log.accept("dev mods: loading them on " + loader + " is not supported yet (Fabric and Quilt only): " + String.join(", ", enabled));
            return List.of();
        }
        String mc = t.profile().minecraftVersion();
        String loaderName = loader == ProjectProfile.Loader.QUILT ? "quilt" : "fabric";
        String known = projectText(t);
        var files = new LinkedHashMap<String, Path>();   // project id -> file
        var seen = new HashSet<String>();
        var names = new ArrayList<String>();
        for (String slug : enabled) add(slug, mc, loaderName, known, files, seen, names, log, 0);
        if (files.isEmpty()) return List.of();
        log.accept("dev mods: " + String.join(", ", names));
        var paths = files.values().stream().map(Path::toString).toList();
        return List.of("-Dfabric.addMods=" + String.join(java.io.File.pathSeparator, paths));
    }

    private static void add(String idOrSlug, String mc, String loader, String known, Map<String, Path> files, Set<String> seen,
                            List<String> names, Consumer<String> log, int depth) {
        if (depth > 4 || !seen.add(idOrSlug)) return;
        try {
            JsonObject project = get("https://api.modrinth.com/v2/project/" + enc(idOrSlug)).getAsJsonObject();
            String id = project.get("id").getAsString(), slug = project.get("slug").getAsString();
            if (!seen.add(id) && depth > 0) return;
            if (alreadyThere(known, slug, project)) {
                if (depth == 0) log.accept("dev mods: " + slug + " is already part of the project, not added again");
                return;
            }
            JsonArray versions = get("https://api.modrinth.com/v2/project/" + enc(id) + "/version?loaders=" + enc("[\"" + loader + "\"]")
                    + "&game_versions=" + enc("[\"" + mc + "\"]")).getAsJsonArray();
            if (versions.isEmpty()) { log.accept("dev mods: " + slug + " has no " + loader + " build for " + mc + ", skipped"); return; }
            JsonObject v = versions.get(0).getAsJsonObject();
            JsonObject file = primary(v.getAsJsonArray("files"));
            Path dir = cacheDir(mc);
            Path target = dir.resolve(file.get("filename").getAsString());
            if (!Files.isRegularFile(target)) {
                Files.createDirectories(dir);
                Path part = dir.resolve(target.getFileName() + ".part");
                try (InputStream in = client().send(request(file.get("url").getAsString()), HttpResponse.BodyHandlers.ofInputStream()).body()) {
                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                // older downloads of the same mod for this version are replaced
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(p -> !p.equals(target) && p.getFileName().toString().equals(remember(dir, slug))).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
                }
            }
            Files.writeString(dir.resolve(slug + ".txt"), target.getFileName().toString());
            files.put(id, target);
            names.add(slug + " " + v.get("version_number").getAsString() + (depth > 0 ? " (needed)" : ""));
            for (JsonElement d : v.getAsJsonArray("dependencies")) {
                JsonObject dep = d.getAsJsonObject();
                if (!"required".equals(dep.get("dependency_type").getAsString()) || dep.get("project_id").isJsonNull()) continue;
                add(dep.get("project_id").getAsString(), mc, loader, known, files, seen, names, log, depth + 1);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            // offline (or Modrinth down): use what was downloaded last time
            Path dir = cacheDir(mc);
            String last = remember(dir, idOrSlug);
            if (last != null && Files.isRegularFile(dir.resolve(last))) {
                files.put(idOrSlug, dir.resolve(last));
                names.add(idOrSlug + " (cached)");
            } else if (depth == 0) {
                log.accept("dev mods: could not get " + idOrSlug + " from Modrinth (" + e.getMessage() + ")");
            }
        }
    }

    /** The project names the mod (build script dependency) or has its jar on the class path SulfurFrame read. */
    private static boolean alreadyThere(String known, String slug, JsonObject project) {
        if (known.contains(slug)) return true;
        String id = slug.replace("-", "");
        if (known.contains(id)) return true;
        // Fabric API's slug is "fabric-api"; builds name it fabric-api / fabric_api / fabricApi / fabric-api-base ...
        return slug.equals("fabric-api") && (known.contains("fabric_api") || known.contains("fabricapi"));
    }

    /** Lower-case build scripts, gradle.properties and the class path list of the target, searched for mod names. */
    private static String projectText(Workspace.Target t) {
        var sb = new StringBuilder();
        for (Path f : new Path[]{t.dir().resolve("build.gradle"), t.dir().resolve("build.gradle.kts"), t.buildRoot().resolve("build.gradle"),
                t.buildRoot().resolve("build.gradle.kts"), t.buildRoot().resolve("gradle.properties"), t.buildRoot().resolve("gradle/libs.versions.toml")}) {
            try { if (Files.isRegularFile(f)) sb.append(Files.readString(f)).append('\n'); } catch (IOException ignored) { }
        }
        try {   // the class path FastCompile cached (jar names), if it has run
            Path cache = Path.of(System.getProperty("java.io.tmpdir"), "sulfurframe");
            if (Files.isDirectory(cache)) try (Stream<Path> s = Files.list(cache)) {
                for (Path p : s.filter(p -> p.getFileName().toString().startsWith("classpath")).toList()) {
                    String text = Files.readString(p);
                    if (text.contains(t.dir().toString())) sb.append(text);
                }
            }
        } catch (IOException ignored) { }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static JsonObject primary(JsonArray files) {
        for (JsonElement f : files) if (f.getAsJsonObject().get("primary").getAsBoolean()) return f.getAsJsonObject();
        return files.get(0).getAsJsonObject();
    }

    private static String remember(Path dir, String slug) {
        try { Path f = dir.resolve(slug + ".txt"); return Files.isRegularFile(f) ? Files.readString(f).trim() : null; } catch (IOException e) { return null; }
    }

    static Path cacheDir(String mc) { return SharedInstance.home().resolve("devmods").resolve(mc); }

    private static JsonElement get(String url) throws IOException, InterruptedException {
        var r = client().send(request(url), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) throw new IOException("not found on Modrinth");
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return JsonParser.parseString(r.body());
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "SulfurFrame-IDE-plugin").build();
    }

    private static HttpClient client;
    private static synchronized HttpClient client() {
        if (client == null) client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
        return client;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private DevMods() {}
}
