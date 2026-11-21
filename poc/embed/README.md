🌐 **English** · [한국어](README.ko.md)

> Translated from the Korean original ([README.ko.md](README.ko.md)), which is the source of truth: if the two differ, the Korean one is right. Last synced: 2026-11-21.

# SulfurFrame — embedding PoC

A PoC that checks whether putting a game window (another process) inside an IDE panel really holds up. A Swing window
stands in for IntelliJ as the host, and a test target on the same stack (LWJGL 3.4.3 + SDL3 + OpenGL) stands in for
Minecraft.

| File | Role |
|---|---|
| `Win32.java` | user32 bindings made with FFM (JDK 22+). No JNA/JNI |
| `EmbedHost.java` | The host, standing in for an IntelliJ ToolWindow. Editor on the left, an AWT `Canvas` (the game window's parent) on the right |
| `SdlTarget.java` | Stand-in for Minecraft 26.x. SDL3 window + GL render loop + stdin commands (`stall <ms>`) |
| `SelfTest.java` | Automatic checks. With `--robot` it also drives the real mouse and keyboard through the focus and freeze scenarios |

## Running (JDK 25, no build needed)

The LWJGL jars are found in the local Gradle cache (`~/.gradle/caches/.../org.lwjgl/*/3.4.3`).

```bash
cd poc/embed
java --enable-native-access=ALL-UNNAMED EmbedHost.java                    # manual
java --enable-native-access=ALL-UNNAMED EmbedHost.java --selftest         # automatic (does not touch input devices)
java --enable-native-access=ALL-UNNAMED EmbedHost.java --selftest --robot # automatic + mouse/keyboard, about 30 s
java --enable-native-access=ALL-UNNAMED EmbedHost.java --attach <PID>     # embed a window that is already up (e.g. Minecraft)
java --enable-native-access=ALL-UNNAMED EmbedHost.java --selftest --robot --attach <PID>   # automatic checks on the real game
java --enable-native-access=ALL-UNNAMED EmbedHost.java --attach <PID> --snapshot out.png   # screenshot of the embedded state
./run-vanilla-mc.sh opengl   # or vulkan: runs vanilla 26.3 from the Loom cache in a separate game folder
```

## Results (2026-11-06, Windows 11, AMD Radeon 780M, GL 4.6)

| Item | REPARENT (`SetParent`+`WS_CHILD`) | OVERLAY (an owned popup tracks the position) |
|---|---|---|
| Structure (parent/owner, styles) | PASS | PASS |
| Follows viewport resize and move | PASS | PASS |
| **Synchronous** `SetWindowPos` while the game is frozen | IDE thread **stalls about 2.2 s** | IDE thread **stalls about 2.2 s** |
| **Asynchronous** `SetWindowPos` while the game is frozen | 0 ms | 0 ms |
| Click the game → keys go to the game | PASS (*1) | PASS |
| Click the editor → keys go to the editor | PASS (*2) | PASS |
| **Click and type in the editor while the game is frozen** | **PASS, about 170 ms** (*3) | **FAIL, 3–3.9 s delay** |

The REPARENT results were the same in two runs in a row.

### What we learned

1. **Always resize and move with `SWP_ASYNCWINDOWPOS`.** A synchronous call on another process's window stalls the IDE
   UI thread until that window processes the message. Confirmed by measurement.
2. **(*1) A child window does not take the keyboard focus from a click alone.** At first this was solved with a hook on
   the game side; now **the host alone solves it** (see "Click-to-focus without a mod" below). The hook in `SdlTarget`
   is off by default (turn it on with `SF_CLICK_TO_FOCUS=1` to compare).
3. **(*2) The other way round, clicking a Swing component does not bring the native focus back.** The host has to
   detect clicks outside the viewport and ask for native focus with `requestFocus(Cause.MOUSE_EVENT)`.
4. **(*3) `SetParent` joins the input queues of the two processes.** When the game froze while holding the focus,
   editor input was blocked for about 6 s without a fix. The fix: when a watchdog misses two `WM_NULL` round trips in a
   row (`SendMessageTimeout`, 200 ms), it detaches the queues with `AttachThreadInput(false)`, and attaches them again
   once the game recovers. Deciding on a single miss mistook the short wait while the focus is handed over for a freeze,
   so a debounce was needed.
5. **The overlay approach is weaker against freezes.** When the owned window froze, activating its owner waited for it,
   and editor input was blocked until the game recovered. That is the opposite of the first guess that not joining the
   input queues would be safer.
   → **Decided on REPARENT.**

## Results with real Minecraft 26.3 (2026-11-07, vanilla, NVIDIA 617.42)

Vanilla 26.3 from the Loom cache was started in a separate game folder with `./run-vanilla-mc.sh opengl|vulkan`, then
checked with `--selftest --robot --attach <PID>`. Minecraft has no stall command, so freezes were reproduced by
**suspending the game window's thread (the render thread) with `SuspendThread`**. (`NtSuspendProcess` failed with
status 1 while the window was embedded.)

| Item | GL (REPARENT / OVERLAY) | Vulkan (REPARENT / OVERLAY) |
|---|---|---|
| Structure, follows resize and move | PASS / PASS | PASS / PASS |
| Synchronous `SetWindowPos` while frozen | about 2.2 s stall / about 2.2 s stall | about 2.2 s stall / about 2.2 s stall |
| Asynchronous `SetWindowPos` while frozen | 0 ms / 0 ms | 0 ms / 0 ms |
| Click the game → keys go to the game (before host handling) | FAIL / PASS | FAIL / PASS |
| Click the game → keys go to the game (**after host click-to-focus**) | **PASS** / PASS | **PASS** / PASS |
| Click the editor → keys go to the editor | PASS / PASS | PASS / PASS |
| Typing in the editor while the game is frozen | **PASS 166–170 ms** / FAIL 3.3 s | **PASS 165–170 ms** / FAIL 3.2 s |

- **GL and Vulkan give exactly the same results.** HWND-level embedding does not depend on the renderer.
- **Vulkan draws correctly inside the child window** (`snapshot-vulkan.png`). At the moment of a resize there were two
  `Couldn't acquire next surface texture` warnings, but it recreated the swapchain and carried on.
- With host click-to-focus, the REPARENT items pass on both GL and Vulkan. **But this check only looked at the Win32
  focus. SDL does not give the keyboard focus, so the game does not actually react. See the "⚠ Important" section
  below.**

### Click-to-focus without a mod (handled by the host)

1. Clear the game window's `WS_EX_NOPARENTNOTIFY`, so mouse-downs inside it reach the parent (the canvas) as
   `WM_PARENTNOTIFY`.
2. Subclass the canvas's `WNDPROC` with an FFM upcall (`installClickToFocus`). A click notification is not handled
   right away but deferred with `PostMessage`, because AWT's own handling of the same click (activation, focus proxy)
   has to finish first.
3. **`SetFocus(game)` must not be called on the AWT toolkit thread.** It returns success, but AWT takes the focus back
   immediately. Instead, **a helper thread unrelated to AWT** briefly attaches to the game's input queue with
   `AttachThreadInput` and calls `SetFocus` there. That matches the condition in which AWT stayed quiet when the game
   took the focus on its own thread.
4. Not done while the game is frozen (`SetFocus` would wait for the frozen game). Filtered by the watchdog state and a
   100 ms `WM_NULL` check.

### Notes on the environment

- In some runs Minecraft quit with exit 139 right after loading resources (no hs_err). It also happened before the
  host attached, so it seems unrelated to the PoC. The cause was not investigated.
- When it quits during startup, Minecraft resets its graphics API to "default" (GL) once on the next start. Always check
  the `Using graphics backend ...` line in the log before testing. A hand-written `options.txt` (without a `version:`
  line) falls back to GL for the same reason.

## The Minecraft 26.3 stack (checked from `mojang_minecraft_info.json` in the local Loom cache)

- Java 25, LWJGL 3.4.3
- **The window library is SDL3, not GLFW** (`lwjgl-sdl`). The spec's `glfwGetWin32Window` cannot be used on 26.x
- **Both OpenGL and Vulkan renderers are included** (`lwjgl-opengl`, `lwjgl-vulkan`, `shaderc`, `spvc`, `vma`)

So the host **finds the top-level window by PID** (`Win32.findMainWindowOfProcess`). That works the same with GLFW
(1.20.5–1.21.x) and SDL3 (26.x), with GL or Vulkan, and attaching the window itself needs no mod. Keyboard input does
need a patch on the game side, though (the "⚠ Important" section). A mod only has to handle what can only be done inside
the game, such as throttling (FPS cap, pausing).

## Not checked yet

- In a world, with the mouse locked (relative mode): release it with ESC and go back to the editor
- Moving the IDE between monitors with different DPI
- Finding the `SunAwtCanvas` HWND inside IntelliJ (JBR), choosing between FFM and JNA in the plugin environment

## Throttler probe, first results (2026-11-07, vanilla 26.3, GL, in a world, G1 defaults, -Xmx2G)

`java EmbedHost.java --throttle-probe --attach <PID>` (`ThrottleProbe.java`). Everything was done **from the host,
without a mod**.

| State | CPU (of one core) | Working set | Heap used/committed |
|---|---|---|---|
| Pause menu (focus lost) | 18% | 1640 MB | 803 / 948 MB |
| Hidden (`ShowWindowAsync` minimize → vanilla's 10 FPS cap) | 9% | 1641 MB | 837 / 948 MB |
| + `jcmd VM.set_flag MaxHeapFreeRatio 10` + `GC.run` | - | 1170 MB | 442 / **485 MB** |
| + `EmptyWorkingSet` | 7.6% | **65 MB** | 447 / 485 MB |
| 4 s after restore (ratio back to 70/40) | 18.5% | 613 MB | 471 / 729 MB |

- 221 ms until the game responds after a restore.
- Asking for `GC.run` alone does not shrink the heap (G1's default MaxHeapFreeRatio is 70%). It is a flag that can
  change at runtime, so the host can tighten it and set it back.
- **Caution: the "PLAYING" measurement is invalid.** The screenshot showed the pause menu still open after ESC. There is
  no baseline for actual play yet.
- Minecraft often died with `0xC0000005` right after loading resources (no hs_err; OpenAL is required in 26.3 and
  cannot be turned off). Cause unknown.

## ⚠ Important: in REPARENT, Minecraft does not get the keyboard focus (2026-11-07)

The earlier "click the game → keys go to the game PASS" **only checked the Win32 focus (GetGUIThreadInfo)**, not
whether the game actually reacts. Logging the SDL events directly (`SdlTarget` log):

| Mode | Key event windowID | SDL keyboard focus | Mouse event windowID |
|---|---|---|---|
| REPARENT (child window) | **0** | **none** | normal |
| OVERLAY (top-level window) | normal | yes | normal |

- On Windows, SDL3 appears to decide focus as "foreground window == my window". A child window can never be the
  foreground, so SDL's keyboard focus never arrives.
- Minecraft (`SDLEventHandler.getWindowHandle`) drops input whose windowID is not its own window, and since it thinks
  its window has no focus, `pauseOnLostFocus` reopens the pause menu every tick. **An embedded vanilla Minecraft is
  effectively unplayable.**
- So the throttle probe's "PLAYING" values are still the pause menu state (confirmed by the save log: no sign of the
  menu closing and reopening).

Candidate fixes:
1. **A tiny patch on the game side**: when embedded, Minecraft treats its window as focused and accepts windowID 0
   input as its own. Possible as a mod (Mixin) or as a **javaagent** the host adds to the launch arguments; a javaagent
   also works on vanilla, whatever the mod loader.
2. **OVERLAY only while typing**: REPARENT normally, switch to a top-level window when the game is clicked. Weaker
   against freezes (results above).
3. Look for an SDL option (hint or environment variable). None found yet.

## Throttler (`Throttler.java`), second results

It switches between ACTIVE / CODING / HIDDEN by focus and visibility. Entering HIDDEN minimizes the game window
(`ShowWindowAsync`, vanilla's 10 FPS), and if it is still hidden 5 s later, it tightens G1's return ratio and runs GC →
`EmptyWorkingSet`. When visible again it restores the ratio (70/40) and the window. No mod needed.

| State (vanilla 26.3, GL, in a world) | CPU (of one core) | Working set | Heap used/committed |
|---|---|---|---|
| Pause menu, trying to focus the game | 64–68% | 700–800 MB | ~450 / 632 MB |
| CODING (pause menu) | 65–72% | 700–820 MB | ~500 / 632 MB |
| HIDDEN (just minimized) | 6–9% | unchanged | unchanged |
| HIDDEN + reclaim | 17% (right after reclaim) | **430–490 MB** | 380 / **420 MB** |
| 4 s after restore | 20–65% | 600–670 MB | ~480 / 630 MB |

- 59–104 ms until it responds after a restore. `jcmd` confirmed the GC flags returned to 70/40 after the restore.
- The pause menu alone uses about 2/3 of a core (it keeps drawing the world behind the menu). Lowering the FPS in
  CODING is not possible with vanilla alone; with the game-side patch above it can be done at the same time.
- Minecraft dying with `0xC0000005` during startup happened again in 2 of 3 runs (cause unknown).

## Solved: the SulfurFrame javaagent (`poc/agent`, 2026-11-07)

`./poc/agent/build.sh` builds `sf-agent.jar`. Only JDK 25 is needed; it uses the JDK's Class-File API, so no ASM or
Gradle. It only "inserts one hook call right before the return" in three Minecraft methods.

| Patched method | Hook | What it does |
|---|---|---|
| `Window.isFocused()` (also used by `Minecraft.isWindowActive()`) | `SfHooks.focused` | Focused if Win32 `GetFocus()` is a window of this process. With the input queues joined by SetParent, focus in the editor shows up as "someone else's window" |
| `SDLEventHandler.getWindowHandle(event)` | `SfHooks.windowHandle` | Accepts key events SDL sent with windowID 0 as the game window's |
| `FramerateLimitTracker.getFramerateLimit()` | `SfHooks.fpsLimit` | Applies an FPS cap without focus (`codingFps=15`) |

- **How it attaches:** the host attaches to the running game with the Attach API (`EmbedHost --agent
  poc/agent/sf-agent.jar ...`) and retransforms the already loaded classes. **No game restart needed.** To attach at
  startup: `SF_AGENT=poc/agent/sf-agent.jar ./run-vanilla-mc.sh opengl`. The startup path has not been run yet, though.
- Attaching twice only updates the settings and does not insert the patches twice.
- It patches the vanilla class names directly, whatever the mod loader (Fabric etc.). Not checked under Fabric.

### Results (vanilla 26.3, GL, in a world, agent attached while running)

- **Actually playable:** clicking "Back to Game" returned to play (screenshot). **E → the inventory opened** (keyboard
  input confirmed). Clicking the editor brings up the pause menu and leaves a save log.
- With the agent attached, all REPARENT items of the embedding self-test pass (editor input while frozen: 169 ms).

| State | CPU (of one core) | Working set | Heap used/committed |
|---|---|---|---|
| **PLAYING (actual play)** | **92–111%** | 880–1380 MB | ~530 / 632–679 MB |
| **CODING (paused + 15 FPS cap)** | **9–10%** | unchanged | unchanged |
| HIDDEN (minimized) | 5–12% | unchanged | unchanged |
| HIDDEN + reclaim | - | **500 MB** | 384 / **420 MB** |
| Right after restore | 11–22% | 530–570 MB | ~380 / 422–632 MB |

Without the agent, CODING was 65–72%. Thanks to the FPS cap, the game's CPU while coding dropped to about 1/7.

## Every version: agent v2 (LWJGL-level patches, Java 8)

Agent v1 patched Minecraft classes (`Window` etc.) by name. That works on 26.x, which is not obfuscated, but older
versions have different names per environment (Mojmap / Yarn / intermediary), and their structure changes too. So v2
**only patches LWJGL methods, which are not remapped and keep stable names**. The goal is one jar for every version and
loader.

- **Java 8 bytecode** (major 52). Loads on Java 8 through 25. No FFM or Class-File API on the game side.
- **ASM 9.10.1 is packed as a jar inside the jar (`sf-impl.jar`) and loaded by a class loader whose parent is
  bootstrap.** No clash with the ASM of Fabric, Forge or Mixin.
- **The Win32 focus is read through LWJGL 3's JNI invokers (`JNI.invokeP` etc., `callP` in 3.1).** If a window owned by
  the render thread has the focus, the game has the focus.

| LWJGL method | Where | What it does |
|---|---|---|
| `SDLEvents.SDL_GetWindowFromEvent` | before return | windowID 0 → the game window (26.x key input) |
| `SDLEvents.SDL_PollEvent` | before return | when the queue is empty and the focus changed, injects `WINDOW_FOCUS_GAINED/LOST` (26.x focus and pausing) |
| `GLFW.glfwSwapBuffers`, `SDLVideo.SDL_GL_SwapWindow`, `KHRSwapchain.vkQueuePresentKHR` | entry | without focus, sleeps briefly to match `codingFps` (every version) |
| `Display.isActive`, `Display.update` (LWJGL 2) | - | placeholder only. Focus source and behaviour not implemented |

### Results per version (the same `sf-agent.jar`, loaded at startup with `-javaagent`)

| | 26.3 (SDL3, Java 25) | 1.21.1 (GLFW, Java 21) without agent | 1.21.1 with agent |
|---|---|---|---|
| Click and keyboard (Back to Game, E inventory) | ✅ (agent required) | ✅ | ✅ |
| PLAYING CPU | 109% | 106% | 124% |
| CODING CPU | **24%** | 72% | **11%** |
| HIDDEN CPU | 9% | **72%** (1.21.1 does not lower its FPS when minimized) | **12%** |
| Working set after HIDDEN + reclaim | 549 MB | 554 MB | 585 MB |

### Found and fixed in this step
- **GLFW's `GLFW_FOCUSED` attribute is always false on a child window** (it compares against `GetActiveWindow()`).
  Focus is now decided by the Win32 thread everywhere.
- **GLFW passes `WM_MOUSEACTIVATE` on to the parent.** When AWT sees it, it takes the focus to the frame right after the
  click. The canvas subclass handles it as `MA_ACTIVATE` and does not pass it to AWT. Before the fix, 1.21.1 kept
  pausing right after returning to the game.
- **When the host dies without detaching, the game window is destroyed with it** (a child window goes away with its
  parent). The Minecraft process stays, but its window is gone. Detaching was added to a shutdown hook and to the test
  mode's finally block, but **there is no protection against an IDE crash or a forced kill.** A problem for the real
  plugin to solve (for example, the agent detects the lost parent and puts the window back to top level).
- `run-vanilla-mc.sh` can now pick a version (`SF_MC_VERSION`). It chooses the matching Java, and libraries without an
  exact version are replaced from the ForgeGradle cache or by the same minor version.

### Not checked yet
- ~~The LWJGL 2 era, 1.13–1.20~~ → checked with 1.12.2 and 1.16.5 in "Results for every version" below. 1.7.10 and
  LWJGL 3.1 (1.13–1.14) have not been run yet.
- **Mod loaders:** Fabric, Forge, NeoForge. NeoForge and Forge 1.17+ in particular keep LWJGL in a named module layer,
  so they may not see `SfHooks` on the system class path (a module reads edge may be needed).
- Dynamic attach (while running) on 26.3 was only checked with v1. v2 was only checked loaded at startup.

## Results for every version: 1.12.2 / 1.16.5 / 1.21.1 / 26.3 (the same `sf-agent.jar`)

Versions missing from the local cache were downloaded from Mojang's servers with `McFetch.java` (SHA-1 checked, sound
assets left out: 106 MB for both versions together).
`java McFetch.java fetch|launch <version> [--agent <jar>=codingFps=15]`

| Version | Window / LWJGL / Java | Input without agent | PLAYING | CODING without → with | HIDDEN without → with | Working set after reclaim |
|---|---|---|---|---|---|---|
| 26.3 | SDL3 / 3.4.3 / 25 | ❌ (key windowID 0, pause loop) | 109% | 65–72% → **24%** | 9% → 9% | 549 MB |
| 1.21.1 | GLFW / 3.3.3 / 21 | ✅ | 124% | 72% → **11%** | 72% → **12%** | 585 MB |
| 1.16.5 | GLFW / 3.2.2 / 8 | ✅ | 80% | 75% → **10%** | 58% → **9%** | 110 MB |
| 1.12.2 | LWJGL 2 `Display` / 2.9.4 / 8 | ✅ | 137% | 84% → **10%** | 63% → **9%** | 44 MB |

CPU is of one core. Click (Back to Game) and keyboard (E inventory) were confirmed by screenshot on every version.
How the agent was attached:
- 26.3, 1.21.1, 1.16.5: loaded at game start with `-javaagent`
- 1.12.2: attached to the running game

- **Only SDL3 (26.x) really needs the agent for input.** GLFW and LWJGL 2 decide focus from `WM_SETFOCUS`, so they work
  in a child window too. On older versions the agent's job is the frame cap while coding and while hidden; these
  versions do not lower their FPS on their own when minimized.
- **LWJGL 2:** the frame cap is at the entry of `Display.update(Z)V`, and focus comes from LWJGL 2's own
  `Display.isActive()`.
- **Heap reclaim on Java 8:** the default GC is Parallel, so tightening `MaxHeapFreeRatio` barely shrinks the committed
  heap. Trimming the working set helps a lot.

### Found and fixed in this step
- **Java 8 cannot read a `-javaagent` jar whose path has non-ASCII characters.** It fails with `ClassNotFoundException:
  dev.sulfurframe.agent.SfAgent`, and this repository's path contains Korean characters. Copy it to an ASCII path (e.g.
  `%TEMP%\sf-agent.jar`). The real plugin has to unpack the agent to an ASCII path. The same goes for the path given to
  `loadAgent` in a dynamic attach.
- **When the size happens to match at the first embed, no `WM_SIZE` arrives.** 1.16.5 then kept using its old
  framebuffer and GUI size, so the picture was off and clicks landed in the wrong place. That clicked the "Report bugs"
  button and brought up an external link confirmation; the link was not opened and the dialog was closed with ESC. Now
  the window is shrunk by 1 px right after embedding and set back.
- **When a JDK 25 host attaches to a Java 8 game**, `AgentLoadException: ... 0` is thrown even though the agent loaded
  fine (a reply format difference). The host tells this case apart.
- Menu layouts differ between versions, so the probe's "Back to Game" position can be set with `SF_BTG_X`/`SF_BTG_Y`,
  and 1.12.2's world list button with `SF_CREATE_LIST_BTN`. `InputCheck` got `--open-world` and `--esc-resize`.

## The remaining versions and mod loaders (1.7.10, 1.13.2, Fabric)

| Target | Window / LWJGL / Java | PLAYING | CODING | HIDDEN | Working set after reclaim | Input check |
|---|---|---|---|---|---|---|
| 1.7.10 | LWJGL 2.9.1 / 8 | 102% | **10.5%** | **9.5%** | 52 MB | F2 screenshot saved, "Taking Inventory" achievement (E) |
| 1.13.2 | GLFW / LWJGL 3.1.6 / 8 | 85% | **14%** | **12%** | 52 MB | E inventory |
| 1.21.1 + **Fabric Loader 0.16.10** | GLFW / 3.3.3 / 21 | 107% | **13%** | **12%** | 530 MB | E inventory |
| 26.3 (remeasured, new hook approach) | SDL3 / 3.4.3 / 25 | 129% | **9.9%** | **8.3%** | 558 MB | E inventory |
| 1.16.5 (remeasured, new hook approach) | GLFW / 3.2.2 / 8 | 91% | **13%** | **15%** | 128 MB | - |

The same `sf-agent.jar` now works on **six vanilla versions from 1.7.10 to 26.3** and on **Fabric**.
- 1.7.10's window does not update in screen captures, so it was checked with the game's own screenshot (F2) and logs.
- `run-vanilla-mc.sh` got `SF_LOADER=fabric`. It runs Knot with the Fabric Loader, intermediary, Mixin and ASM found in
  the cache.

### Found and fixed in this step
- **LWJGL 3.1.x has the JNI invoker arguments in the opposite order** (function address first). Calling it the 3.2 way
  **crashed 1.13.2** (it called the window handle as a function). The order is now chosen by reading `org.lwjgl.Version`.
- **Fabric's Knot does not load classes from class path entries "not exposed to the game" (the agent jar).** Under
  Fabric, Knot also loads LWJGL, so patched code calling `SfHooks` directly crashed with `NoClassDefFoundError`. Adding
  it to the bootstrap path did not help (origin check).
  → **The patched code no longer references agent classes at all.** The hooks are registered as JDK functional interface
  objects (`LongUnaryOperator`, `BiFunction`, `LongConsumer`, `Runnable`) under `dev.sulfurframe.hooks` (Object[]) in
  `System.getProperties()`, and the patched code takes them out and calls them through the interfaces. JDK types are
  visible from every class loader and module, so the same should not be blocked in the NeoForge and Forge module layers
  either (unverified).
  - Caution: one non-String value goes into the system properties. Code that casts every value to String could trip
    over it.

### Forge / NeoForge: → checked in "Mod loader results" below

## Mod loader results (the same `sf-agent.jar`, `-javaagent` at startup)

Installers from the official Maven (SHA-1 checked) were installed headless (`--install-client` / `--installClient`) and
run with `java McFetch.java launch-installed <install folder> <version id> --agent <jar>=codingFps=15`.

| Loader | Launch structure | PLAYING | CODING | HIDDEN | Working set after reclaim | Input |
|---|---|---|---|---|---|---|
| Fabric 0.16.10 (1.21.1) | Knot | 107% | **13%** | **12%** | 530 MB | E inventory |
| NeoForge 21.1.265 (1.21.1) | BootstrapLauncher + module layer | 127% | **31%** | **25%** | 591 MB | E inventory |
| Forge 47.4.28 (1.20.1) | BootstrapLauncher + module layer | 89% | **16%** | **14%** | 624 MB | E inventory |
| Forge 14.23.5.2860 (1.12.2) | LaunchWrapper, Java 8 | 83% | **14%** | **15%** | 75 MB | E inventory |

- **It works unchanged in module layers (NeoForge/Forge 1.17+)**, thanks to passing the hooks as JDK interfaces.
- ~~NeoForge coming out higher than vanilla looks like background work in the loader.~~ → **That reading was wrong
  (remeasured 2026-11-08).** The NeoForge 31%/25% above appear to have been measured right after entering the world,
  while chunk loading and generation were still running. Measuring NeoForge 21.1.265 and Fabric 1.21.1 on the same world
  after they settled, twice each in turn, shows no difference (of one core, `--throttle-probe`):

  | | CODING | HIDDEN |
  |---|---|---|
  | NeoForge 21.1.265 | 12.6%, 13.7% | 11.0%, 8.3% |
  | Fabric 0.16.10 | 10.7%, 13.1% | 14.9%, 12.3% |

  The other way round, Fabric measured 20 s after entering also showed CODING 26.5%. With JFR (`thread-cpu-load`), the
  CPU left while capped at 15 fps is mostly the JIT compiler threads (C2) on both loaders, and NeoForge had no busy
  thread of its own (its render thread was actually lower than Fabric's).
  **When measuring, enter the world and wait 1–2 minutes first.**
- `launch-installed` does what the official launcher does:
  - merges `inheritsFrom`, applies argument rules (Windows x64, features excluded), substitutes `${...}`
  - fills in the vanilla libraries the installer does not download, LWJGL 2 natives and the parent json from Mojang
  - for recent Forge and NeoForge, does not put the version jar on the class path; for LaunchWrapper, only that

## Coverage summary

One and the same jar makes all of the following **playable while embedded**, with an FPS cap while coding or hidden.
- Vanilla: 1.7.10, 1.12.2, 1.13.2, 1.16.5, 1.21.1, 26.3
- Loaders: Fabric, NeoForge, Forge (modern and legacy)

| Window / LWJGL | Versions | Input without agent | Agent's job |
|---|---|---|---|
| LWJGL 2 `Display` | 1.7.10–1.12.2 | ✅ | FPS cap (`Display.update`) |
| GLFW, LWJGL 3.1–3.3 | 1.13–1.21.x | ✅ | FPS cap (`glfwSwapBuffers`), Win32 focus |
| SDL3, LWJGL 3.4 | 26.x | ❌ | windowID 0 fix, synthesized focus events, FPS cap |

Remaining: 1.8–1.11, 1.14–1.15, 1.17–1.20 (vanilla), Quilt, macOS. (The IntelliJ plugin environment is checked in
`sf-idea-plugin`; Linux X11 in the PoC below.)

## Linux / X11 PoC (2026-11-07, WSL2 Ubuntu 24.04 + WSLg, vanilla 1.21.1)

`X11Host.java` ports `EmbedHost`'s REPARENT mode to X11. It moves the game window under the Canvas X window of a Swing
window (XToolkit) with `XReparentWindow`, calling libX11 through FFM. `McFetch` now tells operating systems apart
(library and argument rules, `natives-linux`, the Mojang manifest when there is no Loom cache,
`/usr/lib/jvm/java-N-openjdk-amd64`).

```bash
./run-x11-poc.sh 1.21.1 --hold 30          # start the game (if needed) → embed → detach after 30 s
./run-x11-poc.sh 1.21.1 --hold 30 --halt   # after 30 s the host "crashes" with Runtime.halt
```
On WSLg, `WAYLAND_DISPLAY` has to be cleared for GLFW 3.4 to use X11 (XWayland). The renderer was llvmpipe (GL 4.5).

```
RESULT PASS game window found
RESULT PASS canvas X window                       (getWindow of the XToolkit peer)
RESULT PASS reparented under the canvas (and stays)
RESULT PASS game sized to the canvas
RESULT PASS game follows a host resize
RESULT PASS detached back to the root             (after detaching, a normal window with its window manager frame back, IsViewable)
```
- **Mouse:** clicks go straight to the game while embedded (onboarding Continue → title, opening Options). The deepest
  X window under the pointer is the game window, so no Windows-style focus handling is needed.
- **Keyboard:** after `XSetInputFocus(game)`, ESC closed Options.
- Screenshot: `snapshot-x11.png` (the pink and black panorama is because the PoC does not download asset objects)

### What we learned
- **Taking the window away from the window manager:** a mapped top-level window is put in a frame and managed by the
  window manager. A plain `XReparentWindow` is taken back at once by WSLg's Weston WM, which holds it under its own
  hidden window "Weston WM". The order: `XWithdrawWindow` → turn on `override_redirect` (a window the window manager
  leaves alone) → wait until the window manager takes it out of the frame → move it under the Canvas and map it. If it
  is taken back anyway, try again (usually 1–2 times). Detaching is the reverse: turn `override_redirect` off and map it
  on the root, and the window manager frames it again.
- **On a crash, X11 cannot save the game window either:** `XAddToSaveSet` has to be registered on the connection that
  created the Canvas (AWT's XToolkit connection). Even then, the place the server moves the game window to is "the
  nearest ancestor not created by the dead client", which is **the window manager's frame**, and the window manager
  deletes that frame when the host window goes away. The game window goes with it, and when GLFW sends `XGetGeometry`
  to the deleted window and gets `BadDrawable`, Xlib's default error handler ends the game process (no world save).
  With window managers that add frames (Weston, Mutter, KWin and so on), the host side alone cannot prevent it.
  → Solved with the agent's X11 lifeboat below (the window is lost, but the world is saved).

## The agent's Linux/X11 part (`X11Lifeboat.java`, the same `sf-agent.jar`)

- **Focus (FPS cap):** on X11, `GLFW_FOCUSED` is `XGetInputFocus() == game window`, so it is right for an embedded child
  window too (the host calls `XSetInputFocus` on click). Linux uses that.
- **Detecting the embed:** checks every 250 ms for the `override_redirect` the host turns on when it takes the window
  from the window manager.
- **X errors are not fatal while embedded:** a C function that ignores its arguments (libc `getpid`) is set with
  `XSetErrorHandler` (Xlib does not use the handler's return value). GLFW briefly installs its own handler around calls
  that need errors and then puts ours back. After detaching, the original handler is restored.
- **When the window disappears:** `glfwSwapBuffers` is skipped (the patch became "return right away if the hook says
  true"), and a normal quit is requested with `glfwSetWindowShouldClose`. Without skipping the swap, Mesa llvmpipe
  presents to the deleted window and the JVM dies with SIGSEGV on a rasterizer thread (right after "Stopping!", before
  saving).
- **Trying to save the window (event thread):** it waits with `XNextEvent` for the ReparentNotify in which the save-set
  moves the game window into the window manager's frame, then moves it to the root immediately without any query.
  WSLg's Weston was faster every time, so the window was lost, but the same thread puts the window right back when the
  window manager takes it during an embed, so the host embeds on the first try. If the previous parent (the Canvas)
  still exists, it was taken back; if not, the host died.

Check (WSLg, vanilla 1.21.1, in a world, `run-x11-poc.sh ... --focus-test <pid> --halt`):
```
RESULT PASS reparented under the canvas (and stays)   [1 attempt(s)]
RESULT PASS agent caps the game while the editor has focus  [render + llvmpipe CPU 365% with focus, 190% while coding]  (codingFps=3)
[host] halt
[SF-Agent] x11: host gone and the window manager was faster: window lost
[SF-Agent] game window destroyed while embedded (IDE gone?): asking the game to quit so the world is saved
Stopping! → Saving worlds → All dimensions are saved → exits 2 s later, level.dat updated
```
- Normal detach (without `--halt`): the X error handler is restored, and the game keeps running as a normal framed window
- llvmpipe draws the world at around 15 fps anyway, so a cap of 15 showed no difference; it was tested with 3. CPU
  counts only the render thread and llvmpipe threads, so server and chunk threads do not mix in.
- Windows regression check: the `glfwSwapBuffers` patch now has a branch, so 1.21.1 (LWJGL 3.3, loaded at startup and
  attached while running) and 1.13.2 (LWJGL 3.1) were run again → patches, lifeboat and focus fine, no VerifyError

### SDL3 (26.x) and LWJGL 2 (1.12.2) on X11 too (the same `sf-agent.jar`)

| | 26.3 (SDL3, LWJGL 3.4) | 1.12.2 (LWJGL 2.9.4, Java 8) | 1.21.1 (GLFW) |
|---|---|---|---|
| Embed, size, follows window size | PASS | PASS | PASS |
| FPS cap (render+llvmpipe CPU, focus → coding, codingFps=3) | 724% → 81% | 324% → 32% | 365% → 190% |
| Host crash | window saved, game continues | window saved, game continues | the window manager won (2/2 this time, 3/3 before) → saved and quit |

- **Finding the window:** SDL3 sets `WM_NAME` with type `UTF8_STRING`, which `XFetchName` cannot read. The name is read
  as bytes with `XGetWindowProperty`, and only viewable (`IsViewable`) windows are picked, to avoid a hidden helper
  window whose name starts the same ("RenderPearl OpenGL Hidden Utility Window"). The plugin's `X11.name` does the same.
- **Focus:** SDL3 and LWJGL 2 do not notice the focus the host gives with `XSetInputFocus` (LWJGL 2's
  `Display.isActive()` stays false). So the agent decides itself with `XGetInputFocus() == game window` (a connection
  only for the render thread). GLFW keeps using `GLFW_FOCUSED`.
- **Window number:** SDL3 through the window property `SDL.window.x11.window`, LWJGL 2 through
  `LinuxDisplay.getWindow()`.
- **LWJGL 2 has no LWJGL 3 JNI,** so the X11 calls go through JNA (bundled with Minecraft 1.8+). The native layer of
  `X11Lifeboat` uses whichever is there.
- **Skipping presentation when the window is gone:** `SDL_GL_SwapWindow` is skipped returning true, and for LWJGL 2 the
  whole `Display.update` is skipped (`isCloseRequested` still works). LWJGL 2's `Display` is Java 5 bytecode, so no stack
  map frames are added at the branch (ASM rejects them if added).
- **26.x dies right at startup without asset objects** (the title panorama). `McFetch` now downloads the asset objects,
  minus sounds (.ogg).
- 26.3 and 1.12.2 were tested on the title screen without a world (a WSL restart wiped the test world in `/tmp`). The
  window was saved, so the save path did not run. 1.21.1's "save and quit" was confirmed in the in-world test above.
- Windows regression: 1.12.2 (McFetch), 1.21.1 and 26.3 (run-vanilla) all fine for patches, lifeboat and focus. 26.3
  once died with 0xC0000005 while loading resources, and three more runs were all fine (taken to be the intermittent
  26.3 startup crash that was already there).

### Not done yet
- Checking on real Linux desktops (GNOME/KDE, XWayland in a Wayland session) and GPU drivers
- Why only GLFW loses the window rescue
