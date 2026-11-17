# SulfurFrame

An IntelliJ IDEA plugin that runs your Minecraft mod's dev client **inside the IDE**: the game window is embedded in a
tool window (or an editor tab), throttled while you type, and changed code is hotswapped into it on save.

- Embedded game window, throttled to a low frame rate while you edit and parked while hidden
- Starts in the background: the game does not take the focus or the mouse from the app you are in
- Hotswap on save (also for files changed outside the IDE), "Restart into the Same World"
- Restarts skip Gradle while the build is unchanged: the game starts again from its last command line
- Minecraft version and loader detected from the build files (multi-project and Stonecutter-style layouts too)
- World snapshots, throwaway test worlds, worlds shared by every project of a Minecraft version
- F11 fullscreen leaves the IDE and comes back embedded

**Status:** 0.1.2. Fabric 1.21.1 dev clients on Windows and Linux (X11), IntelliJ IDEA 2026.3 or newer.
It grew up next to a much bigger mod that needs every loader and many Minecraft versions, so Forge, NeoForge and the
other versions will follow as that one gets there.

## What it saves

Most edits never need the game to restart. To see how many, 1,080 recent commits of nine large open-source mods
(Fabric API, Sodium, Lithium, Iris, Create, Applied Energistics 2, Botania, Farmer's Delight, Mod Menu) were sorted by
what a running client needs to pick each change up. Then the time each path takes was measured on one machine, on a
small test mod and on a real multi-project mod (9 Gradle projects, 65 mods):

| Waiting per edit-run step | Small mod | Multi-project mod |
|---|---|---|
| Restart every time (`runClient` straight into the world) | 44 s | 83 s |
| Stock HotSwap (Gradle compile, restart for anything structural) | 26–30 s | 50–57 s |
| Stock HotSwap on a JetBrains Runtime (structural changes apply too) | 20–25 s | 39–49 s |
| **SulfurFrame** | **8–17 s** | **11–30 s** |

That is 63–87% less waiting than restarting, 44–78% less than stock HotSwap and 35–72% less than stock HotSwap on a
JetBrains Runtime. The JBR row differs from stock HotSwap only in that added or removed members swap live; it still
compiles through Gradle, and a changed mixin is not re-applied to its targets without Mixin's hotswap agent (which
SulfurFrame adds), so mixins count as restarts there. The range runs from counting a commit
as one step (its slowest change decides) to counting each changed file as one step. 65–85% of the steps apply live:
method bodies and new members (with a JetBrains Runtime, see Install), mixins, and assets or data.

The rest needs a restart (registrations, static initializers, mod metadata). A restart skips Gradle and starts the game
from the command line it was last started with, unless build files, mod metadata or access wideners changed since.
Code and resources saved in the meantime are compiled and copied first. On the multi-project mod that brought a restart
from 81 s to 64 s; on the small mod it made almost no difference (32 s to 30 s), because its Gradle is quick. The commit
sample cannot tell which restarts would skip Gradle, so each range's high end counts commits with every restart
through Gradle, and its low end counts files with every restart skipping it. The gain grows with how slow your project's Gradle is. Method, scripts and raw numbers:
[`sf-idea-plugin/docs/effect-study`](sf-idea-plugin/docs/effect-study) and the plugin README.

## Install

- From JetBrains Marketplace (submitted, waiting for JetBrains' review): *Settings → Plugins → Marketplace*, search
  for **SulfurFrame**.
- From a release zip: download `SulfurFrame-<version>.zip` from
  [Releases](https://github.com/0LUCID0/Sulfur_Frame/releases), then *Settings → Plugins → ⚙ → Install Plugin from Disk…*.

Open a Fabric mod project, then use the **SulfurFrame** tool window's *Run client*.

**For structural hotswap, install a JetBrains Runtime (JBR) of the game's Java version.** Run client starts the game
on JBR with `-XX:+AllowEnhancedClassRedefinition`, so adding or removing methods and fields swaps in live too. On a
regular JVM only method body changes apply, and SulfurFrame names the classes that need a restart. Minecraft 1.21.1
needs JBR 21: in IntelliJ, *File → Project Structure → SDKs → + → Download JDK…*, vendor **JetBrains Runtime**,
version 21. SulfurFrame looks for it in `~/.jdks`, and uses the IDE's own JBR 25 for Minecraft 26.x. Your build files
stay untouched: a Gradle init script swaps the game's launcher for that run only.

SulfurFrame keeps its own log of what it did (swaps, reloads, restarts) in `~/.sulfurframe/logs` for 14 days, so you
can look back at your sessions. It stays on your machine; nothing is sent anywhere.

## Build

```bash
./gradlew buildPlugin          # → sf-idea-plugin/build/distributions/SulfurFrame-<version>.zip
./gradlew check verifyPlugin   # fixture self-tests + JetBrains Plugin Verifier
```

JDK 25 is needed (Gradle finds it in `~/.jdks` or a standard install). The build downloads IntelliJ IDEA
`platformVersion` from `gradle.properties`; to use an installed IDE instead, pass `-PideaHome=<install dir>` or set
`IDEA_HOME`.

Release: set `PUBLISH_TOKEN` (and optionally `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` for signing),
then `./gradlew publishPlugin`.

## Layout

- `sf-idea-plugin/` – the plugin (design notes and test results in its [README](sf-idea-plugin/README.md), Korean)
- `poc/agent/` – the Java agent loaded into the game (frame cap, focus fixes, stats), bundled into the plugin
- `poc/embed/`, `poc/profile/` – proofs of concept and the options sync core

## License

[MIT](LICENSE). Not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB.
