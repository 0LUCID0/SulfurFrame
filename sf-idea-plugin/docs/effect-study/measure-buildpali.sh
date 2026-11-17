#!/usr/bin/env bash
# Baseline timings on a real multi-project mod (BuildPali: 9 Gradle projects, Fabric 1.21.1), without touching its
# files: the edits go to two temporary files that are removed (and rebuilt away) at the end.
#   plain     ./gradlew <run task> straight into the world (--quickPlaySingleplayer)
#   hotswap   ./gradlew <classes task> after a method-body edit of a temporary leaf class (the best case for Gradle:
#             nothing depends on it)
#   assets    ./gradlew <processResources task> after editing a temporary resource file
# usage: measure-buildpali.sh <root> <run task> <classes task> <resources task> <java dir> <resources dir> <run dir> <world> [runs]
set -uo pipefail
[ "${SF_SOUND:-}" = 1 ] || export ALSOFT_DRIVERS=null   # silent test games, options.txt untouched
ROOT="$1"; RUN="$2"; CLASSES="$3"; RES="$4"; JDIR="$5"; RDIR="$6"; RUNDIR="$7"; WORLD="$8"; RUNS="${9:-3}"
cd "$ROOT"
LOG="$RUNDIR/logs/latest.log"
PROBE="$JDIR/SfTimingProbe.java"; PKG=$(echo "$JDIR" | sed 's|.*src/main/java/||; s|/|.|g')
TMPRES="$RDIR/sf-timing-probe.txt"
[ -e "$PROBE" ] || [ -e "$TMPRES" ] && { echo "temporary files already exist; stopping"; exit 1; }
now() { date +%s.%N; }
el() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
kill_game() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'java*' -and \$_.CommandLine -match 'fabric.dli.config' -and \$_.CommandLine -match 'buildpali' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
}
cleanup() { rm -f "$PROBE" "$TMPRES"; ./gradlew -q "$CLASSES" "$RES" >/dev/null 2>&1; }
trap cleanup EXIT

echo "== gradle configuration: help"
t0=$(now); ./gradlew -q help >/dev/null 2>&1; t1=$(now); echo "help: $(el "$t0" "$t1") s"

echo "== plain: $RUN -> logged in to '$WORLD'"
for i in $(seq 1 "$RUNS"); do
  rm -f "$LOG"
  t0=$(now)
  ./gradlew -q "$RUN" --args="--quickPlaySingleplayer \"$WORLD\"" >/dev/null 2>&1 &
  gp=$!; ok=""
  for _ in $(seq 1 900); do
    grep -q "logged in with entity id" "$LOG" 2>/dev/null && { ok=1; break; }
    sleep 0.5
  done
  t1=$(now)
  [ -n "$ok" ] && echo "plain run $i: $(el "$t0" "$t1") s" || echo "plain run $i: not in the world within 450 s"
  grep -E "Loading [0-9]+ mods" "$LOG" 2>/dev/null | head -1 | sed 's/.*Loading/  mods: Loading/' | cut -c1-60
  sleep 3; kill_game; wait "$gp" 2>/dev/null; sleep 2
done

echo "== hotswap (stock): $CLASSES after a body edit"
printf 'package %s;\n\nfinal class SfTimingProbe {\n    static String message() { return "b0"; }\n}\n' "$PKG" > "$PROBE"
./gradlew -q "$CLASSES" >/dev/null 2>&1
for i in $(seq 1 "$RUNS"); do
  sed -i "s/return \"b[0-9]*\";/return \"b$i\";/" "$PROBE"
  t0=$(now); ./gradlew -q "$CLASSES" >/dev/null 2>&1; t1=$(now)
  echo "classes run $i: $(el "$t0" "$t1") s"
done

echo "== assets (stock): $RES after a resource edit"
echo "r0" > "$TMPRES"; ./gradlew -q "$RES" >/dev/null 2>&1
for i in $(seq 1 "$RUNS"); do
  echo "r$i" > "$TMPRES"
  t0=$(now); ./gradlew -q "$RES" >/dev/null 2>&1; t1=$(now)
  echo "processResources run $i: $(el "$t0" "$t1") s"
done
echo "done (temporary files removed on exit)"
