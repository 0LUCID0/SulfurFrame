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
| Static initialization (`<clinit>`) runs once per class | Java (language rules) | Cannot change. A restart is suggested when it changes (`StartupCode`) |
| Compile-time constants are copied into the classes that read them | javac | Already worked around: the users' sources are recompiled and sent along |
| Registering new blocks, items and the like | **Minecraft** (freezes its registries after startup) | Restart → goal 2 |
| Mod metadata | **Our implementation** | Solved: filled in with the last build's values, restart without Gradle (`MetadataTemplate`) |
| Access wideners, class tweakers | **Our implementation** | Solved: Fabric Loader applies them at start, only the classes for compiling are widened here, restart without Gradle (`AwOverlay`) |
| Outputs of deleted sources and resources stay behind; Windows command line length | **Our implementation** (restart without Gradle) | Cleaned up on the next Gradle run; a long class path goes through `CLASSPATH` |

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

## Goal 2: registering while the game runs (far, research first)

- Unfreeze the registries → register the new entries → reload models, tags and translations → sync client and server.
- Scope: single-player dev clients only. Multiplayer and release builds are out of scope.
- Safety net: a snapshot of the open world is taken automatically before starting (`WorldSnapshots`). On failure the
  game restarts from that snapshot.
- Registry internals differ between Minecraft versions, so like loader and version support, this goes together with
  the multi-version work of the bigger mod developed alongside.
- First step: register one block at runtime on Fabric 1.21.1 and list what breaks (item groups, block states, render
  layers, network sync IDs).

## Other candidates

- ~~First-run experience: install JBR 21 straight from the tool window when it is missing.~~ Done in 0.2.0 (one-click
  JetBrains Runtime download).
- Use real usage records (`UsageLog`, `usage.py`) to find where in the measured range the effect actually lands.
