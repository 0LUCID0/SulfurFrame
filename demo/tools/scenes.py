"""The edits the demo video shows, one per scene, written to the showcase mod's files like a save from the editor.

  python scenes.py list                 scenes and what they change
  python scenes.py apply <scene>        make one scene's edit
  python scenes.py revert [scene]       put the files back (all scenes when none is given)
  python scenes.py run <scene> ... [--hold N] [--log FILE]
                                        apply each scene in turn, N seconds apart (default 6), logging the time of every
                                        save so the video edit can line up zooms, captions and the measured swap time

The mod folder defaults to ../showcase-mod; pass --mod <dir> to edit a copy (the self-test copy, a recording copy).
"""
import os, shutil, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA = "src/main/java/dev/sulfurframe/showcase"

# scene -> (caption, [(file, old, new) | (file, None, source file to copy over it)])
# Text edits are short on purpose: typed a character at a time they take under a second on screen.
SCENES = {
    "particles": ("Saved. Already in the game.", [
        (f"{JAVA}/client/Fountain.java", "Vector3f color = Palette.SULFUR;", "Vector3f color = Palette.TEAL;"),
    ]),
    "sky": ("Mixins swap too.", [
        # the handler starts empty (a blank line indented 8 spaces); the call is typed into it
        (f"{JAVA}/client/mixin/SkyMixin.java", "{\n        \n    }", "{\n        cir.setReturnValue(Palette.DUSK);\n    }"),
    ]),
    "texture": ("Textures reload by themselves.", [
        ("src/main/resources/assets/showcase/textures/block/tile.png", None, os.path.join(HERE, "..", "scenes", "texture", "tile.png")),
    ]),
    "shader": ("So do shaders.", [
        ("src/main/resources/assets/minecraft/shaders/program/showcase_grade.fsh",
         "    fragColor = vec4(c.rgb, 1.0);", "    fragColor = sulfur(c);"),
    ]),
    "restart": ("New item? One click. Same world.", [
        # a new item constant after the existing one, on the blank line (indented 4 spaces) left for it
        (f"{JAVA}/ShowcaseItems.java", 'register("sulfur_cube");\n    \n',
         'register("sulfur_cube");\n    public static final Item SPARK = register("spark");\n'),
    ]),
}

# scenes whose caret waits a few characters after the insertion point (so the inserted text pushes it along)
CARET_AFTER = {}

# typing speed per scene (1 = 70-130 ms a key): the long lines are typed a bit faster so the part stays short
SPEED = {"sky": 1.3, "restart": 1.6}


def mod_dir(args):
    if "--mod" in args:
        i = args.index("--mod"); d = args[i + 1]; del args[i:i + 2]; return d
    return os.path.join(HERE, "..", "showcase-mod")


def backup_path(mod, rel):
    return os.path.join(mod, ".scene-backup", rel)


def _middle(old, new):
    """common prefix length and the differing middles of old and new"""
    p = 0
    while p < min(len(old), len(new)) and old[p] == new[p]: p += 1
    s = 0
    while s < min(len(old), len(new)) - p and old[-1 - s] == new[-1 - s]: s += 1
    return p, old[p:len(old) - s], new[p:len(new) - s]


def caret(mod, scene):
    """(file, line, column), both 1-based, where typing starts: the end of the text that gets replaced"""
    rel, old, new = SCENES[scene][1][0]
    if old is None: return rel, 1, 1
    text = open(os.path.join(mod, rel), encoding="utf-8").read()
    p, mid_old, _ = _middle(old, new)
    at = text.index(old) + p + len(mid_old) + CARET_AFTER.get(scene, 0)
    before = text[:at]
    return rel, before.count("\n") + 1, at - (before.rfind("\n") + 1) + 1


def type_edit(mod, scene, speed=1.0, seed=7, on_key=None):
    """Make the scene's text edit like a person typing: backspace the old middle, then type the new one, writing the
    file after every keystroke. Gaps stay well under the plugin's 500 ms save debounce, so only the end result is
    swapped. on_key(time, line, column) is called after each keystroke with where a caret right after the typed text
    would be (1-based; the edit draws that caret, IntelliJ's own does not follow edits made outside it).
    Returns the time of the last keystroke."""
    import random
    rnd = random.Random(seed)
    rel, old, new = SCENES[scene][1][0]
    path = os.path.join(mod, rel)
    bak = backup_path(mod, rel)
    if not os.path.exists(bak):
        os.makedirs(os.path.dirname(bak), exist_ok=True)
        shutil.copy2(path, bak)
    text = open(path, encoding="utf-8").read()
    at = text.index(old)
    p, mid_old, mid_new = _middle(old, new)
    head, tail = text[:at + p], text[at + p + len(mid_old):]
    def write(middle):
        open(path, "w", encoding="utf-8", newline="").write(head + middle + tail)
        if on_key:
            upto = head + middle
            on_key(time.time(), upto.count("\n") + 1, len(upto) - (upto.rfind("\n") + 1) + 1)
    last = time.time()
    for n in range(len(mid_old) - 1, -1, -1):       # backspaces are quick and even
        time.sleep(0.055 / speed); write(mid_old[:n]); last = time.time()
    time.sleep(0.18 / speed)                        # the beat before typing
    n = 0
    while n < len(mid_new):
        ch = mid_new[n]
        n += 1
        if ch == "\n":                              # Enter: the IDE puts the indent there in the same keystroke
            while n < len(mid_new) and mid_new[n] == " ": n += 1
        gap = rnd.uniform(0.07, 0.13) + (0.08 if ch in ".(\n" else 0)
        time.sleep(min(gap, 0.3) / speed); write(mid_new[:n]); last = time.time()
    return last


def apply(mod, scene):
    for rel, old, new in SCENES[scene][1]:
        path = os.path.join(mod, rel)
        bak = backup_path(mod, rel)
        if not os.path.exists(bak):
            os.makedirs(os.path.dirname(bak), exist_ok=True)
            shutil.copy2(path, bak)
        if old is None:
            shutil.copyfile(new, path)
            os.utime(path, None)
            continue
        text = open(path, encoding="utf-8").read()
        if old not in text:
            if new in text: continue   # already applied
            sys.exit(f"{scene}: '{old[:40]}' not found in {rel}")
        open(path, "w", encoding="utf-8", newline="").write(text.replace(old, new, 1))


def revert(mod, scene=None):
    names = [scene] if scene else list(SCENES)
    for name in names:
        for rel, _, _ in SCENES[name][1]:
            bak = backup_path(mod, rel)
            if os.path.exists(bak):
                shutil.copyfile(bak, os.path.join(mod, rel))
                os.remove(bak)
    shutil.rmtree(os.path.join(mod, ".scene-backup"), ignore_errors=True) if not scene else None


def main():
    args = sys.argv[1:]
    mod = mod_dir(args)
    if not args or args[0] == "list":
        for name, (caption, edits) in SCENES.items():
            print(f"{name:10} {caption}  ({', '.join(sorted({os.path.basename(e[0]) for e in edits}))})")
        return
    cmd = args[0]
    if cmd == "apply": apply(mod, args[1])
    elif cmd == "revert": revert(mod, args[1] if len(args) > 1 else None)
    elif cmd == "run":
        hold, log = 6.0, None
        if "--hold" in args: i = args.index("--hold"); hold = float(args[i + 1]); del args[i:i + 2]
        if "--log" in args: i = args.index("--log"); log = args[i + 1]; del args[i:i + 2]
        out = open(log, "a", encoding="utf-8") if log else None
        for n, scene in enumerate(args[1:]):
            if n: time.sleep(hold)
            t = time.time()
            apply(mod, scene)
            line = f"{t:.3f}\t{scene}\t{SCENES[scene][0]}"
            print(line, flush=True)
            if out: out.write(line + "\n"); out.flush()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
