# Worst / mid / best case for SulfurFrame over the 9-mod mix, within the measured ranges of each environment
# (see model.py).
#   worst: fastest plain restart, SulfurFrame restart 8 s slower than plain (test copy: 52 vs 44 s), fastest stock
#          compile, mixin changes counted as restarts (Mixin hot-swap unverified outside Fabric), slow reload (5 s),
#          slow SulfurFrame swap (2.5 s, the cold first one)
#   best:  slowest plain restart, SulfurFrame restart equal to plain, slowest stock compile, mixin live, reload 1 s
#   usage: python sensitivity.py <dir with mix.json>
import json, sys, os
rows = json.load(open(os.path.join(sys.argv[1], "mix.json")))
K = ["body", "struct", "mixin", "assets", "data", "restart"]
ENVS = {  # restart min/mid/max, classes min/mid/max, resources min/mid/max (seconds, measured)
    "test copy": ((41.8, 44.4, 49.4), (7.0, 8.5, 10.9), (6.5, 6.8, 7.1)),
    "BuildPali": ((80.2, 82.5, 85.1), (19.7, 19.8, 19.8), (18.5, 18.6, 18.8)),
}
def agg(basis, skip=("Mojang/brigadier",)):
    m = {k: 0 for k in K}
    for r, v in rows.items():
        if r in skip: continue
        mix = v["need"] if basis == "commit" else v["files"]; t = sum(mix.get(k, 0) for k in K)
        for k in K: m[k] += mix.get(k, 0) / t
    t = sum(m.values()); return {k: m[k] / t for k in K}
def cost(mix, c): return sum(mix[k] * c[k] for k in K)
for env, (R3, C3, S3) in ENVS.items():
    print(f"#### {env}")
    for basis in ("commit", "file"):
        m = agg(basis)
        for label, i, sf_extra, mixin_live, rl, swap in [("worst", 0, 8, False, 5, 2.5), ("mid", 1, 0, True, 2, 1.0), ("best", 2, 0, True, 1, 0.6)]:
            R, C, S = R3[i], C3[i] + 0.5, S3[i]
            plain = {k: R for k in K}
            hot = {"body": C, "struct": R, "mixin": R, "assets": S + rl, "data": S + rl, "restart": R}
            sf = {"body": swap, "struct": swap + 0.3, "mixin": swap if mixin_live else R + sf_extra,
                  "assets": 0.5 + rl, "data": 0.5 + rl, "restart": R + sf_extra}
            p, h, s = cost(m, plain), cost(m, hot), cost(m, sf)
            print(f"{basis:6} {label:5} plain {p:5.1f}s hotswap {h:5.1f}s sulfur {s:5.1f}s | saved vs plain {p-s:5.1f}s ({100*(1-s/p):3.0f}%), vs hotswap {h-s:5.1f}s ({100*(1-s/h):3.0f}%)")
