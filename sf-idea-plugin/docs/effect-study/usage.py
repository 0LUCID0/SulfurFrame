# Real sessions, from SulfurFrame's own log (~/.sulfurframe/logs/sulfurframe-<date>.log, written by UsageLog):
# every edit-run step that actually happened, committed or not, with how long it took.
#   usage: python usage.py [log dir] [--baseline plain=44.4,hotswap=8.5,assets=8.8]
import re, sys, os, glob, statistics, collections
from datetime import datetime

args = [a for a in sys.argv[1:] if not a.startswith("--")]
LOGDIR = args[0] if args else os.path.join(os.path.expanduser("~"), ".sulfurframe", "logs")
BASE = {"plain": 44.4, "hotswap": 8.5, "assets": 8.8}
for a in sys.argv[1:]:
    if a.startswith("--baseline="):
        for kv in a.split("=", 1)[1].split(","):
            k, v = kv.split("="); BASE[k] = float(v)

LINE = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\] (.*)$")
SWAP = re.compile(r"^hotswap \((\w+), (\d+) class file\(s\), (\d+) ms\): (.*)$")
RES = re.compile(r"^resources: (\d+) file\(s\) copied, reload (\w+)")
steps = collections.defaultdict(list)     # project -> [(kind, seconds)]
pending = {}                               # project -> (kind, start time) waiting for the game window
for path in sorted(glob.glob(os.path.join(LOGDIR, "sulfurframe-*.log"))):
    for raw in open(path, encoding="utf-8", errors="replace"):
        m = LINE.match(raw.rstrip("\n"))
        if not m: continue
        ts, proj, msg = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f"), m.group(2), m.group(3)
        if proj.startswith("autotest:"): continue
        if (s := SWAP.match(msg)):
            needs = "restart needed" in s.group(4)
            steps[proj].append(("swap-restart-needed" if needs else "swap", int(s.group(3)) / 1000)); continue
        if (r := RES.match(msg)):
            steps[proj].append(("assets" if r.group(2).startswith("assets") else "data", None)); continue
        if msg.startswith("restart: saving and quitting"): pending[proj] = ("restart", ts); continue
        if msg.startswith("started ") and "for " in msg and proj not in pending: pending[proj] = ("runclient", ts); continue
        if msg.startswith("game window appeared") and proj in pending:
            kind, t0 = pending.pop(proj)
            steps[proj].append((kind, (ts - t0).total_seconds())); continue
        if msg.startswith("restart needed:"):
            steps[proj].append(("restart-offered", None))

if not steps:
    print(f"no sessions in {LOGDIR} yet"); sys.exit(0)
for proj, st in steps.items():
    c = collections.Counter(k for k, _ in st)
    print(f"== {proj}: " + ", ".join(f"{k} {n}" for k, n in c.most_common()))
    for kind in ("swap", "restart", "runclient"):
        xs = [s for k, s in st if k == kind and s is not None]
        if xs: print(f"   {kind}: median {statistics.median(xs):.1f} s, range {min(xs):.1f}-{max(xs):.1f} s, n={len(xs)}")
    live = c["swap"] + c["assets"] + c["data"]
    restarts = c["restart"] + c["runclient"]
    if live + restarts:
        sf = sum(s for k, s in st if k in ("swap", "restart", "runclient") and s is not None) + 2.5 * (c["assets"] + c["data"])
        plain = BASE["plain"] * (live + restarts)
        print(f"   live {live} / restarts {restarts} ({100 * live / (live + restarts):.0f}% live)")
        print(f"   waited ~{sf / 60:.1f} min; every step as a plain restart would have been ~{plain / 60:.1f} min")
