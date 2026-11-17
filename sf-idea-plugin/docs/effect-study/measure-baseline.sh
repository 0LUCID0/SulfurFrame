#!/usr/bin/env bash
# Baseline timings for the expected-effect study, measured on the same machine and test project as SulfurFrame's own
# numbers (README "핫스왑"): what the same edit costs WITHOUT SulfurFrame.
#   plain     ./gradlew runClient straight into the world (--quickPlaySingleplayer, so no menu clicking is counted)
#   hotswap   ./gradlew classes after a method-body edit (IntelliJ delegates Build to Gradle by default; JDWP
#             redefinition itself adds well under a second)
#   assets    ./gradlew processResources after a lang edit (the F3+T reload after it is the same with or without
#             SulfurFrame)
# usage: measure-baseline.sh <mod project> <world> [runs]   -> prints one line per run, and a summary
set -uo pipefail
# test games are silent: OpenAL Soft null output, options.txt untouched (SF_SOUND=1 to hear them)
[ "${SF_SOUND:-}" = 1 ] || export ALSOFT_DRIVERS=null
PROJ="${1:?mod project}"; WORLD="${2:?world name}"; RUNS="${3:-3}"
cd "$PROJ"
LOG=run/logs/latest.log
PROBE=$(grep -rl 'class SfHotswapProbe' src/main/java | head -1)
LANG=$(find src/main/resources/assets -path '*lang*' -name '*.json' | head -1)
now() { date +%s.%N; }
el() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
kill_game() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'java*' -and \$_.CommandLine -match 'sf-lucid-copy' -and \$_.CommandLine -match 'net.fabricmc|KnotClient|devlaunchinjector' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
}

echo "== plain: runClient -> joined '$WORLD'"
for i in $(seq 1 "$RUNS"); do
  rm -f "$LOG"
  t0=$(now)
  ./gradlew runClient -q --args="--quickPlaySingleplayer $WORLD" >/dev/null 2>&1 &
  gp=$!
  ok=""
  for _ in $(seq 1 600); do
    if grep -q "logged in with entity id" "$LOG" 2>/dev/null; then ok=1; break; fi
    sleep 0.5
  done
  t1=$(now)
  [ -n "$ok" ] && echo "plain run $i: $(el "$t0" "$t1") s" || echo "plain run $i: no "logged in" within 300 s"
  sleep 3; kill_game; wait "$gp" 2>/dev/null; sleep 2
done

echo "== hotswap (stock): gradlew classes after a body edit"
cp "$PROBE" "$PROBE.bak"
for i in $(seq 1 "$RUNS"); do
  sed -i "s/return \"v[0-9a-z]*\";/return \"b$i\";/" "$PROBE"
  t0=$(now); ./gradlew classes -q >/dev/null 2>&1; t1=$(now)
  echo "classes run $i: $(el "$t0" "$t1") s"
done
mv "$PROBE.bak" "$PROBE"; ./gradlew classes -q >/dev/null 2>&1

echo "== assets (stock): gradlew processResources after a lang edit"
cp "$LANG" "$LANG.bak"
for i in $(seq 1 "$RUNS"); do
  sed -i "0,/\": \"/s//\": \"$i/" "$LANG"
  t0=$(now); ./gradlew processResources -q >/dev/null 2>&1; t1=$(now)
  echo "processResources run $i: $(el "$t0" "$t1") s"
done
mv "$LANG.bak" "$LANG"; ./gradlew processResources -q >/dev/null 2>&1
echo "done"
