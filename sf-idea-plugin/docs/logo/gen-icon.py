# Generates sf-idea-plugin/src/main/resources/META-INF/pluginIcon.svg (pixel-art sulfur cube with an item frame inside;
# drawn from plain shapes, no game assets). usage: python gen-icon.py <out.svg>
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
# item frame, upright, seen through the cube
S, X0, Y0, M = 1.3, 11.55, 13.0, 13
wood = ["#8a5a2b", "#9c6a37", "#7a4c22", "#a87544", "#6b4019"]
back = ["#c9a073", "#bf9467", "#d1aa7e", "#b88c5f"]
for i in range(M):
    for j in range(M):
        ring = min(i, j, M-1-i, M-1-j)
        if ring == 0: col = "#4a2c10"
        elif ring == 1: col = random.choice(wood)
        else: col = random.choice(back)
        out.append(f'<rect x="{X0+i*S:.2f}" y="{Y0+j*S:.2f}" width="{S+0.02:.2f}" height="{S+0.02:.2f}" fill="{col}"/>')
# a little sulfur nugget shown in the frame
nug = {(5,5):"#fff3a0",(6,5):"#f7e36a",(7,5):"#f2d43c",(4,6):"#f7e36a",(5,6):"#f2d43c",(6,6):"#f2d43c",(7,6):"#e0bd1d",(8,6):"#c9a40f",
       (4,7):"#e0bd1d",(5,7):"#f2d43c",(6,7):"#e0bd1d",(7,7):"#c9a40f",(8,7):"#b8920e",(5,8):"#c9a40f",(6,8):"#b8920e",(7,8):"#9c7a08",(6,4):"#fff3a0"}
for (i, j), c in nug.items():
    out.append(f'<rect x="{X0+i*S:.2f}" y="{Y0+j*S:.2f}" width="{S+0.02:.2f}" height="{S+0.02:.2f}" fill="{c}"/>')
face(T, (R[0]-T[0], R[1]-T[1]), (L[0]-T[0], L[1]-T[1]), "#f4d84a", 0.4, 1.12)
face(L, (C[0]-L[0], C[1]-L[1]), (LB[0]-L[0], LB[1]-L[1]), "#d8b222", 0.32, 0.78)
face(C, (R[0]-C[0], R[1]-C[1]), (B[0]-C[0], B[1]-C[1]), "#b8920e", 0.32, 0.75)
outline = (f'<path d="M{L[0]} {L[1]} L{C[0]} {C[1]} L{R[0]} {R[1]} M{C[0]} {C[1]} L{B[0]} {B[1]}" fill="none" stroke="#5c4500" stroke-opacity="0.45" stroke-width="0.6"/>'
           f'<path d="M{T[0]} {T[1]} L{R[0]} {R[1]} L{RB[0]} {RB[1]} L{B[0]} {B[1]} L{LB[0]} {LB[1]} L{L[0]} {L[1]} Z" fill="none" stroke="#5c4500" stroke-width="1" stroke-linejoin="round"/>')
svg = '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 40 40">\n' + "\n".join(out) + "\n" + outline + "\n</svg>\n"
open(sys.argv[1], "w").write(svg)
