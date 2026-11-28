---
name: sulfurframe
description: >-
  Check code and resource changes in the running Minecraft dev client without restarting it, through SulfurFrame's
  `sf` command: hotswap after an edit (`sf wait`), run commands in the world (`sf cmd`), read the game log (`sf log`),
  take a screenshot (`sf shot`), restart into the same world (`sf restart`), or start the client without IntelliJ
  (`sf run`). Use whenever a change to a Fabric mod should be seen working in the game, and report SulfurFrame problems
  with `sf feedback`.
---

# SulfurFrame: the running game for agents

SulfurFrame (an IntelliJ plugin) runs the mod's dev client and reloads changed code and resources into it. The `sf`
command lets an agent in a terminal use that game. The plugin writes it into `~/.sulfurframe/bin`:

- POSIX shell / Git Bash: `sh ~/.sulfurframe/bin/sf <command>`
- PowerShell / cmd: `& "$HOME\.sulfurframe\bin\sf.cmd" <command>`

Run it from inside the mod's project folder (it finds the session by the folder). `sf help` lists every command.
Exit codes: 0 done, 1 the game said no (or no game / no world), 2 error, 3 no IDE or `sf run` answered.

## Is a game running?

Run `sf status` first. It needs SulfurFrame 0.3.0 or newer in IntelliJ; older versions have no `sf`.

Exit code 3 means nobody answered, and the message says why: there is no session for this folder, or none at all. Then do one of these:
- If the user has IntelliJ open with SulfurFrame, ask them to press Run client, or start the game yourself as below.
- Start it without IntelliJ, in the background, and wait for it: `sf run --world SFAgent`.
  - When the project has several clients, `sf run` lists them; pick one with `--target <where>`, e.g. `sf run --target :mymod --world SFAgent`.
  - A missing world is created as a flat test world.
  - The first start goes through Gradle (1-3 minutes). `sf status` shows "world 'SFAgent'" once the game is in.
  - `sf run` needs Java 25 and an IntelliJ installation; the IDE does not have to be open.

## The loop

1. Edit the source or resource files as usual. Saving to disk is enough; no IDE action is needed.
2. Run `sf wait`. It waits for the swap that the edit starts and prints its log:
   - `redefined N`: applied. The new code runs from the next call.
   - `ran ... again`, `registered ...`: init code (`onInitialize*`) ran again, and the new registrations are in.
   - `resources: N file(s) copied: ...`: resources from any module of the run went into the game. Assets and data
     reloaded, or only the language if only translation files (`assets/<ns>/lang/*.json`) changed.
   - `hotswap: kept [lambda$...]`: a lambda you removed stays in the game for objects made before the swap. This is normal.
   - `restart needed: ...`: this change cannot go into a running JVM (see below). Run `sf restart`.
   - `compile failed` or other errors: fix the code. Nothing was sent.
3. Check the effect:
   - `sf log 40` or `sf log 100 <text>`: the game log (your mod's log lines, exceptions).
   - `sf cmd <command>` (no leading `/`), e.g. `sf cmd give @p <modid>:<item>` or
     `sf cmd execute as @p at @s run setblock ~1 ~ ~ <block>`. Exit code 1 means the game refused it; the message says why.
   - `sf shot`: prints a PNG path. Open it to see the screen; it works while the window is covered.
   - `sf look`: the block or entity under the crosshair (class, state, NBT).
   - `sf stats`: fps, tick time, memory.
4. Repeat. If a save was not picked up, `sf swap` forces a compile and swap now.

Notes on reading the result:
- **Several files:** for a change spread over several files, save them all, then run `sf wait` once. A save in between may
  log `compile failed` because the other file is not there yet. Nothing is sent then, and the next save compiles everything again.
- **`not loaded N`:** this is normal. The game has not used those classes yet, so there is nothing to redefine; they load
  from the new class files the first time the game uses them. Use the feature (`sf cmd`, open the screen) to see it.
- **Callers of a change:** when other files call a changed method or field, SulfurFrame compiles those callers too. If they
  break, it refuses the swap (`... no longer compile against it`). Fix the callers, save, and run `sf wait`.
- **A module the game loads as a jar:** `sf wait` logs `the game loads <module> from its jar`. Loaded classes run the
  new code, but classes not loaded yet still come from the old jar until `sf restart`, which builds it again.
- **New instance fields:** objects made before the swap get 0 / false / null in a new field, not its initializer's
  value (objects made after get it). An existing object (a settings holder, an open screen) shows the default until it is
  made again or the game restarts; do not take that for a bug in the new code.
- **Language reload:** a language-only reload lays out the open screen again. Text a mod cached in its own fields keeps the old
  string until the screen is opened again.
- **Gradle builds:** avoid running Gradle builds of the mod (`gradlew build`, `compileJava`, tests) while the game runs. They
  rewrite the class folders the game reads. SulfurFrame waits for a class file that is missing for a moment, but `sf wait`
  is the way to check a change.

## What needs a restart (`sf restart`)

A restart takes about 20-40 s, into the same world, and skips Gradle when the build files are unchanged. It is needed for:
- removing a registered entry
- removing an enum constant
- removing a mixin whose interface other code uses (an accessor or invoker), or changing a `*.mixins.json` beyond
  adding or removing mixins
- removing an access widener entry that was there at startup
- adding a JDK or Minecraft type to a class's hierarchy, or removing one
- `fabric.mod.json`
- build files

Everything else applies live: method bodies, new fields and methods, new classes, new blocks, items and entities, a
changed `onInitialize`, new enum constants, new and removed mixins, added access widener entries, assets, data and translations.
Code in a method that is already running (a loop) keeps the old version until it returns.

## When SulfurFrame gets it wrong

Do not quietly work around it. Report it, then continue:

`sf feedback "<what you did, what you expected, what happened>"`

Be concrete: the file and the change, the `sf` command and its output, what the game showed. The report is saved locally
with the current state (logs) in `~/.sulfurframe/feedback/inbox`; nothing is sent over the network. Tell the user you
left a report: they can send it to SulfurFrame's developers as a GitHub issue themselves, with the IntelliJ notification
or `sf feedback send` in their own terminal (names and paths are replaced, and it is posted only when they press Submit
in the browser). Do not run `sf feedback send` yourself; it only opens for a person at a terminal.

Examples worth reporting:
- a swap that says `redefined`, but the game still runs the old code
- `restart needed` for something the list above says applies live
- a game that crashed right after a swap
- `sf wait` returning before the swap
- confusing messages

## Do not

- Do not `sf quit` or `sf restart` a game the user started and is playing in without saying so.
- Do not edit files under the run folder (`run/`) or the IDE's settings to make SulfurFrame behave.
- `sf test` is for SulfurFrame's own demo mod, not for your project.
