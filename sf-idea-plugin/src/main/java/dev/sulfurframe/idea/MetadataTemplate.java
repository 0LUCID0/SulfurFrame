package dev.sulfurframe.idea;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mod metadata (fabric.mod.json, quilt.mod.json, mods.toml) without Gradle. processResources fills placeholders such as
 * "${version}" with values from the build script ({@code expand "version": project.version}), which can be any Groovy
 * expression, so the plugin cannot compute them itself. But the last Gradle run wrote the filled-in file to the output
 * folder: lined up against the template it was made from, it gives every placeholder's value. A changed template is
 * then filled with those values the way Gradle would.
 *
 * Only what Gradle's expand does for plain names is handled: {@code ${name}} and {@code $name} (also dotted, a.b).
 * Anything else that SimpleTemplateEngine would evaluate ({@code ${expr}}, {@code <% %>}, a {@code \$} escape), a
 * template that does not line up with the output, or a placeholder the last build did not fill: null, and the restart
 * goes through Gradle as before.
 */
final class MetadataTemplate {
    /** Files Minecraft's loaders read as mod metadata (processResources usually expands them). */
    static boolean isMetadata(String fileName) {
        return fileName.equals("fabric.mod.json") || fileName.equals("quilt.mod.json") || fileName.endsWith("mods.toml");
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\}|\\$([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)");

    /** A template split into literal text and placeholder names, alternating (literal, name, literal, ...); null if unsupported. */
    private static List<String> parse(String template) {
        if (template.contains("<%") || template.contains("\\$")) return null;
        var parts = new ArrayList<String>();
        Matcher m = PLACEHOLDER.matcher(template);
        int at = 0;
        while (m.find()) {
            parts.add(template.substring(at, m.start()));
            parts.add(m.group(1) != null ? m.group(1) : m.group(2));
            at = m.end();
        }
        String tail = template.substring(at);
        if (tail.indexOf('$') >= 0) return null;                    // a '$' that is not a plain placeholder
        for (int i = 0; i < parts.size(); i += 2) if (parts.get(i).indexOf('$') >= 0) return null;
        parts.add(tail);
        return parts;
    }

    /**
     * The value of every placeholder of {@code template}, read from {@code output} (what Gradle made of it), or null if
     * the template is unsupported or does not line up. A template without placeholders gives an empty map when the
     * output is the same text.
     */
    static Map<String, String> learn(String template, String output) {
        var parts = parse(template);
        if (parts == null) return null;
        var regex = new StringBuilder("\\A");
        var groups = new HashMap<String, String>();   // name -> regex group name
        for (int i = 0; i < parts.size(); i++) {
            if (i % 2 == 0) { regex.append(Pattern.quote(parts.get(i))); continue; }
            String name = parts.get(i);
            String g = groups.get(name);
            if (g != null) regex.append("\\k<").append(g).append('>');   // the same name twice: the same value
            else { g = "g" + groups.size(); groups.put(name, g); regex.append("(?<").append(g).append(">.*?)"); }
        }
        regex.append("\\z");
        Matcher m = Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(output);
        if (!m.matches()) return null;
        var values = new LinkedHashMap<String, String>();
        for (var e : groups.entrySet()) values.put(e.getKey(), m.group(e.getValue()));
        return values;
    }

    /** {@code template} with its placeholders filled from {@code values}; null if unsupported or a value is missing. */
    static String render(String template, Map<String, String> values) {
        var parts = parse(template);
        if (parts == null) return null;
        var out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i % 2 == 0) { out.append(parts.get(i)); continue; }
            String v = values.get(parts.get(i));
            if (v == null) return null;
            out.append(v);
        }
        return out.toString();
    }

    /** Placeholder names used by {@code template} (empty if none or unsupported). */
    static Set<String> names(String template) {
        var parts = parse(template);
        var names = new LinkedHashSet<String>();
        if (parts != null) for (int i = 1; i < parts.size(); i += 2) names.add(parts.get(i));
        return names;
    }

    private MetadataTemplate() {}
}
