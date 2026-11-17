# Fetches recent commits of large mod repos for the expected-effect study (sf-idea-plugin/README.md).
#   usage: GH_TOKEN=$(gh auth token) python fetch.py <out dir> 120 FabricMC/fabric CaffeineMC/sodium ...
# Raw commit JSON (third-party diffs) is not kept in this repo; mix.json holds the counts.
# Fetch the last N non-merge commits (with per-file patches) of each repo into one JSON per repo.
import json, os, sys, urllib.request, time
TOKEN = os.environ["GH_TOKEN"]; OUT = sys.argv[1]; N = int(sys.argv[2])
REPOS = sys.argv[3:]
def get(url, tries=5):
    for t in range(tries):
        try: return _get(url)
        except Exception as e:
            if t == tries - 1: raise
            time.sleep(3 * (t + 1))
def _get(url):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json", "User-Agent": "commit-mix-study"})
    with urllib.request.urlopen(req, timeout=60) as r: return json.load(r)
for spec in REPOS:
    repo, _, branch = spec.partition("@")
    path = os.path.join(OUT, repo.replace("/", "__") + ".json")
    if os.path.exists(path): print("skip", repo); continue
    shas, page = [], 1
    while len(shas) < N and page < 10:
        lst = get(f"https://api.github.com/repos/{repo}/commits?per_page=100&page={page}" + (f"&sha={branch}" if branch else ""))
        if not lst: break
        shas += [c["sha"] for c in lst if len(c["parents"]) == 1]
        page += 1
    out = []
    for sha in shas[:N]:
        c = get(f"https://api.github.com/repos/{repo}/commits/{sha}")
        out.append({"sha": sha, "date": c["commit"]["author"]["date"], "msg": c["commit"]["message"].split("\n")[0][:120],
                    "files": [{"f": f["filename"], "s": f["status"], "a": f["additions"], "d": f["deletions"], "p": f.get("patch")} for f in c.get("files", [])]})
    json.dump(out, open(path, "w", encoding="utf-8"))
    print(repo, len(out), out[-1]["date"][:10] if out else "", "..", out[0]["date"][:10] if out else "")
