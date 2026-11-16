package dev.sulfurframe.idea.profile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Workspace.scan against the layout fixtures, then any real folders given.
 *   java -cp out dev.sulfurframe.idea.profile.WorkspaceSelfTest <workspaces fixtures dir> [real folders...]
 */
public class WorkspaceSelfTest {
    public static void main(String[] a) throws Exception {
        Path fx = Path.of(a[0]);
        // expected: "<runTask> <LOADER> <mc> <loaderVersion>" per target, in scan order
        var cases = List.of(
                new Object[]{"container", List.of(
                        "runClient FABRIC 1.21.1 0.17.2", "runClient NEOFORGE 1.21.1 21.1.265")},
                new Object[]{"multiproject", List.of(
                        ":core:mod-fabric-1_21_1:runClient FABRIC 1.21.1 0.16.10",
                        ":core:mod-fabric-1_20_1:runClient FABRIC 1.20.1 0.15.11")},
                new Object[]{"architectury", List.of(
                        ":fabric:runClient FABRIC 1.21.1 0.16.10", ":neoforge:runClient NEOFORGE 1.21.1 21.1.265")},
                new Object[]{"stonecutter", List.of(
                        ":1.21.1:runClient FABRIC 1.21.1 0.16.10", ":1.20.1:runClient FABRIC 1.20.1 0.16.10")},
                // a run config of the project's own (all modules' mods) comes first, though ":addon" sorts before ":core"
                new Object[]{"runconfig", List.of(
                        ":core:mod-fabric:runAllModsClient FABRIC 1.21.1 0.16.10",
                        ":addon:mod-fabric:runClient FABRIC 1.21.1 0.16.10", ":core:mod-fabric:runClient FABRIC 1.21.1 0.16.10")});
        var failures = new ArrayList<String>();
        for (Object[] c : cases) {
            var got = new ArrayList<String>();
            for (var t : Workspace.scan(fx.resolve((String) c[0])))
                got.add(t.runTask() + " " + t.profile().loader() + " " + t.profile().minecraftVersion() + " " + t.profile().loaderVersion());
            boolean ok = got.equals(c[1]);
            System.out.println((ok ? "PASS " : "FAIL ") + c[0] + " -> " + got);
            if (!ok) { failures.add((String) c[0]); System.out.println("     expected " + c[1]); }
        }
        for (int i = 1; i < a.length; i++) {
            System.out.println("REAL " + a[i]);
            for (var t : Workspace.scan(Path.of(a[i]))) System.out.println("     " + t.label() + "   [" + t.runTask() + "]");
        }
        System.out.println(failures.isEmpty() ? "all layouts passed" : "FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
