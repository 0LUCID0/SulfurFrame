# Classify each commit by what a running dev client would need to pick it up.
#   none     no runtime effect (docs, CI, tests, tooling)
#   body     Java/Kotlin method bodies only           -> any HotSwap (stock JVM)
#   struct   members added/removed/signatures changed -> JBR enhanced redefinition (SulfurFrame), restart on a stock JVM
#   mixin    mixin class changed                       -> Mixin hot-swap agent (SulfurFrame, verified on Fabric)
#   assets   client resources                          -> resource reload (F3+T)
#   data     datapack resources                        -> /reload
#   restart  startup-only: registration/static init, mod metadata, access wideners, mixin configs, build files,
#            class hierarchy changes
# A commit takes every category its files hit; its "need" is the most expensive one.
import json, os, re, sys, glob, collections

RESTART_FILES = re.compile(r"(^|/)(fabric\.mod\.json|quilt\.mod\.json|mods\.toml|neoforge\.mods\.toml|accesstransformer\.cfg|"
                           r"[^/]*\.accesswidener|[^/]*\.classtweaker|[^/]*mixins?\.json)$")
# build files: version bumps and tooling. Not a dev-loop iteration on their own, and alongside code they are a one-off
TOOLING_FILES = re.compile(r"(^|/)(build\.gradle(\.kts)?|settings\.gradle(\.kts)?|gradle\.properties|libs\.versions\.toml|"
                           r"gradle-wrapper\.properties|[^/]*\.gradle(\.kts)?)$")
STARTUP_CTX = re.compile(r"(onInitialize\w*|\bregister\w*\s*\(|\binit(ialize)?\w*\s*\(|static\s*\{|<clinit>|\bbootstrap\w*\s*\()", re.I)
STARTUP_LINE = re.compile(r"(Registry\.register|DeferredRegister|\.register\s*\(|RegistryObject|DeferredHolder|DeferredBlock|DeferredItem|"
                          r"Registries\.|BuiltInRegistries\.|ResourceKey\.create)")
STATIC_INIT = re.compile(r"^\s*(public|private|protected)?\s*(static\s+final|final\s+static)\s+[\w<>\[\], ?.]+\s+\w+\s*=")
HIERARCHY = re.compile(r"^\s*(public|private|protected|abstract|final|sealed|non-sealed|static|\s)*\s*(class|interface|enum|record)\s+\w+.*\b(extends|implements|permits)\b")
DECL = re.compile(r"^\s*(@\w+(\(.*\))?\s*)*(public|private|protected|static|final|abstract|synchronized|native|default|transient|volatile|\s)+"
                  r"[\w<>\[\], ?.]+\s+\w+\s*(\(|=|;)")
SKIP_LINE = re.compile(r"^\s*(import\s|package\s|//|/\*|\*|\*/|$)")

def is_source(f): return f.endswith((".java", ".kt"))
def is_test(f): return bool(re.search(r"(^|/)(src/(test|testmod|gametest|testmodClient)\w*/|testmod/|test/)", f, re.I))

def classify_file(fi):
    f, st, p = fi["f"], fi["s"], fi["p"]
    if RESTART_FILES.search(f): return {"restart"}
    if TOOLING_FILES.search(f): return {"tooling"}
    if is_test(f): return set()
    if "/resources/" in f or f.startswith(("src/generated", "common/src/generated")) or "/generated/" in f:
        if "/assets/" in f: return {"assets"}
        if "/data/" in f: return {"data"}
        return set() if f.endswith((".md", ".txt")) else {"assets"}
    if not is_source(f) or "/src/" not in "/" + f and not f.startswith("src/"):
        return set()
    if "/mixin" in f.lower() or (p and "@Mixin" in p):
        return {"mixin"}
    if st == "added": return {"struct"}            # a new class: loaded fresh, nothing to redefine
    if st == "removed": return {"struct"}
    if not p:                                       # diff too large for the API: be conservative
        return {"restart"}
    cats, ctx = set(), ""
    for line in p.split("\n"):
        if line.startswith("@@"):
            ctx = line.split("@@", 2)[-1]
            continue
        if not line or line[0] not in "+-": continue
        body = line[1:]
        if SKIP_LINE.match(body): continue
        if HIERARCHY.match(body): cats.add("restart"); continue
        if STARTUP_LINE.search(body) or STARTUP_CTX.search(ctx): cats.add("restart"); continue
        if STATIC_INIT.match(body): cats.add("restart"); continue
        member_level = (body.startswith("    ") and not body.startswith("     ")) or (body.startswith("	") and not body.startswith("		"))
        if member_level and DECL.match(body) and not body.strip().startswith(("return", "throw")): cats.add("struct"); continue
        cats.add("body")
    return cats

ORDER = ["none", "tooling", "body", "struct", "mixin", "assets", "data", "restart"]
def need(cats):
    if not cats: return "none"
    if cats == {"tooling"}: return "tooling"
    return max(cats - {"tooling"}, key=ORDER.index)

def main(d):
    rows = {}
    for path in sorted(p for p in glob.glob(os.path.join(d, "*__*.json"))):
        repo = os.path.basename(path)[:-5].replace("__", "/")
        commits = json.load(open(path, encoding="utf-8"))
        cnt = collections.Counter(); any_cnt = collections.Counter(); fcnt = collections.Counter()
        for c in commits:
            cats = set().union(*[classify_file(fi) for fi in c["files"]]) if c["files"] else set()
            cnt[need(cats)] += 1
            for fi in c["files"]:
                fc = classify_file(fi)
                if fc and fc != {"tooling"}: fcnt[need(fc)] += 1
            for k in cats: any_cnt[k] += 1
        dates = sorted(c["date"][:10] for c in commits)
        rows[repo] = {"n": len(commits), "need": dict(cnt), "any": dict(any_cnt), "files": dict(fcnt), "from": dates[0] if dates else "", "to": dates[-1] if dates else ""}
    json.dump(rows, open(os.path.join(d, "mix.json"), "w"), indent=1)
    hdr = f"{'repo':38} {'n':>4} " + " ".join(f"{k:>7}" for k in ORDER) + "  period"
    print(hdr)
    for repo, r in rows.items():
        n = r["n"]
        print(f"{repo:38} {n:4} " + " ".join(f"{100*r['need'].get(k,0)/n:6.0f}%" for k in ORDER) + f"  {r['from']}..{r['to']}")
    print("per changed runtime file (each file edit ~ one edit-run step)")
    keys = ["body", "struct", "mixin", "assets", "data", "restart"]
    print(f"{'repo':38} {'files':>5} " + " ".join(f"{k:>7}" for k in keys))
    for repo, r in rows.items():
        t = sum(r["files"].values()) or 1
        print(f"{repo:38} {t:5} " + " ".join(f"{100*r['files'].get(k,0)/t:6.0f}%" for k in keys))

if __name__ == "__main__":
    main(sys.argv[1])
