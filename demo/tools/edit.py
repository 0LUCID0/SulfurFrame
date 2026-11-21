"""Cuts the raw takes (window captures of the sandbox IDE, 1936x1216) into variants to compare.

  python edit.py <out dir>

Reads take1-hotswap.mp4 / take2-restart.mp4 and their .timeline.tsv from <out dir>, writes:
  A-wide.mp4        whole window (title bar cut), 1920x1080, captions in a band at the bottom
  B-zoom.mp4        1280x720 camera that moves code -> game per scene; every framing is 1:1 or downscaled, never upscaled
  C-restart.mp4     the restart take: the wait sped up 8x and labelled, 1280x720
  gif-hotswap.gif   particles scene of B, 800 px, 15 fps (the README / Marketplace GIF candidate)
"""
import os, subprocess, sys

FFMPEG = os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe"
                            r"\ffmpeg-9.0.2-full_build\bin\ffmpeg.exe")
FONT = os.path.expandvars(r"%LOCALAPPDATA%\Programs\IntelliJ IDEA Community Edition\jbr\lib\fonts\JetBrainsMono-Bold.ttf")
FONT_F = FONT.replace("\\", "/").replace(":", "\\:")
SULFUR = "0xF4D84A"

# the window capture has an 8 px frame; the content below the title bar starts at y = 8 + 50
CROP = "crop=1920:1080:8:58"
GAME_CX, GAME_CY = 1535, 591    # middle of the game panel, in cropped coordinates
CODE_CX, CODE_CY = 640, 400     # middle of the code that matters


def timeline(path):
    rows = []
    for line in open(path, encoding="utf-8"):
        t, ev, scene, caption = line.rstrip("\n").split("\t")
        rows.append((float(t), ev, scene, caption))
    return rows


def run(args):
    print(" ".join(a if " " not in a else f'"{a}"' for a in args[:6]), "...", args[-1])
    subprocess.run([FFMPEG, "-y", "-loglevel", "error"] + args, check=True)


def esc(text):
    return text.replace("\\", "\\\\").replace(":", "\\:").replace("'", "’").replace(",", "\\,")


def captions(rows, end, size, band):
    """drawtext filters: each scene's caption from its save to the next scene's show (or the end)."""
    saves = [(t, cap) for t, ev, _, cap in rows if ev == "save"]
    shows = [t for t, ev, _, _ in rows if ev == "show"]
    out = []
    for i, (t, cap) in enumerate(saves):
        until = next((s for s in shows if s > t), end)
        out.append(f"drawtext=fontfile='{FONT_F}':text='{esc(cap)}':fontsize={size}:fontcolor={SULFUR}"
                   f":x=(w-text_w)/2:y=h-{band}/2-text_h/2:enable='between(t,{t - 0.2:.2f},{until:.2f})'")
    return out


def camera(rows, end):
    """zoompan expressions: z=1 (whole frame) or 1.5 (a 1280x720 window at 1:1); x/y follow code, then the game."""
    keys = [(0.0, 1.0, 960, 540)]
    for t, ev, scene, _ in rows:
        if ev == "show": keys.append((t, 1.5, CODE_CX, CODE_CY))
        if ev == "save": keys.append((t + 0.6, 1.5, GAME_CX, GAME_CY))
    keys.append((end - 1.5, 1.0, 960, 540))

    def piece(idx):   # value of field idx (1 zoom, 2 cx, 3 cy) at time it, eased between keys over 0.5 s
        expr = str(keys[-1][idx])
        for (t0, *a), (t1, *b) in reversed(list(zip(keys, keys[1:]))):
            v0, v1 = a[idx - 1], b[idx - 1]
            ease = f"(1-cos(PI*min(max((it-{t1 - 0.5:.2f})/0.5,0),1)))/2"
            expr = f"if(lt(it,{t1:.2f}),{v0}+({v1}-{v0})*{ease},{expr})"
        return f"if(lt(it,{keys[0][0]:.2f}),{keys[0][idx]},{expr})"
    z = piece(1)
    x = f"min(max(({piece(2)})-iw/zoom/2,0),iw-iw/zoom)"
    y = f"min(max(({piece(3)})-ih/zoom/2,0),ih-ih/zoom)"
    return f"zoompan=z='{z}':x='{x}':y='{y}':d=1:s=1280x720:fps=30"


def duration(path):
    r = subprocess.run([FFMPEG.replace("ffmpeg.exe", "ffprobe.exe"), "-v", "error", "-show_entries", "format=duration",
                        "-of", "csv=p=0", path], capture_output=True, text=True)
    return float(r.stdout.strip())


def main():
    out = sys.argv[1]
    hot = os.environ.get("SF_HOT", "take1-hotswap"); t1 = os.path.join(out, hot + ".mp4")
    rows = timeline(os.path.join(out, hot + ".timeline.tsv"))
    end = duration(t1)

    band = 90
    vf = [CROP, f"pad=1920:{1080 + band}:0:0:color=0x16181c", "scale=1920:-2"] + captions(rows, end, 44, band)
    run(["-i", t1, "-vf", ",".join(vf), "-c:v", "libx264", "-crf", "18", "-preset", "medium", "-pix_fmt", "yuv420p",
         os.path.join(out, "A-wide.mp4")])

    band = 64
    vf = [CROP, camera(rows, end), f"drawbox=x=0:y=ih-{band}:w=iw:h={band}:color=0x16181c@0.85:t=fill"] + captions(rows, end, 30, band)
    run(["-i", t1, "-vf", ",".join(vf), "-c:v", "libx264", "-crf", "18", "-preset", "medium", "-pix_fmt", "yuv420p",
         os.path.join(out, "B-zoom.mp4")])

    # GIF: the particles scene of B (show .. next show), palette made from the clip itself
    shows = [t for t, ev, _, _ in rows if ev == "show"]
    g0, g1 = shows[0] - 0.5, shows[1]
    b = os.path.join(out, "B-zoom.mp4")
    pal = os.path.join(out, "gif-palette.png")
    run(["-ss", f"{g0:.2f}", "-to", f"{g1:.2f}", "-i", b, "-vf", "fps=15,scale=800:-1:flags=lanczos,palettegen=stats_mode=diff", pal])
    run(["-ss", f"{g0:.2f}", "-to", f"{g1:.2f}", "-i", b, "-i", pal, "-lavfi",
         "fps=15,scale=800:-1:flags=lanczos[v];[v][1:v]paletteuse=dither=sierra2_4a", os.path.join(out, "gif-hotswap.gif")])

    # restart take: normal speed until the click, the wait at 8x with a label, normal again once back in the world
    rst = os.environ.get("SF_RESTART", "take2-restart"); t2 = os.path.join(out, rst + ".mp4")
    rows2 = timeline(os.path.join(out, rst + ".timeline.tsv"))
    save = next(t for t, ev, _, _ in rows2 if ev == "save")
    fast0, fast1 = float(os.environ.get("SF_FAST0", "18")), float(os.environ.get("SF_FAST1", "57"))
    end2 = duration(t2)
    cap = esc(rows2[0][3])
    base = f"{CROP},scale=1280:720"
    label = (f"drawtext=fontfile='{FONT_F}':text='restarting  ×8':fontsize=34:fontcolor=white:box=1:boxcolor=0x16181c@0.8"
             f":boxborderw=12:x=w-text_w-40:y=40")
    capt = (f"drawtext=fontfile='{FONT_F}':text='{cap}':fontsize=30:fontcolor={SULFUR}:box=1:boxcolor=0x16181c@0.85"
            f":boxborderw=16:x=(w-text_w)/2:y=h-text_h-30:enable='gte(t,{save - 0.2:.2f})'")
    run(["-i", t2, "-filter_complex",
         f"[0:v]{base},{capt},split=3[a][b][c];"
         f"[a]trim=0:{fast0},setpts=PTS-STARTPTS[s1];"
         f"[b]trim={fast0}:{fast1},setpts=(PTS-STARTPTS)/8,{label}[s2];"
         f"[c]trim={fast1}:{end2:.2f},setpts=PTS-STARTPTS[s3];"
         f"[s1][s2][s3]concat=n=3:v=1:a=0[v]",
         "-map", "[v]", "-c:v", "libx264", "-crf", "18", "-preset", "medium", "-pix_fmt", "yuv420p",
         os.path.join(out, "C-restart.mp4")])


if __name__ == "__main__":
    main()
