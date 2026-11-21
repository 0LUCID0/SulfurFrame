🌐 **English** · [한국어](README.ko.md)

> Translated from the Korean original ([README.ko.md](README.ko.md)), which is the source of truth: if the two differ, the Korean one is right. Last synced: 2026-11-21.

# SulfurFrame — profiles and worlds (PoC)

The goal is to handle the worlds, key bindings and options scattered across every Minecraft client (the official
launcher, Prism, CurseForge, Modrinth, mod dev projects). There are three layers.

1. **Personal profile (everyday play):** key bindings and options are merge-synced; server lists, resource packs,
   shader packs and screenshots are pooled; worlds are handled as a library.
2. **Dev workspace:** a shared dev home per Minecraft version, test world templates, snapshots. Isolated from the
   personal profile.
3. **Bridge:** try a dev build on a copy of a real world, deploy a build to an instance. Originals are only read.

## Step 1: instance scanner (`InstanceScanner.java`)

Read-only. It changes nothing.

```bash
java InstanceScanner.java --dev-root "C:/path/to/workspace" [--json out.json]
```

| Source | What it reads |
|---|---|
| Official launcher | each installation's `gameDir` in `%APPDATA%/.minecraft/launcher_profiles*.json` (the same folder is merged into one) |
| Prism | `InstanceDir` in `prismlauncher.cfg`, each instance's `instance.cfg` and `mmc-pack.json` (Minecraft version, loader) |
| CurseForge | `~/curseforge/minecraft/Instances/*/minecraftinstance.json` |
| Modrinth | `%APPDATA%/ModrinthApp/profiles/*`. Its metadata is in `app.db` (SQLite), so version and loader are guessed from the instance's logs and `options.txt` |
| Dev projects | `minecraft_version` in `gradle.properties` and `run/` (or `runs/client`) |

For each instance it shows the game folder, Minecraft version, loader, mod count, the data version and key binding
count of `options.txt`, and the worlds. World info is the name read straight from `level.dat`, the **version it was
last opened with** (`Version.Name`, `DataVersion`), and the last played date.

- Logs may not be UTF-8, so they are read as raw bytes. Recognised loader formats: `Loading Minecraft X with Fabric
  Loader Y`, `forge-<mc>-<ver>`, `neoforge-…`.
- **Worlds saved by a newer version than their instance are marked ⚠.** One turned up on this PC too: a world in
  Modrinth `my` (1.21.1) was last saved with 1.21.4.
- Personal information such as account names is not printed.

### Results on this PC (2026-11-07)
12 instances (official 1, Prism 1, Modrinth 6, dev 4), 8 worlds. No CurseForge instances. For the official launcher
the version is only known from the `options.txt` data version (4671).

## Step 2: merge-syncing key bindings and options (`OptionsSync.java`)

```bash
./options-sync.sh pull   [--apply] <gameDir>...   # instances → master
./options-sync.sh status           <gameDir>...   # what a push would change
./options-sync.sh push   [--apply] <gameDir>...   # master → instances
```

- **Nothing is written without `--apply`.** Before writing it leaves an `options.txt.sf-bak-<time>` backup, and it does
  not write while a game is running from that folder.
- **The master keeps the union** (`~/.sulfurframe/profile/options.master`, change it with `--master`). Only the shared
  keys are merged into an instance; the rest of that file and its `version:` line stay as they are. That avoids the
  problem with linking the files: the game drops options it does not know when it saves.
- **What is shared** (`share.txt`, defaults): all key bindings, mouse sensitivity and invert, auto-jump, sneak and
  sprint toggles, language, sound volumes, chat size, subtitles, FOV, view bobbing and so on. **Graphics and video
  options stay per instance** (dev and play setups may want them different).
- **Conflict rule (three-way merge):** `options.txt` is saved again every time the game starts, so file times are not
  used. For each instance the values right after the last push (the baseline) are kept in `<master>/baselines/`, and
  **only keys that changed from that baseline count as the user's change in that instance** and get spread. If several
  places changed the same key differently, that is reported as a conflict and the most recently saved one wins. Keys
  nobody changed, and the first sync, go by **majority** (a tie goes to the most recently saved). Per-instance
  exceptions are listed in that game folder's `sulfurframe-keep.txt` (e.g. `key_iris.*`).
- **Between versions:** numeric key codes of 1.12 and older (LWJGL 2) and the 1.13+ names (`key.keyboard.w`,
  `key.mouse.left`) are converted both ways.
- **Missing keys are added:** vanilla keys missing from an instance are added (the game ignores keys it does not know).
  **Mod keys are only added to instances that already have keys of that mod.**

### Check (on **copies** of real instance files, 2026-11-15)
Targets: Modrinth `my` (1.21.1, 102 keys), Prism 1.21.1 (40), dev 26.3 (62), a hand-made 1.12.2-format file.
- Pulling all 4 gave a master of 166 entries. The drop key, changed later to G on the Modrinth side, won.
- After a push, the 1.12 file got it converted as `key_key.drop:34` (G), and a 1.21-only mod key (axiom) was not added.
- **Lines that are not shared stayed untouched in all three files** (hash comparison). One backup each was made, and
  pushing again changes nothing.
- With `sulfurframe-keep.txt` (`key_iris.*`) in the Prism copy, the 3 iris keys dropped out of the change list.

Known limits: mod keys that use the `key.` prefix are treated as vanilla and added to every instance (harmless, the
game ignores them). Vanilla debug keys that exist only in 26.x are added to older files too (ignored as well).
**Nothing has been written to real instance files yet.**

## Next steps
2-1. Run the sync automatically (push in a javaagent's `premain`, pull in a JVM shutdown hook; any launcher works by
adding a JVM argument)
3. Try dev builds on real world snapshots, deploy builds to instances
4. Shared dev home (junction) and test world templates

### Preview on real instances (2026-11-15, nothing written, checked by hash)
The first rule, "the most recent file wins", turned out wrong in the preview. Because the official launcher's
`.minecraft` was saved most recently, it was about to set the language of the 9 other places to `en_us`, FOV to 70,
and turn music that was off back on. So it was changed to a three-way merge with majority voting. After the change,
values follow what most places use, such as language `ko_kr` (9/10). FOV (70 vs 90) and music (on vs off) were 5:5 ties.

## Step 2-1: automatic sync (javaagent)

The sync code (`src/dev/sulfurframe/profile/OptionsSync.java`, Java 8 syntax) ships inside `sf-agent.jar`.
- **On game start (`premain`):** runs before Minecraft reads its options. Values changed in this instance go up to the
  master, and changes from other instances come in.
- **On game exit (JVM shutdown hook):** values changed during play go up to the master, and this instance's baseline is
  updated. If a crash skips this step, the baseline comparison catches up on the next start.
- **Finding the game folder:** the agent argument `gameDir=`, then `--gameDir` on the JVM command line, then the working
  folder (Prism, Modrinth, CurseForge and Gradle runs use the game folder as working folder).
- **Master lock:** several games starting or quitting at once take turns writing the master (`<master>/lock`).
- **Log:** what was synced goes to `<master>/sync.log`. The game's own log is already closed at exit, so it is kept
  separately.
- **Backups:** automatic sync writes `options.txt` on every start, so only the latest 5 backups per instance are kept.
- **Agent arguments:** `mode=sync` (sync only, no LWJGL patches or FPS cap; for everyday play), `sync=off`,
  `master=<dir>`. Without a master profile it does nothing.

Install: `./install-autosync.sh` builds the agent, copies it to `~/.sulfurframe/sf-agent.jar` (a path without Korean
characters: a Java 8 restriction) and prints the JVM argument to put in the launcher. **It does not change launcher
settings.**
```
-javaagent:C:\Users\<you>\.sulfurframe\sf-agent.jar=mode=sync
```
Where to put it: Prism (Edit instance → Settings → Java arguments), Modrinth (Profile settings → Java and memory → JVM
arguments), CurseForge (Settings → Minecraft → Java settings, global), official launcher (Installations → Edit → More
options → JVM arguments).

### Check (vanilla 1.21.1, a test copy of the master, 2026-11-15)
1. **An instance syncing for the first time:** on start it took the master's values (drop key J → Q), and a baseline
   file was created.
2. **A value changed while the game was off:** with the drop key set to J beforehand, the next start recognised it as
   the user's change and sent it up to the master.
3. **At exit:** a volume slider moved during play (0.349) went up to the master at exit. In a run where nothing was
   touched, the game did not change any values (nothing spread by mistake).

Known behaviour: vanilla drops keys it does not know (mod keys) when it saves. So in an instance without mods, those
keys are added again on every start and the game drops them again. Harmless, but each start leaves a backup (5 kept).

## Step 2-2: content profiles (per instance, mostly key bindings)

The same player wants different key layouts per modpack or content. So **named profiles** are layered on top of the
default master.
- Profile: `<master>/profiles/<name>.txt` (`key:value` lines). Assigning to an instance: `sulfurframe-profile.txt` in
  that game folder (with several, later ones win).
- **Applying:** the instance's profile value → otherwise the default master value.
- **Collecting:** changing a key that a profile has in that instance changes **that profile**, which spreads only to
  instances using the same profile. Profile keys do not take part in the default majority vote.
- Automatic sync (the agent) follows the same rules.

```bash
./options-sync.sh suggest <gameDir>...                              # groups keys that differ between instances into profile candidates
./options-sync.sh profile-new <name> <gameDir> 'key_iris.*' --apply  # creates a profile from that instance's current values
./options-sync.sh assign <gameDir> <name>[,<name>] --apply           # assigns to an instance ("-" removes)
./options-sync.sh profiles <gameDir>...                             # profiles and assignments
```

### Check (copies, 2026-11-15)
- `suggest` on the pre-sync backups grouped the real differences: iris shader keys differ per pack, the zoomify zoom
  key differs, some keys exist only in FindTheMurderer (toolbar, player list, mod menu), and FOV and music split 5:5.
- A `shaders` profile made from the iris keys of the Modrinth copy was assigned to Prism and Modrinth. Changing the iris
  reload key to F9 in Prism **changed only the profile** (the default master stayed), and spread **only to Modrinth,
  which uses the same profile**. The dev instance without the profile stayed as it was.

No profiles have been created or assigned on real instances yet.

## Step 2-3: keys of the mod you are developing are not synced

A dev instance (a mod project's `run/`) has only vanilla and the mod you are making. So:
- **Vanilla keys** are shared with every instance through the master.
- **Keys of the mod you are developing** are neither sent nor received in that dev instance. Syncing them would put the
  master's old key back after you change the default in code, so a new default could never be tested. Mod IDs are read
  from the nearest Gradle project's `fabric.mod.json`, `quilt.mod.json`, `mods.toml`, `neoforge.mods.toml` or
  `mcmod.info`. IDs found on this PC: `maxwells_chamber`, `disconnected1nn`, `lcd`, `untitled1`.
- **Namespace fix:** many mods name their keys `key.<modid>.<action>`. The old rule took those for vanilla, so
  `maxwells_chamber` and jade keys were added to every instance (they did land in 3 dev folders). Now only
  `key.<action>`, `key.hotbar.*` and `key.debug.*` are vanilla, and `key.<x>.<y>` belongs to mod x. Keys of other mods
  that already got in are dropped by games without that mod on their next save, and are not added again.

Check: `status` on the 10 real instances shows 0 changes (file hashes identical), and the mod IDs of the 4 dev folders
were found. On copies, changing `maxwells_chamber.enter` to G in a dev folder did not reach the master (E stayed), and
mod keys were not added to instances without that mod.

Key bindings of play instances (Modrinth and Prism with other people's mods) can be restored with
`own-profiles --from-backup` (not applied).

## Step 2-4: connecting to the IDE plugin

- **Dev clients started by the plugin** (Run client) get the agent arguments `gameDir=<run folder>`,
  `ownMods=<mod IDs+…>` and `createMaster=true`. The run folder and the mods in development are taken from the open
  project instead of guessed (`sf-idea-plugin/.../profile/ModIds.java`). Without a master, the first dev run creates it.
- **`SharedInstance` (a shared folder per MC version) no longer copies `options.txt` whole.** Passing the whole file
  around between projects of the same version carried one project's mod keys into another and ignored the exclusion
  of keys of the mod in development. The links for worlds, resource packs, screenshots and shader packs, and the
  copies of `servers.dat` and `hotbar.nbt`, stay. Only a new project without an `options.txt` in `run/` takes the shared
  folder's one, once.
- Check: the plugin's `ModIds` read the IDs of 4 projects (`maxwells_chamber`, `disconnected1nn`, `lcd`, `untitled1`).
  Running vanilla 1.21.1 with the same arguments as the plugin created a master in an empty folder (144 entries), and
  keys of the passed mod ID (`testmod`) did not go into the master. **The flow of running through Run client inside
  the IDE has not been tested yet.**
