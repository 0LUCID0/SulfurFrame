# Generates sf-idea-plugin/src/main/resources/META-INF/pluginIcon.svg: a pixel-art sulfur cube with a < > / face on its
# left face and an item frame standing inside it (drawn from plain shapes, no game assets). usage: python gen-icon.py <out.svg>
import random, sys
random.seed(7)
out = []
def poly(pts, fill, op=None):
    d = " ".join(f"{x:.2f},{y:.2f}" for x, y in pts)
    o = ''
    out.append(f'<polygon points="{d}" fill="{fill}" stroke="{fill}" stroke-width="0.06"{o}/>')
def shade(hexc, f):
    r, g, b = (int(hexc[i:i+2], 16) for i in (1, 3, 5))
    c = lambda v: max(0, min(255, int(v * f)))
    return f"#{c(r):02x}{c(g):02x}{c(b):02x}"
T, L, R, C = (20, 2.5), (4.5, 11.5), (35.5, 11.5), (20, 20.5)
LB, RB, B = (4.5, 29.5), (35.5, 29.5), (20, 38.5)
N = 8
def face(o, du, dv, base, op, border):
    out.append(f'<g opacity="{op}">')
    for i in range(N):
        for j in range(N):
            p = lambda u, v: (o[0] + du[0]*u + dv[0]*v, o[1] + du[1]*u + dv[1]*v)
            u0, u1, v0, v1 = i/N, (i+1)/N, j/N, (j+1)/N
            edge = i in (0, N-1) or j in (0, N-1)
            f = random.choice([0.9, 0.96, 1.0, 1.0, 1.05, 1.1])
            col = shade(base, border if edge else f)
            poly([p(u0, v0), p(u1, v0), p(u1, v1), p(u0, v1)], col, op)
    out.append('</g>')
# opaque sulfur interior: the see-through faces show sulfur, not the IDE background (contrast on dark themes)
poly([T, R, RB, B, LB, L], "#fdf1c0")
# item frame, in the cube's own projection
M, A = 13, 0.12                      # cells per side, margin from the face edges (fraction of the face)
# the frame stands inside the cube on a plane parallel to its left face, halfway in; the face is drawn on the
# cube's left face, in front of it
FU = (C[0]-L[0], C[1]-L[1])          # along the face, left -> front corner
FV = (LB[0]-L[0], LB[1]-L[1])        # down
DEPTH = ((R[0]-C[0]) * 0.5, (R[1]-C[1]) * 0.5)
FO = (L[0] + DEPTH[0], L[1] + DEPTH[1])
LU = (C[0]-L[0], C[1]-L[1])          # left face axes, for the face
LV = (LB[0]-L[0], LB[1]-L[1])
def cell(i, j, col, o=None, du=None, dv=None):
    o, du, dv = o or FO, du or FU, dv or FV
    k = (1 - 2*A) / M
    q = lambda u, v: (o[0] + du[0]*u + dv[0]*v, o[1] + du[1]*u + dv[1]*v)
    u0, v0 = A + i*k, A + j*k
    poly([q(u0, v0), q(u0+k, v0), q(u0+k, v0+k), q(u0, v0+k)], col)
wood = ["#8a5a2b", "#9c6a37", "#7a4c22", "#a87544", "#6b4019"]
back = ["#c9a073", "#bf9467", "#d1aa7e", "#b88c5f"]
for i in range(M):
    for j in range(M):
        ring = min(i, j, M-1-i, M-1-j)
        if ring == 0: col = "#4a2c10"
        elif ring == 1: col = random.choice(wood)
        else: col = random.choice(back)
        cell(i, j, col)
face(T, (R[0]-T[0], R[1]-T[1]), (L[0]-T[0], L[1]-T[1]), "#f4d84a", 0.4, 1.12)
face(L, (C[0]-L[0], C[1]-L[1]), (LB[0]-L[0], LB[1]-L[1]), "#d8b222", 0.32, 0.78)
face(C, (R[0]-C[0], R[1]-C[1]), (B[0]-C[0], B[1]-C[1]), "#b8920e", 0.32, 0.75)
# the sulfur cube's own face, on its left face: eyes "<" ">", mouth "/" (code brackets), the mouth kept apart
# from the eyes
face_px = [(3,2),(2,3),(1,4),(2,5),(3,6), (4,2),(3,3),(2,4),(3,5),(4,6),        # <  two cells wide
           (9,2),(10,3),(11,4),(10,5),(9,6), (8,2),(9,3),(10,4),(9,5),(8,6),       # >
           (5,10),(6,9),(7,8), (5,11),(6,10),(7,9)]                                # /  two cells tall
# the right eye sits over the frame's dark border: give it its own light rim so it stays readable
right_eye = [(9,2),(10,3),(11,4),(10,5),(9,6), (8,2),(9,3),(10,4),(9,5),(8,6)]
rim = {(i+di, j+dj) for (i, j) in right_eye for di, dj in ((1,0),(-1,0),(0,1),(0,-1))} - set(face_px)
for (i, j) in sorted(rim):
    if 0 <= i < M and 0 <= j < M:
        cell(i, j, "#fdf1c0", L, LU, LV)
for (i, j) in face_px:
    cell(i, j, "#3d2a00", L, LU, LV)
outline = (f'<path d="M{L[0]} {L[1]} L{C[0]} {C[1]} L{R[0]} {R[1]} M{C[0]} {C[1]} L{B[0]} {B[1]}" fill="none" stroke="#5c4500" stroke-opacity="0.45" stroke-width="0.6"/>'
           f'<path d="M{T[0]} {T[1]} L{R[0]} {R[1]} L{RB[0]} {RB[1]} L{B[0]} {B[1]} L{LB[0]} {LB[1]} L{L[0]} {L[1]} Z" fill="none" stroke="#5c4500" stroke-width="1" stroke-linejoin="round"/>')
svg = '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 40 40">\n' + "\n".join(out) + "\n" + outline + "\n</svg>\n"
open(sys.argv[1], "w").write(svg)
