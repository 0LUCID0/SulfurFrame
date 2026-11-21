"""Part-vs-part comparison video from the raw window captures in demo/out.

  python edit-vs.py <out dir>

Each part puts the stock path (left) and SulfurFrame (right) side by side: the game, the edited lines under it, a timer
chip that shows real elapsed time from the save (last keystroke) to the change being visible, and small badges for the
steps (Gradle task, button press). Waits that are only my reaction time while recording (clicking after Gradle ended,
etc.) are cut; long waits that are real (Gradle runClient, the game starting) are sped up and labelled, and the timer
keeps counting real seconds through them. Writes vs-1.mp4 .. vs-4.mp4, vs-summary.mp4, vs-end.mp4 and SulfurFrame-vs.mp4.
"""
import gzip, os, re, subprocess, sys, time

FFMPEG = os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe"
                            r"\ffmpeg-9.0.2-full_build\bin\ffmpeg.exe")
FONTS = os.path.expandvars(r"%LOCALAPPDATA%\Programs\IntelliJ IDEA Community Edition\jbr\lib\fonts")
MONO = os.path.join(FONTS, "JetBrainsMono-Bold.ttf")
MONO_R = os.path.join(FONTS, "JetBrainsMono-Regular.ttf")
SANS = r"C:\Windows\Fonts\seguisb.ttf"
HERE = os.path.dirname(os.path.abspath(__file__))
# log snapshots in <out>/logs (the game renumbers its archives, so they are copied there after recording):
# idea.log = the sandbox IDE's log (the plugin's lines), <take>-old/-new.log.gz = the game runs before/after a restart
IDEA_LOG = GAME_LOGS = None
RES = os.path.join(HERE, "..", "showcase-mod", "src", "main", "resources", "assets", "showcase", "textures")
TILE_BEFORE = os.path.join(RES, "block", "tile.png")
TILE_AFTER = os.path.join(HERE, "..", "scenes", "texture", "tile.png")
# the plugin's Marketplace icon (sf-idea-plugin/.../META-INF/pluginIcon.svg) rendered to 640x640 PNG with headless Edge
# (ffmpeg here has no SVG decoder)
ICON = os.path.join(HERE, "..", "scenes", "plugin-icon.png")
TAKE_T0 = {}     # take -> wall-clock start (epoch), from takes-t0.txt

W, H, FPS = 1920, 1080, 30
BG, CARD = "0x0F1113", "0x16181C"
SULFUR, GREY, WHITE = "0xF4D84A", "0x8A8F98", "0xE8E8E8"
GAME = "686:386:1201:457"            # the game inside the IDE window capture
LANE_W, GAME_H, CODE_H = 880, 495, 110
LX, RX, LANE_Y = 60, 980, 200

# each lane: take, segments (src start, src end, speed), save time, visible-change time (src), code strip centre y,
#            badges (src start, src end, text), timer start offset (seconds already elapsed at the save)
#            caret = (from, to): the keystroke log rows (<take>.keys.tsv) that belong to this lane's scene; the edit draws
#            the caret from them (IntelliJ's own stays parked on line 1, it does not follow edits made outside it)
#            inlay = (src time, px): an inlay hint that appears after the save and pushes the caret right
PARTS = [
    dict(title="Method body", sub="a change inside a method",
         left=dict(take="L5-gradle-hotswap", label="Gradle compile + HotSwap",
                   segs=[(3.57, 11.88, 1), (14.4, 16.4, 1)], save=4.949, change=15.167, code_y=348, caret=(0, 99),
                   badges=[(4.95, 11.88, "gradlew classes"), (14.4, 15.17, "HotSwap")]),
         right=dict(take="R5-sulfurframe", label="SulfurFrame",
                    segs=[(3.57, 8.0, 1)], save=4.971, change=6.667, code_y=348, caret=(0, 9), badges=[])),
    dict(title="Mixin", sub="stock HotSwap cannot apply it: restart",
         # IntelliJ unfolded the imports at 5.2 s in the stock take: the edited line moves down, the strip follows it;
         # the 5 frames it painted blank or half laid out meanwhile (before the save) are cut
         left=dict(take="L8-mixin-restart", label="Restart through Gradle",
                   segs=[(3.6, 5.06, 1), (5.25, 7.5, 1), (19.3, 20.9, 1), (24.4, 78.4, 12), (78.4, 80.9, 1)], save=6.871, change=79.433,
                   code_y=[(0, 348), (5.245, 528)], caret=(0, 99),
                   badges=[(19.3, 20.9, "Stop"), (24.4, 78.4, "gradlew runClient   ×12")],
                   logs=(19.3, 79.2, ["L8-old.log", "L8-new.log"])),
         right=dict(take="R7-sulfurframe-mixin", label="SulfurFrame",
                    segs=[(3.6, 10.0, 1)], save=6.808, change=8.733, code_y=348, caret=(0, 99), badges=[])),
    dict(title="Texture", sub="assets reload in the running game", strip="tile",
         left=dict(take="L3-resources", label="processResources + F3+T",
                   segs=[(3.3, 7.92, 1), (45.0, 46.4, 1), (46.4, 48.4, 4), (48.4, 50.2, 1)], save=4.067, change=48.4,
                   code_y=648,
                   badges=[(4.07, 7.92, "gradlew processResources"), (45.0, 46.4, "F3+T"),
                           (46.4, 48.4, "resource reload   ×4")]),
         right=dict(take="R1-sulfurframe", label="SulfurFrame",
                    segs=[(19.8, 21.6, 1), (21.6, 25.2, 4), (25.2, 27.0, 1)], save=20.588, change=25.2, code_y=648,
                    badges=[(20.6, 21.6, "reload on save"), (21.6, 25.2, "resource reload   ×4")])),
    dict(title="New item", sub="a registration needs a restart either way",
         left=dict(take="L9-register-restart", label="Restart through Gradle",
                   segs=[(3.6, 8.0, 1), (19.3, 20.9, 1), (23.5, 72.0, 12), (72.0, 74.5, 1)], save=7.398, change=73.167,
                   code_y=378, caret=(0, 99), inlay=(7.8, 66),
                   badges=[(19.3, 20.9, "Stop"), (23.5, 72.0, "gradlew runClient   ×12")],
                   logs=(19.3, 73.0, ["L9-old.log", "L9-new.log"])),
         right=dict(take="R8-sulfurframe-restart", label="SulfurFrame",
                    segs=[(3.6, 8.0, 1), (17.2, 19.3, 1), (19.3, 51.6, 12), (51.6, 54.2, 1)], save=7.408, change=52.8,
                    code_y=378, caret=(0, 99), inlay=(7.833, 66),
                    badges=[(17.2, 19.3, "Restart into the same world"), (19.3, 51.6, "no Gradle   ×12")],
                    logs=(17.2, 52.6, ["R8-old.log", "R8-new.log"]))),
]
# the editor in the window capture: column 1 starts at x 143, 12 px per column; the strip crops x 180..900
COL1_X, COL_W, STRIP_X0, STRIP_K = 143, 12, 180, LANE_W / 720
CARET_COLOR = "0xCED0D6"
CARET_LAG = 0.017   # the editor shows a write ~15 ms after it (when no frame change is found for a keystroke)


def lane_out_times(segs):
    """output start of each segment"""
    out, t = [], 0.0
    for s, e, k, *_ in segs:
        out.append(t); t += (e - s) / k
    return out, t


def src_to_out(segs, src):
    outs, _ = lane_out_times(segs)
    for (s, e, k, *_), o in zip(segs, outs):
        if s <= src <= e: return o + (src - s) / k
    # inside a cut: the start of the next segment
    for (s, e, k, *_), o in zip(segs, outs):
        if src < s: return o
    return outs[-1] + (segs[-1][1] - segs[-1][0]) / segs[-1][2]


def timer_expr(lane):
    """drawtext expression: real seconds since the save, frozen at the change. A cut counts as zero (my reaction time
    while recording) unless its segment says how many real seconds it removed (a reload screen)."""
    segs, save, change = lane["segs"], lane["save"], lane["change"]
    outs, _ = lane_out_times(segs)
    pieces, real = [], 0.0
    for (s, e, k, *gap), o in zip(segs, outs):
        a, b = max(s, save), min(e, change)
        if b <= a:
            if s >= change: break
            continue
        oa, ob = o + (a - s) / k, o + (b - s) / k
        pieces.append((oa, ob, real, k))
        real += b - a
        if gap and e < change: real += gap[0]
    total = real
    expr = f"{total:.3f}"
    for oa, ob, base, k in reversed(pieces):
        expr = f"if(lt(t,{ob:.3f}),{base:.3f}+(t-{oa:.3f})*{k},{expr})"
    first = pieces[0][0] if pieces else 0
    return f"if(lt(t,{first:.3f}),0,{expr})", total


def tenths(x):
    """one decimal, cut not rounded: the same digits the timer chip shows"""
    return f"{int(x * 10) / 10:.1f}"


def esc(text):
    return text.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\u2019").replace(",", "\\,")


def ff(path):
    return path.replace("\\", "/").replace(":", "\\:")


def text(t, font, size, color, x, y, enable=None, box=None):
    s = f"drawtext=fontfile='{ff(font)}':expansion=none:text='{esc(t)}':fontsize={size}:fontcolor={color}:x={x}:y={y}"
    if box: s += f":box=1:boxcolor={box}:boxborderw=10"
    if enable: s += f":enable='{enable}'"
    return s


def _clean(line):
    """no account names or user paths on screen; one line of at most 74 characters"""
    line = re.sub(r"[A-Za-z]:[\\/]Users[\\/][^\\/ ]+", "~", line)
    line = line.replace("\t", "  ").rstrip()
    return line if len(line) <= 74 else line[:73] + "\u2026"


def collect_log(take, files, a, b):
    """real log lines (the plugin's log + the game logs of the runs involved) between take seconds a and b"""
    t0 = TAKE_T0[take]
    day = time.strftime("%Y-%m-%d", time.localtime(t0))
    rows = []
    for raw in open(IDEA_LOG, encoding="utf-8", errors="replace"):
        m = re.match(r"(\d{4}-\d\d-\d\d) (\d\d:\d\d:\d\d),(\d{3}) .*?\[SulfurFrame\] \d\d:\d\d:\d\d (.*)", raw)
        if not m or m.group(1) != day: continue
        msg = m.group(4)
        if re.search(r"game (not responding|responsive again)|^detached|input queues", msg): continue
        wall = time.mktime(time.strptime(f"{m.group(1)} {m.group(2)}", "%Y-%m-%d %H:%M:%S")) + int(m.group(3)) / 1000
        rows.append((wall - t0, "[SulfurFrame] " + msg))
    for name in files:
        path = os.path.join(GAME_LOGS, name)
        opener = gzip.open if name.endswith(".gz") else open
        last = None
        with opener(path, "rt", encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                m = re.match(r"\[(\d\d:\d\d:\d\d)\] (.*)", raw)
                if m:
                    last = time.mktime(time.strptime(f"{day} {m.group(1)}", "%Y-%m-%d %H:%M:%S"))
                    rows.append((last - t0, f"[{m.group(1)}] {m.group(2)}"))
                elif last is not None and raw.strip():
                    last += 0.002
                    rows.append((last - t0, raw))
    return [(s, _clean(x)) for s, x in sorted(rows, key=lambda r: r[0]) if a <= s <= b]


def log_overlay(lane, out, tag, x0):
    """the run's real log, scrolling over the game card while the game is gone or starting (at most 12 states/s)"""
    if "logs" not in lane: return []
    a, b, files = lane["logs"]
    lines = collect_log(lane["take"], files, a, b)
    if not lines: return []
    folder = os.path.join(out, "logtxt"); os.makedirs(folder, exist_ok=True)
    states, shown, last_out = [], [], -1.0
    for s, line in lines:
        shown.append(line)
        o = src_to_out(lane["segs"], s)
        if o - last_out >= 1 / 12 or not states:
            states.append([o, list(shown[-15:])]); last_out = o
        else:
            states[-1][1] = list(shown[-15:])
    end_out = src_to_out(lane["segs"], b)
    # a dark terminal panel over the game card, so the log reads over a game that is still quitting
    filters = [f"drawbox=x={x0}:y={LANE_Y}:w={LANE_W}:h={GAME_H}:color=0x0B0C0E@0.80:t=fill"
               f":enable='between(t,{states[0][0]:.3f},{end_out:.3f})'"]
    for i, (o, block) in enumerate(states):
        until = states[i + 1][0] if i + 1 < len(states) else end_out
        if until <= o: continue
        path = os.path.join(folder, f"{tag}-{i:04d}.txt")
        open(path, "w", encoding="utf-8", newline="\n").write("\n".join(block))   # \r\n would read as extra lines
        filters.append(f"drawtext=fontfile='{ff(MONO_R)}':expansion=none:textfile='{ff(path)}':fontsize=15:fontcolor=0xA7B4A9"
                       f":line_spacing=6:x={x0 + 18}:y={LANE_Y + 78}:enable='between(t,{o:.3f},{until:.3f})'")
    return filters


def line_frames(path, until, line_y):
    """the edited line's pixels (grey, 600x20 from column 1) in every frame up to `until` (line_y: a crop expression)"""
    w, h = 600, 20
    p = subprocess.Popen([FFMPEG, "-loglevel", "error", "-t", f"{until:.3f}", "-i", path, "-vf", f"crop={w}:{h}:{COL1_X}:({line_y})-10",
                          "-f", "rawvideo", "-pix_fmt", "gray", "-"], stdout=subprocess.PIPE)
    frames = []
    while True:
        buf = p.stdout.read(w * h)
        if len(buf) < w * h: break
        frames.append(buf)
    return frames, w


def repaint_time(frames, w, t, x, skip=()):
    """the first frame near keystroke time t where the characters around capture x change: the IDE repaints 0-2 frames
    after a write (highlighting repaints elsewhere on the line are ignored)"""
    # only the cell typed into (left of x) or deleted from (right of x): a late repaint of the previous character
    # must not count. skip: times where the crop jumps to another line (the frames around them differ anyway)
    c0, c1 = max(0, x - COL1_X - 10), min(w, x - COL1_X + 10)
    for n in range(max(1, int((t - 0.02) * FPS)), min(len(frames), int((t + 0.3) * FPS) + 1)):
        if n / FPS < t - 0.02 or any((n - 1) / FPS < k <= n / FPS for k in skip): continue
        a, b = frames[n], frames[n - 1]
        if sum(1 for r in range(20) for c in range(c0, c1) if abs(a[r * w + c] - b[r * w + c]) > 40) > 6:
            return n / FPS - 0.001
    return t + CARET_LAG


def caret_overlay(lane, out, x0, length):
    """a text caret in the code strip, right after the typed text: solid while typing, blinking (0.5 s on / off, in
    output time) once the keys stop. Positions come from the take's keystroke log."""
    if "caret" not in lane: return []
    a, b = lane["caret"]
    rows = []
    for raw in open(os.path.join(out, lane["take"] + ".keys.tsv"), encoding="utf-8"):
        t, line, col = raw.split()
        if a <= float(t) < b: rows.append((float(t), int(col)))
    if not rows: return []
    inlay_t, inlay_px = lane.get("inlay", (None, 0))
    until = max(rows[-1][0], inlay_t or 0) + 0.5
    skip = [t for t, _ in lane["code_y"][1:]] if isinstance(lane["code_y"], list) else []
    frames, fw = line_frames(os.path.join(out, lane["take"] + ".mp4"), until, code_y_expr(lane, False))
    events = []                                  # (src time, x in the capture)
    for i, (t, col) in enumerate(rows):
        x = COL1_X - 1 + (col - 1) * COL_W
        events.append((repaint_time(frames, fw, t, x, skip) if i else t, x))
    if inlay_t is not None:
        events.append((repaint_time(frames, fw, inlay_t - 0.1, events[-1][1]), events[-1][1] + inlay_px))
        events.sort()
    y = LANE_Y + GAME_H + 14 + CODE_H // 2 - 14
    boxes = []
    for i, (t, x) in enumerate(events):
        start = src_to_out(lane["segs"], t)
        end = src_to_out(lane["segs"], events[i + 1][0]) if i + 1 < len(events) else length
        if end <= start: continue
        sx = x0 + round((x - STRIP_X0) * STRIP_K)
        on = start
        while on < end:                         # on 0.5 s, off 0.5 s; the next keystroke restarts it solid
            boxes.append(f"drawbox=x={sx}:y={y}:w=3:h=28:color={CARET_COLOR}:t=fill"
                         f":enable='gte(t,{on:.3f})*lt(t,{min(on + 0.5, end):.3f})'")
            on += 1.0
    return boxes


def tile_strip(out):
    """the texture part's strip under the game: the file's old and new 16x16 art, big and pixel-sharp"""
    dst = os.path.join(out, "vs-tile-strip.png")
    g = (f"color=c={CARD}:s={LANE_W}x{CODE_H}:d=1[bg];[0]scale=88:88:flags=neighbor[b];[1]scale=88:88:flags=neighbor[a];"
         f"[bg][b]overlay=300:11[x];[x][a]overlay=492:11,"
         + text("tile.png", MONO_R, 22, GREY, 60, 42) + "," + text("\u2192", MONO, 40, WHITE, 418, 30) + "[v]")
    script = os.path.join(out, "vs-tile-strip.filter")
    open(script, "w", encoding="utf-8").write(g)
    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-i", TILE_BEFORE, "-i", TILE_AFTER, "-/filter_complex", script,
                    "-map", "[v]", "-frames:v", "1", dst], check=True)
    return dst


def build_lane(idx, lane, length, tag, strip_input=None):
    """filter chain for one lane: segments -> game card over code strip, padded to the part length"""
    n = len(lane["segs"])
    parts = [f"[{idx}:v]split={n}" + "".join(f"[{tag}s{i}]" for i in range(n))]
    for i, (s, e, k, *_) in enumerate(lane["segs"]):
        parts.append(f"[{tag}s{i}]trim={s - 0.001:.4f}:{e - 0.001:.4f},setpts=(PTS-STARTPTS)/{k}[{tag}t{i}]")
    parts.append("".join(f"[{tag}t{i}]" for i in range(n)) + f"concat=n={n}:v=1:a=0,fps={FPS}[{tag}c]")
    _, own = lane_out_times(lane["segs"])
    pad = max(0.0, length - own)
    parts.append(f"[{tag}c]tpad=stop_mode=clone:stop_duration={pad:.3f},split[{tag}a][{tag}b]")
    parts.append(f"[{tag}a]crop={GAME},scale={LANE_W}:{GAME_H}:flags=lanczos[{tag}g]")
    if strip_input is None:
        parts.append(f"[{tag}b]crop=720:90:180:({code_y_expr(lane, True)})-45,scale={LANE_W}:{CODE_H}:flags=lanczos[{tag}k]")
    else:   # a still strip (the texture part); the lane's own frames only drive the timing
        parts.append(f"[{tag}b]nullsink")
        parts.append(f"[{strip_input}]loop=loop=-1:size=1,fps={FPS},trim=0:{length:.3f},setpts=PTS-STARTPTS[{tag}k]")
    parts.append(f"[{tag}g]pad={LANE_W}:{GAME_H + 14 + CODE_H}:0:0:color={CARD}[{tag}gp]")
    parts.append(f"[{tag}gp][{tag}k]overlay=0:{GAME_H + 14}[{tag}lane]")
    return parts


def code_y_expr(lane, out_time):
    """the code strip's centre line in the capture, as a crop expression: a number, or a step function of time when the
    editor moved the edited line (out_time: the lane's output time; otherwise the take's own time)"""
    cy = lane["code_y"]
    if isinstance(cy, int): return str(cy)
    expr = str(cy[-1][1])
    for (t, y), (t_next, _) in reversed(list(zip(cy, cy[1:]))):
        # half a frame early: a switch that lands on a frame's own timestamp must apply to that frame
        at = (src_to_out(lane["segs"], t_next) if out_time else t_next) - 0.5 / FPS
        expr = rf"if(lt(t\,{at:.3f})\,{y}\,{expr})"
    return expr


def snap(x):
    """the time of the first frame at or after x: trim starts there, so the overlays must count from there too"""
    return -(-round(x * FPS * 1000) // 1000) / FPS


def render_part(n, part, out):
    L, R = part["left"], part["right"]
    for lane in (L, R):
        lane["segs"] = [(snap(s), snap(e), k, *g) for s, e, k, *g in lane["segs"]]
    _, lenL = lane_out_times(L["segs"]); _, lenR = lane_out_times(R["segs"])
    length = max(lenL, lenR) + 1.2
    strip = 2 if part.get("strip") == "tile" else None
    if strip is not None:   # the still strip is used by both lanes
        g = [f"[2:v]split[st0][st1]"]
        g += build_lane(0, L, length, "L", "st0") + build_lane(1, R, length, "R", "st1")
    else:
        g = build_lane(0, L, length, "L") + build_lane(1, R, length, "R")
    g.append(f"color=c={BG}:s={W}x{H}:r={FPS}:d={length:.3f}[bg]")
    g.append(f"[bg][Llane]overlay={LX}:{LANE_Y}[b1]")
    g.append(f"[b1][Rlane]overlay={RX}:{LANE_Y}[b2]")
    draw = []
    draw.append(text(part["title"], SANS, 46, WHITE, LX, 56))
    draw.append(text(part["sub"], SANS, 26, GREY, LX, 116))
    draw.append(text(L["label"], SANS, 28, GREY, LX, LANE_Y - 44))
    draw.append(text(R["label"], SANS, 28, SULFUR, RX, LANE_Y - 44))
    # logs first: their panel goes under the timer chips and badges
    draw += log_overlay(L, out, f"p{n}L", LX) + log_overlay(R, out, f"p{n}R", RX)
    totals = []
    for lane, x0, col in ((L, LX, WHITE), (R, RX, SULFUR)):
        expr, total = timer_expr(lane)
        totals.append(total)
        t_change = src_to_out(lane["segs"], lane["change"])
        num = f"%{{eif\\:floor({expr})\\:d}}.%{{eif\\:floor(mod(({expr})*10\\,10))\\:d}} s"
        chip = (f"drawtext=fontfile='{ff(MONO)}':text='{num}':fontsize=34:fontcolor=white:box=1:boxcolor={CARD}@0.88"
                f":boxborderw=12:x={x0 + LANE_W - 190}:y={LANE_Y + 22}")
        draw.append(chip)
        draw.append(chip.replace("fontcolor=white", f"fontcolor={col}") + f":enable='gte(t,{t_change:.3f})'")
        for s, e, label in lane["badges"]:
            a, b = src_to_out(lane["segs"], s), src_to_out(lane["segs"], e)
            draw.append(text(label, MONO_R, 26, WHITE, x0 + 22, LANE_Y + 24, f"between(t,{a:.3f},{max(b, a + 0.6):.3f})",
                             box=f"{CARD}@0.88"))
        if part.get("strip"): continue
        draw += caret_overlay(lane, out, x0, length)
        # the edited line: an outline while it is typed and just after the save
        t_save = src_to_out(lane["segs"], lane["save"])
        y_line = LANE_Y + GAME_H + 14 + CODE_H // 2 - 18
        draw.append(f"drawbox=x={x0 + 4}:y={y_line}:w={LANE_W - 8}:h=36:color={SULFUR}@0.9:t=2"
                    f":enable='between(t,{max(0, t_save - 1.6):.3f},{t_save + 0.9:.3f})'")
    res = f"{tenths(totals[0])} s  \u2192  {tenths(totals[1])} s"
    t_res = max(src_to_out(L["segs"], L["change"]), src_to_out(R["segs"], R["change"])) + 0.2
    draw.append(text(res, MONO, 44, WHITE, f"({W}-text_w)/2", 900, f"gte(t,{t_res:.3f})"))
    g.append("[b2]" + ",".join(draw) + "[v]")
    script = os.path.join(out, f"vs-{n}.filter")
    open(script, "w", encoding="utf-8").write(";\n".join(g))
    dst = os.path.join(out, f"vs-{n}.mp4")
    extra = ["-i", tile_strip(out)] if strip is not None else []
    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-i", os.path.join(out, L["take"] + ".mp4"),
                    "-i", os.path.join(out, R["take"] + ".mp4")] + extra + ["-/filter_complex", script, "-map", "[v]",
                    "-t", f"{length:.3f}", "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p",
                    dst], check=True)
    print(f"part {n}: {length:.1f} s, {res}")
    return part["title"], totals


BASE_LEN = 1.8   # seconds of the starting code shown before each part


def render_base(n, part, out):
    """vs-<n>-base.mp4: the part's title over the code as it is before the edit (the stock take's editor before the first
    keystroke, the edited line outlined), or the old texture for the texture part, so the edit that follows has context"""
    L = part["left"]
    draw = [text(part["title"], SANS, 46, WHITE, LX, 56), text(part["sub"], SANS, 26, GREY, LX, 116)]
    dst = os.path.join(out, f"vs-{n}-base.mp4")
    if part.get("strip") == "tile":
        size = 264
        g = (f"color=c={BG}:s={W}x{H}:r={FPS}:d={BASE_LEN}[bg];[0]scale={size}:{size}:flags=neighbor,loop=loop=-1:size=1,"
             f"fps={FPS}[im];[bg][im]overlay=({W}-{size})/2:330:shortest=1,"
             + text("before: tile.png", MONO_R, 28, GREY, f"({W}-text_w)/2", 330 + size + 30))
        src = ["-i", TILE_BEFORE]
    else:
        cy = L["code_y"] if isinstance(L["code_y"], int) else L["code_y"][0][1]
        k, cw, ch = 1.4, 1140, 330                       # 11 editor lines around the edited one, 1.4x
        pw, ph = round(cw * k), round(ch * k)
        px, py = (W - pw) // 2, (H - ph) // 2 + 20
        g = (f"color=c={BG}:s={W}x{H}:r={FPS}:d={BASE_LEN}[bg];[0]crop={cw}:{ch}:40:{cy - ch // 2},"
             f"scale={pw}:{ph}:flags=lanczos,loop=loop=-1:size=1,fps={FPS}[ed];[bg][ed]overlay={px}:{py}:shortest=1,"
             + text("before the edit", SANS, 28, GREY, px, py - 44) + ","
             + f"drawbox=x={px}:y={py + ph // 2 - 22}:w={pw}:h=44:color={SULFUR}@0.9:t=2")
        src = ["-ss", f"{snap(L['segs'][0][0]) + 0.05:.3f}", "-i", os.path.join(out, L["take"] + ".mp4")]
    script = os.path.join(out, f"vs-{n}-base.filter")
    open(script, "w", encoding="utf-8").write(g + "," + ",".join(draw) + ",fade=t=in:st=0:d=0.25[v]")
    subprocess.run([FFMPEG, "-y", "-loglevel", "error"] + src + ["-/filter_complex", script, "-map", "[v]",
                    "-t", f"{BASE_LEN}", "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p", dst],
                   check=True)


def render_card(out, name, lines, length, icon_y=None):
    """a title card: centred lines that come in one after another, optionally the plugin's Marketplace icon"""
    draw = [text(t, font, size, color, f"({W}-text_w)/2", y, f"gte(t,{at})") for t, font, size, color, y, at in lines]
    head = f"color=c={BG}:s={W}x{H}:r={FPS}:d={length}"
    if icon_y is not None:
        head += f"[bg];[0]scale=170:170:flags=lanczos,format=rgba,loop=loop=-1:size=1,fps={FPS}[ic];[bg][ic]overlay=({W}-170)/2:{icon_y}:shortest=1"
    script = os.path.join(out, name.replace(".mp4", ".filter"))
    open(script, "w", encoding="utf-8").write(head + "," + ",".join(draw) + ",fade=t=in:st=0:d=0.4[v]")
    dst = os.path.join(out, name)
    inputs = ["-i", ICON] if icon_y is not None else []
    subprocess.run([FFMPEG, "-y", "-loglevel", "error"] + inputs + ["-/filter_complex", script, "-map", "[v]", "-t", str(length),
                    "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p", dst], check=True)


def main():
    global IDEA_LOG, GAME_LOGS
    out = sys.argv[1]
    GAME_LOGS = os.path.join(out, "logs")
    IDEA_LOG = os.path.join(GAME_LOGS, "idea.log")
    for line in open(os.path.join(out, "takes-t0.txt"), encoding="utf-8"):
        m = re.match(r"(\S+)\s+dur=\s*[\d.]+\s+t0=([\d.]+)", line)
        if m: TAKE_T0[m.group(1)] = float(m.group(2))
    only = sys.argv[2:] if len(sys.argv) > 2 else None
    results = []
    for n, part in enumerate(PARTS, 1):
        if only and str(n) not in only: continue
        render_base(n, part, out)
        results.append(render_part(n, part, out))
    if only: return
    rows = [(f"{title:<12} {tenths(a):>5} s  \u2192  {tenths(b):>4} s", MONO, 40, WHITE, 300 + i * 70, 0.3 + i * 0.15)
            for i, (title, (a, b)) in enumerate(results)]
    render_card(out, "vs-summary.mp4", [("Waiting per edit, same PC, same mod", SANS, 34, GREY, 200, 0)] + rows +
                [("Measured on 9 large mods: 65\u201385% of changes go in live.", SANS, 30, GREY, 640, 1.0)], 4.5)
    render_card(out, "vs-intro.mp4", [("SulfurFrame", SANS, 84, SULFUR, 470, 0.1),
                                      ("Your Minecraft mod's dev client, inside IntelliJ IDEA", SANS, 36, WHITE, 590, 0.35),
                                      ("You save. How long until the game shows it?", SANS, 34, GREY, 700, 1.1)], 3.6, icon_y=260)
    # no version numbers on the end card: what is supported keeps growing; the listing says what it is today
    render_card(out, "vs-end.mp4", [("SulfurFrame", SANS, 84, SULFUR, 470, 0.1),
                                    ("Free IntelliJ IDEA plugin  \u00b7  open source (MIT)", SANS, 34, WHITE, 590, 0.3),
                                    ("Fabric today. More loaders and Minecraft versions are on the way.", SANS, 30, GREY, 660, 0.5),
                                    ("JetBrains Marketplace  \u00b7  github.com/0LUCID0/Sulfur_Frame", MONO_R, 26, GREY, 730, 0.7),
                                    ("Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.", SANS, 20, GREY, 1010, 0.7)],
                4.5, icon_y=260)
    lst = os.path.join(out, "vs-list.txt")
    open(lst, "w").write("file 'vs-intro.mp4'\n" + "".join(f"file 'vs-{i}-base.mp4'\nfile 'vs-{i}.mp4'\n" for i in range(1, len(PARTS) + 1))
                         + "file 'vs-summary.mp4'\nfile 'vs-end.mp4'\n")
    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy",
                    os.path.join(out, "SulfurFrame-vs.mp4")], check=True)
    print("SulfurFrame-vs.mp4 written")


if __name__ == "__main__":
    main()
