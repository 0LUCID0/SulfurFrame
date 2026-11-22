#!/usr/bin/env bash
# Before a release: everything a new user hits, not only a warm developer setup, in one go (Windows + WSL).
#   ./sf-idea-plugin/release-check.sh [--quick] [--worlds] [--download] [--no-linux]
#     --quick      skip the Plugin Verifier (about 6 minutes)
#     --worlds     also the shared-worlds flow (it links the test copy into your real ~/.sulfurframe shared folder)
#     --download   also the real JetBrains Runtime download into a temporary folder (about 280 MB)
#     --no-linux   skip the WSL Xvfb run
# Runs, one after another (each sandbox run takes 5-10 minutes; the game is muted):
#   gradle   buildPlugin, check (fixture self-tests), verifyPlugin
#   A        new user: fresh copy of demo/showcase-mod, no run/options.txt, no JetBrains Runtime (-Dsulfurframe.noJbr)
#   B        regular: fresh copy, JetBrains Runtime used (structural hotswap, restarts with and without Gradle)
#   C        Linux: xvfb-test.sh runclient in WSL (Xvfb + openbox)
# Writes %TEMP%/sf-release-check/summary.txt (and one result file per run); exit 1 if anything failed.
set -uo pipefail
cd "$(dirname "$0")/.."
REPO="$(pwd)"
QUICK=0; WORLDS=0; DOWNLOAD=0; LINUX=1
for a in "$@"; do case "$a" in --quick) QUICK=1;; --worlds) WORLDS=1;; --download) DOWNLOAD=1;; --no-linux) LINUX=0;; *) echo "unknown option $a"; exit 2;; esac; done
OUT="${TEMP:-/tmp}/sf-release-check"; rm -rf "$OUT"; mkdir -p "$OUT"
SUM="$OUT/summary.txt"; : > "$SUM"
say() { echo "$*" | tee -a "$SUM"; }
FAILED=0

# this repository's gradlew needs Java 17+ (Java 8 cannot even open the wrapper under a non-ASCII path)
newest_jdk25() {
  for d in "$HOME/.jdks/temurin-25."* "/c/Program Files/Eclipse Adoptium/jdk-25."*; do
    [ -x "$d/bin/javac" ] || [ -x "$d/bin/javac.exe" ] || continue
    printf '%s\t%s\n' "$(basename "$d" | grep -oE '[0-9]+(\.[0-9]+)*' | head -1)" "$d"
  done | sort -V -k1,1 | tail -1 | cut -f2
}
J25="$(newest_jdk25)"; [ -n "$J25" ] && export JAVA_HOME="$(cygpath -w "$J25" 2>/dev/null || echo "$J25")"

say "== release check $(date '+%Y-%m-%d %H:%M'), $(git rev-parse --short HEAD)$(git diff --quiet || echo ' (uncommitted changes)')"

# ---- gradle
tasks="buildPlugin check"; [ $QUICK = 0 ] && tasks="$tasks verifyPlugin"
rm -rf poc/agent/build sf-idea-plugin/build/classes sf-idea-plugin/build/resources sf-idea-plugin/build/tmp 2>/dev/null   # stale locks
if ./gradlew $tasks --no-build-cache --console=plain -q > "$OUT/gradle.log" 2>&1; then
  say "gradle: ok ($(grep -c '^PASS' "$OUT/gradle.log") fixture checks$( [ $QUICK = 0 ] && grep -q 'Compatible' "$OUT/gradle.log" && echo ', verifier: '"$(grep -m1 -o 'Compatible[^\r]*' "$OUT/gradle.log")"))"
  [ $QUICK = 0 ] && ! grep -q ": Compatible$" "$OUT/gradle.log" && grep -q "Compatible\." "$OUT/gradle.log" && { say "  verifier reports API problems (see $OUT/gradle.log)"; FAILED=1; }
else
  say "gradle: FAILED (see $OUT/gradle.log)"; grep -A3 "What went wrong" "$OUT/gradle.log" | head -8 | tee -a "$SUM"; FAILED=1
fi

# ---- sandbox runs (Windows)
(cd poc/agent && bash build.sh >/dev/null 2>&1) && bash sf-idea-plugin/build.sh >/dev/null 2>&1 || { say "quick build failed"; exit 1; }
COPY="${TEMP:-/tmp}/sf-showcase-test"
PROBE_REL="src/main/java/dev/sulfurframe/showcase/SfHotswapProbe.java"
RESULT="${TEMP:-/tmp}/sulfurframe-autotest.txt"

stop_sandbox() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { (\$_.Name -like 'java*' -or \$_.Name -eq 'idea64.exe') -and \$_.CommandLine -match 'sf-idea-sandbox|sf-showcase-test' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
  sleep 3
}

run_sandbox() {   # name, extra -D options...
  local name="$1"; shift
  bash demo/tools/test-copy.sh "$COPY" >/dev/null || { say "$name: could not make the test copy"; FAILED=1; return; }
  [ "$name" = "A-new-user" ] && rm -f "$COPY/run/options.txt"   # what a new user's run folder looks like
  rm -f "$RESULT"
  stop_sandbox
  bash sf-idea-plugin/sandbox.sh "$COPY" -Dsulfurframe.autotest.runclient=true \
      "-Dsulfurframe.autotest.hotswap=$(cygpath -w "$COPY/$PROBE_REL")" -Dsulfurframe.autotest.world=SFWorld "$@" \
      > "$OUT/$name-ide.log" 2>&1 &
  local start=$(date +%s)
  until grep -q '^failures:' "$RESULT" 2>/dev/null; do
    sleep 10
    [ $(( $(date +%s) - start )) -gt 1800 ] && { say "$name: no result after 30 minutes"; FAILED=1; break; }
  done
  stop_sandbox
  cp "$RESULT" "$OUT/$name.txt" 2>/dev/null
  local pass fail
  pass=$(grep -c '^PASS' "$OUT/$name.txt" 2>/dev/null); fail=$(grep -c '^FAIL' "$OUT/$name.txt" 2>/dev/null)
  say "$name: ${pass:-0} passed, ${fail:-0} failed"
  grep '^FAIL' "$OUT/$name.txt" 2>/dev/null | sed 's/^/  /' | tee -a "$SUM"
  [ "${fail:-1}" = 0 ] && grep -q '^failures: 0' "$OUT/$name.txt" || FAILED=1
}

run_sandbox A-new-user -Dsulfurframe.noJbr=true
extraB=(); [ $WORLDS = 1 ] && extraB+=(-Dsulfurframe.autotest.worlds=true); [ $DOWNLOAD = 1 ] && extraB+=(-Dsulfurframe.autotest.jbrdownload=true)
run_sandbox B-regular "${extraB[@]}"

# ---- Linux (WSL, Xvfb + openbox): the plugin's own Run client with every scenario, on a fresh copy of the demo mod
if [ $LINUX = 1 ]; then
  if MSYS_NO_PATHCONV=1 wsl bash -lc 'test -d ~/idea' 2>/dev/null; then
    WREPO=$(MSYS_NO_PATHCONV=1 wsl wslpath -a "$(cygpath -w "$REPO" | sed 's/\\/\//g')" | tr -d '\r')
    WCOPY=/home/$(MSYS_NO_PATHCONV=1 wsl whoami | tr -d '\r')/sf-showcase-test
    # Gradle daemons go first: one started before a JDK update cannot start the game ("Failed to exec spawn helper").
    MSYS_NO_PATHCONV=1 wsl bash -lc "pkill -f GradleDaemon; bash $WREPO/demo/tools/test-copy.sh $WCOPY && chmod +x $WCOPY/gradlew" >/dev/null 2>&1
    MSYS_NO_PATHCONV=1 wsl env SF_PROJ="$WCOPY" bash "$WREPO/sf-idea-plugin/xvfb-test.sh" runclient "$WCOPY/src/main/java/dev/sulfurframe/showcase/SfHotswapProbe.java" > "$OUT/C-linux.txt" 2>&1
    # the game may have been restarted without Gradle: match the test project's path too, not only Loom's dev launch
    MSYS_NO_PATHCONV=1 wsl bash -lc 'pkill -f "$HOME/idea/bin/idea"; pkill -f "fabric.dli.config|$HOME/sf-showcase-test"; sleep 2; pkill -9 -f "fabric.dli.config|$HOME/sf-showcase-test"' >/dev/null 2>&1
    pass=$(grep -c '^PASS' "$OUT/C-linux.txt"); fail=$(grep -c '^FAIL' "$OUT/C-linux.txt")
    say "C-linux: $pass passed, $fail failed"
    grep '^FAIL' "$OUT/C-linux.txt" | sed 's/^/  /' | tee -a "$SUM"
    grep -q '^failures: 0' "$OUT/C-linux.txt" || FAILED=1
  else
    say "C-linux: skipped (no ~/idea in WSL, see xvfb-test.sh)"
  fi
fi

say "== $( [ $FAILED = 0 ] && echo 'ALL PASSED' || echo 'FAILURES: do not release yet' ) (details in $OUT)"
exit $FAILED
