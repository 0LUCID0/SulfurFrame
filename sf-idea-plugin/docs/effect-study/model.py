# Seconds of waiting per edit-run step, by what the change needs, under three workflows, in two measured
# environments (one machine, 2026-11-17; SulfurFrame's swap timings are from the test copy in both, see below).
#
#   test copy   lucid-study-mod copy: 1 Gradle project, 56 mods (measure-baseline.sh)
#     plain restart   runClient --quickPlaySingleplayer -> in the world: 49.4 / 41.9 / 41.8 s
#     stock HotSwap   gradlew classes after a body edit: 10.9 / 7.0 / 7.5 s (+ JDWP redefinition, < 1 s)
#     stock assets    gradlew processResources: 7.1 / 6.5 / 6.9 s (+ the reload)
#   BuildPali   9 Gradle projects, 65 mods (measure-buildpali.sh); Gradle configuration alone 30.7 s per call
#     plain restart   85.1 / 82.1 / 80.2 s
#     stock HotSwap   19.7 / 19.8 / 19.8 s (+ < 1 s)
#     stock assets    18.8 / 18.5 / 18.5 s (+ the reload)
#   SulfurFrame     swap incl. compile 0.5-1.3 s warm (no Gradle call); restart = a Gradle runClient like plain
#                   (test copy: 38-52 s vs plain 42-49 s), so it is taken as equal to plain
# Assumptions, not measured: the reload after copying resources (2 s, same with or without SulfurFrame), and for
# "plain+manual" quitting the game and clicking into the world by hand (+13 s).
#   usage: python model.py <dir with mix.json>
import json, sys, os

RELOAD, MANUAL = 2, 13
ENVS = {
    "test copy": {"restart": 44.4, "classes": 8.5, "resources": 6.8},
    "BuildPali": {"restart": 82.5, "classes": 20.3, "resources": 18.6},
}
KEYS = ["body", "struct", "mixin", "assets", "data", "restart"]
SKIP = ("Mojang/brigadier",)   # a library, not a mod: shown but kept out of the totals

def costs(env):
    R, C, S = env["restart"], env["classes"], env["resources"]
    return {
        "plain":        {k: R for k in KEYS},
        "plain+manual": {k: R + MANUAL for k in KEYS},
        "hotswap":      {"body": C, "struct": R, "mixin": R, "assets": S + RELOAD, "data": S + RELOAD, "restart": R},
        "sulfur":       {"body": 1.0, "struct": 1.3, "mixin": 1.0, "assets": 0.5 + RELOAD, "data": 0.5 + RELOAD, "restart": R},
    }

def per_step(mix, cost):
    t = sum(mix.get(k, 0) for k in KEYS) or 1
    return sum(mix.get(k, 0) / t * cost[k] for k in KEYS)

rows = json.load(open(os.path.join(sys.argv[1], "mix.json")))
agg = {"commit": {k: 0 for k in KEYS}, "file": {k: 0 for k in KEYS}}
for repo, r in rows.items():
    if repo in SKIP: continue
    for basis, mix in (("commit", r["need"]), ("file", r["files"])):
        tot = sum(mix.get(k, 0) for k in KEYS) or 1
        for k in KEYS: agg[basis][k] += mix.get(k, 0) / tot          # every mod weighs the same

for name, env in ENVS.items():
    c = costs(env)
    print(f"#### {name}")
    print(f"{'repo':42} {'basis':6} {'plain':>6} {'hotswap':>8} {'sulfur':>7} {'vs plain':>9} {'vs hotswap':>11}  live")
    for repo, r in rows.items():
        for basis, mix in (("commit", r["need"]), ("file", r["files"])):
            m = {k: mix.get(k, 0) for k in KEYS}; tot = sum(m.values()) or 1
            p, h, s = per_step(m, c["plain"]), per_step(m, c["hotswap"]), per_step(m, c["sulfur"])
            print(f"{repo:42} {basis:6} {p:6.1f} {h:8.1f} {s:7.1f} {100*(1-s/p):8.0f}% {100*(1-s/h):10.0f}%  {100*(1-m['restart']/tot):3.0f}%")
    for basis in ("commit", "file"):
        m = agg[basis]; t = sum(m.values())
        v = {wf: per_step(m, c[wf]) for wf in c}
        print(f"ALL 9 mods ({basis}) mix " + " ".join(f"{k}={100*m[k]/t:.0f}%" for k in KEYS))
        print("   s/step " + "  ".join(f"{wf} {v[wf]:.1f}" for wf in c))
        print(f"   sulfur saves {v['plain']-v['sulfur']:.1f} s ({100*(1-v['sulfur']/v['plain']):.0f}%) vs plain, "
              f"{v['plain+manual']-v['sulfur']:.1f} s ({100*(1-v['sulfur']/v['plain+manual']):.0f}%) vs plain+manual, "
              f"{v['hotswap']-v['sulfur']:.1f} s ({100*(1-v['sulfur']/v['hotswap']):.0f}%) vs stock HotSwap")
        for steps in (20, 60, 150):
            print(f"   {steps:3} steps/day: " + ", ".join(f"{wf} {steps*v[wf]/60:.0f} min" for wf in c))
    print()
