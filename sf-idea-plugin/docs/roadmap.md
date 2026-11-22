🌐 **English** · [한국어](roadmap.ko.md)

> Translated from the Korean original ([roadmap.ko.md](roadmap.ko.md)), which is the source of truth: if the two differ, the Korean one is right. Last synced: 2026-11-21.

# SulfurFrame goals (not promises)

No dates or versions are set (the marketing plan's "no promises" rule). These are directions; public documents only
describe what already works.

## Why some changes still need a restart: the JVM's part and ours

Hotswap already uses nearly everything the JVM allows (JBR class redefinition + recompiling the users of changed
constants + re-applying Mixins). The restarts that remain fall into two groups by cause.

| Cause | Whose part | Now |
|---|---|---|
| A frame that is already running keeps the old code until it returns | JVM (redefinition rules) | Cannot change. A change inside an endless loop applies when the loop ends |
| Static initialization (`<clinit>`) runs once per class | Java (language rules) | Cannot change in general (a restart is suggested, `StartupCode`). Registration code and enum constants run again (`RuntimeRegister`, `ClinitReplay`) |
| Compile-time constants are copied into the classes that read them | javac | Already worked around: the users' sources are recompiled and sent along |
| Registering new blocks, items and the like | **Minecraft** (freezes its registries after startup) | Solved (Fabric 1.21.1): the registries are unfrozen for a moment and the registration code runs again → goal 2 |
| Mod metadata | **Our implementation** | Solved: filled in with the last build's values, restart without Gradle (`MetadataTemplate`) |
| Access wideners, class tweakers | **Our implementation** | Solved: Fabric Loader applies them at start, only the classes for compiling are widened here, restart without Gradle (`AwOverlay`) |
| Outputs of deleted sources and resources stay behind; Windows command line length | **Our implementation** (restart without Gradle) | Cleaned up on the next Gradle run; a long class path goes through `CLASSPATH` |
| A JDK or Minecraft type added to or removed from the hierarchy, a superclass back to `Object` | **JBR** (adding one nulled unrelated fields and crashed; going back is refused) | A restart is suggested |

## Goal 1: metadata changes without Gradle too (done)

- **Done:** instead of reading the build settings, mod metadata is filled in by lining the last Gradle output up
  against its source to read the values, then filling the changed source (`MetadataTemplate`). Self-test: edit
  `fabric.mod.json` and restart → no Gradle, the output has the edit and the filled-in version.
- **Done:** access wideners did not need Loom remapping. Fabric Loader applies the mod's widener itself as the game
  starts, so it is copied to the output folder, and only the classes compiling needs are written with the new access
  (`AwOverlay`). Self-test: widen a Minecraft field, make the probe read it directly, restart → no Gradle, the probe's
  output in the game log. The original plan follows.

- The plugin fills the `${...}` values of `fabric.mod.json`, `quilt.mod.json` and `mods.toml` itself and writes them to
  the output folder. The values come from the build's `processResources` settings (`expand`/`filesMatching`). Settings
  it cannot read go through Gradle as today.
- Restarts in that case then skip Gradle too: 81 s → 64 s on the multi-project mod (`measure-relaunch.sh`).
- Access wideners and class tweakers need Loom to remap the game jar again, so they are looked into separately.
  Metadata comes first.
- Check: the self-test's "fabric.mod.json change → through Gradle" becomes "without Gradle, and the changed value shows
  in the game".

## Goal 2: registering while the game runs (done on Fabric 1.21.1, 0.3.0)

- **Done:** new blocks and items (every registry), a changed `onInitialize`/`onInitializeClient` (event and reload
  listeners, payload types, renderers, screens, commands and key bindings, none hooked up twice), new enum constants,
  hierarchy changes between the project's own types, and mixins added to a `*mixins.json` apply without a restart
  (`RuntimeRegister`, `EntryRerun`, `ClinitReplay`, `MixinAdd`). What broke in the first experiment was the item model (the
  item renderer lists items at startup) and the creative tabs (cached). How it works and its limits: "Startup code in the
  running game too" in the plugin README.
- Scope: single-player dev clients. Multiplayer and release builds are out of scope.
- Other Minecraft versions and loaders (the Mojang mapping names are in the tables but untested) go together with the
  multi-version work of the bigger mod developed alongside, like loader and version support.
- Safety net (done): the open world is snapshotted automatically right before a run (`before-init`, the newest 3 per
  world). If the run fails in the game or the game crashes right after, a restart into the world from before is offered.

## Other candidates

- ~~First-run experience: install JBR 21 straight from the tool window when it is missing.~~ Done in 0.2.0 (one-click
  JetBrains Runtime download).
- Use real usage records (`UsageLog`, `usage.py`) to find where in the measured range the effect actually lands.
