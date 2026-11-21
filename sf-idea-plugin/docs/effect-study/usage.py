# Real sessions, from SulfurFrame's own log (~/.sulfurframe/logs/sulfurframe-<date>.log, written by UsageLog):
# every edit-run step that actually happened, committed or not, with how long it took.
#   usage: python usage.py [log dir] [--baseline=plain=44.4,hotswap=8.5,assets=8.8] [--include-temp] [--exclude=proj1,proj2]
#
# What counts:
#   swap        a hotswap ("hotswap (<compiler>, N class file(s), M ms): ..."); "restart needed" in it = swap-restart-needed
#   assets/data a resource reload
#   runclient   Run client through Gradle until its game window ("game window appeared (pid, S s after start)")
#   restart     Restart into the same world, through Gradle or without it (the relaunch), until the window
# Skipped: self-test runs (project "autotest:..."), and by default projects that only ever ran from a temporary copy
# (a path under Temp or /tmp: demo recordings, test copies), which are listed separately. A start without a window
# (failed build, stopped) is dropped instead of being matched to a later window.
import re, sys, os, glob, statistics, collections
from datetime import datetime

args = [a for a in sys.argv[1:] if not a.startswith("--")]
LOGDIR = args[0] if args else os.path.join(os.path.expanduser("~"), ".sulfurframe", "logs")
INCLUDE_TEMP = "--include-temp" in sys.argv
EXCLUDE = {x for a in sys.argv[1:] if a.startswith("--exclude=") for x in a.split("=", 1)[1].split(",") if x}
BASE = {"plain": 44.4, "hotswap": 8.5, "assets": 8.8}
for a in sys.argv[1:]:
    if a.startswith("--baseline="):
        for kv in a.split("=", 1)[1].split(","):
            k, v = kv.split("="); BASE[k] = float(v)

LINE = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\] (.*)$")
SWAP = re.compile(r"^hotswap \(([^,]+), (\d+) class file\(s\), (\d+) ms\): (.*)$")
RES = re.compile(r"^resources: (\d+) file\(s\) copied, reload (\w+)")
WINDOW = re.compile(r"^game window appeared \(pid \d+(?:, ([\d.]+) s after start)?\)")
TEMP = re.compile(r"[\\/](Temp|tmp)[\\/]", re.I)

steps = collections.defaultdict(list)     # project -> [(kind, seconds)]
pending = {}                               # project -> (kind, start time) waiting for its game window
paths = collections.defaultdict(set)       # project -> was it started from a temporary copy?
days = collections.defaultdict(set)
for path in sorted(glob.glob(os.path.join(LOGDIR, "sulfurframe-*.log"))):
    for raw in open(path, encoding="utf-8", errors="replace"):
        m = LINE.match(raw.rstrip("\n"))
        if not m: continue
        ts, proj, msg = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f"), m.group(2), m.group(3)
        if proj.startswith("autotest:"): continue
        days[proj].add(ts.date())
        if (s := SWAP.match(msg)):
            needs = "restart needed" in s.group(4)
            steps[proj].append(("swap-restart-needed" if needs else "swap", int(s.group(3)) / 1000)); continue
        if (r := RES.match(msg)):
            steps[proj].append(("assets" if r.group(2).startswith("assets") else "data", None)); continue
        if msg.startswith("restart: saving and quitting") or msg.startswith("stop: saving and quitting"):
            pending[proj] = ("restart" if msg.startswith("restart") else "stop", ts); continue
        if msg.startswith("started "):
            if " gradlew" in msg: paths[proj].add(bool(TEMP.search(msg)))
            via = "nogradle" if msg.startswith("started the game without Gradle") else "gradle"
            kind = pending.get(proj, (None,))[0]
            # Restart -> its new start; a start after a stop (Rerun) or out of nowhere is a Run client
            pending[proj] = (("restart-" if kind == "restart" else "runclient-") + via, ts); continue
        if msg.startswith("the run exited") or msg.startswith("no game window"):
            pending.pop(proj, None); continue        # no window: nothing to time
        if (w := WINDOW.match(msg)) and proj in pending:
            kind, t0 = pending.pop(proj)
            if kind == "stop": continue
            secs = float(w.group(1)) if w.group(1) else (ts - t0).total_seconds()
            if secs <= 600: steps[proj].append((kind, secs))   # longer: a start whose window never came
            continue
        if msg.startswith("restart needed:"):
            steps[proj].append(("restart-offered", None))

temp_only = {p for p in steps if paths[p] and all(paths[p])}
shown = [p for p in steps if (INCLUDE_TEMP or p not in temp_only) and p not in EXCLUDE]
if not shown:
    print(f"no real sessions in {LOGDIR} yet" + (f" (temporary copies only: {', '.join(sorted(temp_only))})" if temp_only else ""))
    sys.exit(0)
for proj in shown:
    st = steps[proj]
    c = collections.Counter(k for k, _ in st)
    tag = " [temporary copy]" if proj in temp_only else ""
    print(f"== {proj}{tag}: {len(days[proj])} day(s): " + ", ".join(f"{k} {n}" for k, n in c.most_common()))
    for kind in ("swap", "runclient-gradle", "restart-gradle", "restart-nogradle", "runclient-nogradle"):
        xs = [s for k, s in st if k == kind and s is not None]
        if xs: print(f"   {kind}: median {statistics.median(xs):.1f} s, range {min(xs):.1f}-{max(xs):.1f} s, n={len(xs)}")
    live = c["swap"] + c["assets"] + c["data"]
    restarts = sum(n for k, n in c.items() if k.startswith(("restart-gradle", "restart-nogradle", "runclient-")))
    if live + restarts:
        waited = sum(s for k, s in st if s is not None and not k.startswith("swap-restart")) + 2.5 * (c["assets"] + c["data"])
        plain = BASE["plain"] * (live + restarts)
        print(f"   live {live} / restarts {restarts} ({100 * live / (live + restarts):.0f}% live)")
        print(f"   waited ~{waited / 60:.1f} min; every step as a plain restart would have been ~{plain / 60:.1f} min")
if temp_only and not INCLUDE_TEMP:
    print(f"(skipped, ran only from temporary copies: {', '.join(sorted(temp_only))}; --include-temp shows them)")
