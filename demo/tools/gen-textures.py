"""Draws the showcase mod's 16x16 textures (no image library needed). Run: python gen-textures.py"""
import os, struct, zlib, random

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.join(HERE, "..", "showcase-mod", "src", "main", "resources", "assets", "showcase", "textures")
SCENES = os.path.join(HERE, "..", "scenes")

def png(path, rows):
    raw = b"".join(b"\0" + bytes(c for px in row for c in px) for row in rows)
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(data)

def hexc(h, a=255): h = h.lstrip("#"); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)

def from_map(art, pal):
    return [[pal[ch] for ch in line] for line in art]

def jitter(c, rnd, amt):
    d = rnd.randint(-amt, amt)
    return tuple(max(0, min(255, v + d)) for v in c[:3]) + (c[3],)

def tile(light, mid, dark, grout, seed):
    rnd = random.Random(seed); rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if x == 15 or y == 15: c = grout
            elif x == 0 or y == 0: c = light
            elif x == 14 or y == 14: c = dark
            else: c = jitter(mid, rnd, 7)
            row.append(c)
        rows.append(row)
    return rows

# floor A: dark slate with gold grout, so the yellow particles stand out (in the mod);
# floor B: teal lattice (swapped in during the texture scene)
png(os.path.join(MOD, "block", "tile.png"), tile(hexc("#3a3f49"), hexc("#2b2f37"), hexc("#1d2026"), hexc("#b8920e"), 1))
def teal_tile():
    rnd = random.Random(2); rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if x == 15 or y == 15: c = hexc("#0b3b40")
            elif (x + y) % 8 in (0, 1) or (x - y) % 8 in (0, 1): c = hexc("#7ff0e2")
            else: c = jitter(hexc("#1b9aaa"), rnd, 6)
            row.append(c)
        rows.append(row)
    return rows
png(os.path.join(SCENES, "texture", "tile.png"), teal_tile())

T = (0, 0, 0, 0)
cube = [
    "................",
    "..cccccccccccc..",
    ".cyyyyyyyyyyyyd.",
    ".cyyyyyyyyyyyyd.",
    ".cyyyyyyyyyyyyd.",
    ".cyykyyyyykyyyd.",
    ".cykyyyyykyykyd.",
    ".ckyyyyyykyyykd.",
    ".cykyyyykyyykyd.",
    ".cyykyykyyykyyd.",
    ".cyyyyyyyyyyyyd.",
    ".cyyyyyyyyyyyyd.",
    ".cyyyyyyyyyyyyd.",
    ".cyyyyyyyyyyyyd.",
    "..dddddddddddd..",
    "................",
]
png(os.path.join(MOD, "item", "sulfur_cube.png"),
    from_map(cube, {".": T, "c": hexc("#fdf1c0"), "y": hexc("#f4d84a"), "d": hexc("#b8920e"), "k": hexc("#3d2a00")}))

spark = [
    "................",
    ".......p........",
    ".......p........",
    "......pvp.......",
    "......pvp.......",
    ".....pvwvp......",
    "..pppvwwwvppp...",
    ".pvvwwwwwwwvvp..",
    "..pppvwwwvppp...",
    ".....pvwvp......",
    "......pvp.......",
    "......pvp.......",
    ".......p........",
    ".......p........",
    "................",
    "................",
]
png(os.path.join(MOD, "item", "spark.png"),
    from_map(spark, {".": T, "p": hexc("#5b2a9e"), "v": hexc("#9b6cf0"), "w": hexc("#f2ecff")}))
print("textures written")
