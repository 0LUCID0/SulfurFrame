"""Drives one recording take: brings each scene's file up in the IDE editor, waits, makes the scene's edit (scenes.py)
and logs when, while wincapvid + ffmpeg record the IDE window.

  python record-scenes.py --mod <copy> --sandbox <sandbox dir> --pid <IDE pid> --out <take.mp4> [--scenes a,b,c]
         [--lead 3] [--before 2] [--hold 6] [--fps 30]

The log (<take>.timeline.tsv) has one line per event: seconds since the capture started, event, scene, caption.
"""
import argparse, os, subprocess, sys, threading, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import scenes  # noqa: E402

FFMPEG = os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe"
                            r"\ffmpeg-9.0.2-full_build\bin\ffmpeg.exe")
IDEA = os.path.expandvars(r"%LOCALAPPDATA%\Programs\IntelliJ IDEA Community Edition\bin\idea64.exe")

# the file each scene shows in the editor, and the line to put the caret on
SHOW = {
    "particles": ("src/main/java/dev/sulfurframe/showcase/client/Fountain.java", 18),
    "banner": ("src/main/java/dev/sulfurframe/showcase/client/Banner.java", 10),
    "sky": ("src/main/java/dev/sulfurframe/showcase/client/mixin/SkyMixin.java", 15),
    "texture": ("src/main/resources/assets/showcase/textures/block/tile.png", 1),
    "shader": ("src/main/resources/assets/minecraft/shaders/program/showcase_grade.fsh", 10),
    "restart": ("src/main/java/dev/sulfurframe/showcase/ShowcaseItems.java", 16),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mod", required=True); ap.add_argument("--sandbox", required=True)
    ap.add_argument("--pid", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--scenes", default="particles,banner,sky,texture,shader")
    ap.add_argument("--lead", type=float, default=3); ap.add_argument("--before", type=float, default=2)
    ap.add_argument("--hold", type=float, default=6); ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--tail", type=float, default=4)
    ap.add_argument("--activate", help="unused (kept for old command lines)")
    ap.add_argument("--after", help="Gradle tasks run in the mod folder right after each edit, timed (the stock build step)")
    ap.add_argument("--size", default="1936x1216")
    ap.add_argument("--extra", type=float, default=0, help="seconds added to the capture for steps the plan cannot time (Gradle, restarts)")
    a = ap.parse_args()
    names = a.scenes.split(",")
    total = a.lead + len(names) * (a.before + a.hold) + a.tail + a.extra

    env = dict(os.environ, IDEA_PROPERTIES=os.path.join(a.sandbox, "idea.properties"),
               IDEA_VM_OPTIONS=os.path.join(a.sandbox, "idea64.vmoptions"))
    cap = subprocess.Popen([os.path.join(HERE, "..", "out", "wincapvid.exe"), a.pid, "showcase-mod", str(a.fps), str(total)],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    ff = subprocess.Popen([FFMPEG, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", a.size,
                           "-r", str(a.fps), "-i", "-", "-c:v", "libx264", "-preset", "veryfast", "-crf", "14",
                           "-pix_fmt", "yuv420p", a.out], stdin=cap.stdout)
    cap.stdout.close()
    t0 = None
    err_lines = []

    def read_err():
        nonlocal t0
        for raw in cap.stderr:
            line = raw.decode().strip(); err_lines.append(line)
            if line.startswith("start "): t0 = float(line.split()[1])
    threading.Thread(target=read_err, daemon=True).start()
    while t0 is None and cap.poll() is None: time.sleep(0.05)
    if t0 is None: sys.exit("capture did not start: " + " | ".join(err_lines))

    log = open(os.path.splitext(a.out)[0] + ".timeline.tsv", "w", encoding="utf-8")
    def mark(event, scene):
        line = f"{time.time() - t0:.3f}\t{event}\t{scene}\t{scenes.SCENES[scene][0]}"
        print(line, flush=True); log.write(line + "\n"); log.flush()

    def show(rel, line, col=None):
        # IntelliJ's command line counts --line from 1 but --column from 0
        args = [IDEA, "--line", str(line)] + (["--column", str(col - 1)] if col else []) + [os.path.join(a.mod, rel)]
        subprocess.Popen(args, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    keys = open(os.path.splitext(a.out)[0] + ".keys.tsv", "w", encoding="utf-8")
    def on_key(t, line, col):   # where a caret right after the typed text would be; the edit draws it
        keys.write(f"{t - t0:.3f}\t{line}\t{col}\n"); keys.flush()

    time.sleep(a.lead)
    for name in names:
        rel, old, _ = scenes.SCENES[name][1][0]
        if old is None:                                   # a file swapped whole (texture): show it, then copy
            show(SHOW[name][0], 1)
            mark("show", name)
            time.sleep(a.before)
            scenes.apply(a.mod, name)
            mark("save", name)
        else:   # typed keystroke by keystroke. IntelliJ's caret does not follow edits made outside the editor, so it is
                # parked on line 1 (out of the edit's crop) and the edit draws a caret from the keystroke log instead
            _, line, col = scenes.caret(a.mod, name)
            show(rel, 1, 1)
            mark("show", name)
            keys.write(f"{time.time() - t0:.3f}\t{line}\t{col}\n")   # the caret's spot before the first keystroke
            time.sleep(a.before)
            mark("type", name)
            scenes.type_edit(a.mod, name, speed=scenes.SPEED.get(name, 1.0), on_key=on_key)
            mark("save", name)                            # the last keystroke; the plugin swaps 0.5 s after it
        if a.after:                                       # the stock path's build step (gradlew classes / processResources)
            mark("cmd-start", name)
            jdk = os.path.join(os.path.expanduser("~"), ".jdks", "temurin-25.0.5")   # Loom 1.19 needs a Java 25 Gradle
            r = subprocess.run([os.path.join(a.mod, "gradlew.bat")] + a.after.split() + ["--console=plain"], cwd=a.mod,
                               env=dict(os.environ, JAVA_HOME=jdk), capture_output=True, text=True)
            mark("cmd-end", name)
            out = (r.stdout + r.stderr).strip().splitlines()
            print(f"  gradle exit {r.returncode}: " + (out[-1] if out else "(no output)"), flush=True)
        time.sleep(a.hold)
    log.close(); keys.close()
    cap.wait(); ff.wait()
    print("\n".join(err_lines[-1:]))


if __name__ == "__main__":
    main()
