package dev.sulfurframe.idea;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mod metadata filled in without Gradle: placeholders learned from the last processResources output, then a changed
 * template filled with them; anything Gradle's expand would evaluate beyond plain names is refused (null).
 *   java -cp out dev.sulfurframe.idea.MetadataTemplateSelfTest
 */
public class MetadataTemplateSelfTest {
    public static void main(String[] a) {
        var failures = new ArrayList<String>();
        String tpl = "{\n  \"id\": \"showcase\",\n  \"version\": \"${version}\",\n  \"name\": \"Showcase\"\n}\n";
        String out = "{\n  \"id\": \"showcase\",\n  \"version\": \"1.0.0+mc1.21.1\",\n  \"name\": \"Showcase\"\n}\n";
        expect(failures, "learn ${version}", MetadataTemplate.learn(tpl, out), Map.of("version", "1.0.0+mc1.21.1"));

        String edited = tpl.replace("\"Showcase\"", "\"Showcase edited\"");
        expect(failures, "render an edited template", MetadataTemplate.render(edited, Map.of("version", "1.0.0+mc1.21.1")),
                out.replace("\"Showcase\"", "\"Showcase edited\""));

        // mods.toml style: $name and dotted names, the same name twice
        String toml = "version=\"${mod_version}\"\nloaderVersion=\"$loader.range\"\n[[dependencies.${mod_id}]]\nmodId=\"${mod_id}\"\n";
        String tomlOut = "version=\"2.3\"\nloaderVersion=\"[4,)\"\n[[dependencies.cool]]\nmodId=\"cool\"\n";
        expect(failures, "learn $name, dotted, repeated", MetadataTemplate.learn(toml, tomlOut),
                Map.of("mod_version", "2.3", "loader.range", "[4,)", "mod_id", "cool"));
        expect(failures, "repeated name with two values does not line up",
                MetadataTemplate.learn(toml, tomlOut.replace("modId=\"cool\"", "modId=\"other\"")), null);

        // no placeholders: the output must be the same text
        expect(failures, "plain file, same output", MetadataTemplate.learn("{\"a\":1}", "{\"a\":1}"), Map.of());
        expect(failures, "plain file, different output", MetadataTemplate.learn("{\"a\":1}", "{\"a\":2}"), null);

        // refused: Groovy beyond plain names, an escape, a new placeholder
        expect(failures, "expression ${a + b}", MetadataTemplate.learn("v=${a + b}", "v=3"), null);
        expect(failures, "scriptlet <% %>", MetadataTemplate.learn("<% x %>v", "v"), null);
        expect(failures, "escaped \\$", MetadataTemplate.learn("p=\\$x", "p=$x"), null);
        expect(failures, "new placeholder not in the last build", MetadataTemplate.render("v=${version} n=${name}", Map.of("version", "1")), null);
        expect(failures, "names of a template", MetadataTemplate.names(toml), java.util.Set.of("mod_version", "loader.range", "mod_id"));

        if (failures.isEmpty()) System.out.println("metadata template checks passed");
        else { failures.forEach(f -> System.out.println("FAIL " + f)); System.exit(1); }
    }

    private static void expect(List<String> failures, String what, Object got, Object want) {
        if (Objects.equals(got, want)) System.out.println("PASS " + what + " -> " + got);
        else failures.add(what + ": got " + got + ", want " + want);
    }
}
