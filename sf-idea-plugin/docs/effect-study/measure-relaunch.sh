#!/usr/bin/env bash
# Restart with and without Gradle on a real mod (Windows), the way SulfurFrame's relaunch does it: no file of the
# project is touched.
#   gradle   ./gradlew <run task> straight into the world (--quickPlaySingleplayer); the game's command line is read
#            from the running process (argument files copied while it runs)
#   direct   that command line started again as it is, without Gradle, into the same world
# Each run ends when the game log says the player logged in; then the game is stopped.
# usage: measure-relaunch.sh <root> <run task> <run dir> <world> [runs]
set -uo pipefail
[ "${SF_SOUND:-}" = 1 ] || export ALSOFT_DRIVERS=null   # silent test games, options.txt untouched
ROOT="$1"; RUN="$2"; RUNDIR="$3"; WORLD="$4"; RUNS="${5:-3}"
cd "$ROOT"
ROOTNAME=$(basename "$(pwd)")
LOG="$RUNDIR/logs/latest.log"
WORK="${TEMP:-/tmp}/sf-measure-relaunch"; mkdir -p "$WORK"
CMDFILE="$WORK/cmdline.txt"
W() { cygpath -w "$1"; }
now() { date +%s.%N; }
el() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
# the dev client of this project only (java process, Fabric dev launch, path of this project)
GAME_FILTER="\$_.Name -like 'java*' -and \$_.CommandLine -match 'fabric.dli.config' -and \$_.CommandLine -match '$ROOTNAME'"
kill_game() { powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $GAME_FILTER } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1; }
# command line of the running game into CMDFILE (UTF-8), its @argument files copied next to it
capture() {
  powershell -NoProfile -Command "
    \$p = Get-CimInstance Win32_Process | Where-Object { $GAME_FILTER } | Select-Object -First 1
    if (-not \$p) { exit 1 }
    \$c = \$p.CommandLine; \$n = 0
    foreach (\$m in [regex]::Matches(\$c, '(?<=\s)@(\"[^\"]+\"|\S+)')) {
      \$f = \$m.Groups[1].Value.Trim('\"'); \$copy = '$(W "$WORK")\\args-' + (\$n++) + '.txt'
      Copy-Item -LiteralPath \$f -Destination \$copy -Force
      \$c = \$c.Replace('@' + \$m.Groups[1].Value, '@' + \$copy)
    }
    [IO.File]::WriteAllText('$(W "$CMDFILE")', \$c, [Text.UTF8Encoding]::new(\$false))"
}
# start CMDFILE as it is (exe + the rest of the line verbatim), in the run folder, inheriting this environment
start_direct() {
  powershell -NoProfile -Command "
    \$c = [IO.File]::ReadAllText('$(W "$CMDFILE")', [Text.Encoding]::UTF8)
    if (\$c.StartsWith('\"')) { \$e = \$c.IndexOf('\"', 1); \$exe = \$c.Substring(1, \$e - 1); \$rest = \$c.Substring(\$e + 1) }
    else { \$e = \$c.IndexOf(' '); \$exe = \$c.Substring(0, \$e); \$rest = \$c.Substring(\$e + 1) }
    \$psi = New-Object Diagnostics.ProcessStartInfo(\$exe, \$rest.Trim())
    \$psi.WorkingDirectory = '$(W "$RUNDIR")'; \$psi.UseShellExecute = \$false; \$psi.CreateNoWindow = \$true
    [void][Diagnostics.Process]::Start(\$psi)"
}
wait_world() {   # prints seconds since $1, or a failure note
  for _ in $(seq 1 900); do
    grep -q "logged in with entity id" "$LOG" 2>/dev/null && { el "$1" "$(now)"; return 0; }
    sleep 0.5
  done
  echo "not in the world within 450 s"; return 1
}

kill_game
echo "== gradle: $RUN -> logged in to '$WORLD' (command line captured)"
for i in $(seq 1 "$RUNS"); do
  rm -f "$LOG"
  t0=$(now)
  ./gradlew -q "$RUN" --args="--quickPlaySingleplayer \"$WORLD\"" >/dev/null 2>&1 &
  gp=$!
  s=$(wait_world "$t0"); echo "gradle run $i: $s s"
  capture || echo "  could not capture the command line"
  sleep 3; kill_game; wait "$gp" 2>/dev/null; sleep 2
done
[ -s "$CMDFILE" ] || { echo "no command line captured; stopping"; exit 1; }
echo "  command line: $(wc -c < "$CMDFILE") chars, argument files: $(ls "$WORK"/args-*.txt 2>/dev/null | wc -l)"

echo "== direct: same command line, no Gradle"
for i in $(seq 1 "$RUNS"); do
  rm -f "$LOG"
  t0=$(now)
  start_direct
  s=$(wait_world "$t0"); echo "direct run $i: $s s"
  grep -E "Loading [0-9]+ mods" "$LOG" 2>/dev/null | head -1 | sed 's/.*Loading/  mods: Loading/' | cut -c1-60
  sleep 3; kill_game; sleep 2
done
echo "done"
