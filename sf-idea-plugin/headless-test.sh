#!/usr/bin/env bash
# SulfurFrame without IntelliJ: `sf run` on a fresh copy of the demo mod, then the sf commands a coding agent uses
# (status, cmd, shot, an edit + sf wait, a new method, restart, quit). Prints PASS/FAIL lines and "failures: N".
#   sf-idea-plugin/headless-test.sh [copy dir]
# Needs the built plugin (build.sh) and an IntelliJ installation for its Gson and ASM jars (IDEA_HOME or the default
# place), and Java 25 (SF_JAVA or the newest JDK 25 found).
set -u
here="$(cd "$(dirname "$0")" && pwd)"
repo="$here/.."
copy="${1:-${TEMP:-/tmp}/sf-headless-test}"
fails=0
check() { if [ "$2" = 0 ]; then echo "PASS $1  [$3]"; else echo "FAIL $1  [$3]"; fails=$((fails + 1)); fi; }
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
sep=":"; command -v cygpath >/dev/null 2>&1 && sep=";"

idea="${IDEA_HOME:-${LOCALAPPDATA:-}/Programs/IntelliJ IDEA Community Edition}"
[ -d "$idea/lib" ] || idea="${IDEA_HOME:-$HOME/idea}"
jar="$here/build/SulfurFrame/lib/sulfurframe.jar"
[ -f "$jar" ] || { echo "FAIL no built plugin ($jar): run build.sh"; echo "failures: 1"; exit 1; }
export SF_HOST_JAR="$(win "$jar")$sep$(win "$idea/lib/intellij.libraries.gson.jar")$sep$(win "$idea/lib/intellij.libraries.asm.jar")"
if [ -z "${SF_JAVA:-}" ]; then
  for j in "/c/Program Files/Eclipse Adoptium"/jdk-25*/bin/java.exe "$HOME"/.jdks/*25*/bin/java /usr/lib/jvm/*25*/bin/java; do [ -x "$j" ] && SF_JAVA="$j"; done
fi
export SF_JAVA="${SF_JAVA:-java}"
export SF_MUTE=1
sfbin="$here/src/main/resources/bridge/sf"   # the script as shipped (the host also installs it into ~/.sulfurframe/bin)

# sf relay, with a stand-in agent that only keeps the prompt it was given: a report without an IDE, handed on once
rh="${TEMP:-/tmp}/sf-relay-test"; rm -rf "$rh"; mkdir -p "$rh/repo"
if command -v cygpath >/dev/null 2>&1; then agent='$input | Out-File -Encoding utf8 got-prompt.txt'; else agent='cat > got-prompt.txt'; fi
printf 'repo=%s\ncommand=%s\n' "$(win "$rh/repo")" "$agent" > "$rh/relay.conf"
(cd "$rh/repo" && SULFURFRAME_HOME="$(win "$rh")" sh "$sfbin" feedback "relay self-test: a report to hand on" >/dev/null)
sleep 3
out=$(SULFURFRAME_HOME="$(win "$rh")" sh "$sfbin" relay --once 2>&1); rc=$?
got=$(cat "$rh/repo/got-prompt.txt" 2>/dev/null | tr -d '\0')
check "sf feedback (no IDE) + sf relay --once -> the agent gets the report, the batch is filed as done" \
  $([ $rc = 0 ] && echo "$got" | grep -q "relay self-test: a report to hand on" && echo "$got" | grep -q "do not commit" && ls "$rh"/feedback/done/*/result.txt >/dev/null 2>&1 && [ -z "$(ls "$rh/feedback/inbox")" ] && echo 0 || echo 1) "$(echo "$out" | tail -1)"

bash "$repo/demo/tools/test-copy.sh" "$copy" >/dev/null || { echo "FAIL could not make the test copy"; echo "failures: 1"; exit 1; }
cd "$copy"
# a class that is in the build output from the start but used only once run/sf-rescue.flag exists (class rescue check)
pkg=src/main/java/dev/sulfurframe/showcase
printf 'package dev.sulfurframe.showcase;\n\nfinal class SfRescueProbe {\n    static String text() { return "rescued"; }\n}\n' > "$pkg/SfRescueProbe.java"
sed -i 's/static String tag() { return SfProbeConst.TAG; }/static String tag() { return java.nio.file.Files.exists(java.nio.file.Path.of("sf-rescue.flag")) ? SfRescueProbe.text() : SfProbeConst.TAG; }/' "$pkg/SfHotswapProbe.java"
grep -q "sf-rescue.flag" "$pkg/SfHotswapProbe.java" || echo "FAIL the probe changed shape (tag())"
log="$copy/../sf-headless-host.log"
sh "$sfbin" run --world SFHeadless > "$log" 2>&1 &
host=$!
up=1; for i in $(seq 1 120); do grep -q "fast compile ready\|javac warmed up" "$log" && { up=0; break; }; grep -q "the game ended" "$log" && break; sleep 5; done
check "sf run -> game up, world created, ready to swap" $up "$(grep -c 'world .SFHeadless. created' "$log") world created, $(grep -m1 'game window' "$log" | cut -c1-90)"
sleep 10   # the world finishes loading
out=$(sh "$sfbin" status); check "sf status -> running in the world" $([ $? = 0 ] && echo "$out" | grep -q "world 'SFHeadless'" && echo 0 || echo 1) "$(echo "$out" | sed -n 2p)"
# class rescue: the class file goes away (as when a Gradle build clears the output), the game needs it, it comes back
cls=build/classes/java/main/dev/sulfurframe/showcase/SfRescueProbe.class
if [ -f "$cls" ]; then
  mv "$cls" "$cls.away"; touch run/sf-rescue.flag; sleep 8; mv "$cls.away" "$cls"; sleep 8
  check "class file missing while the game needs it -> waited for, no crash" \
    $(grep -q "class rescue: dev.sulfurframe.showcase.SfRescueProbe was missing" run/logs/latest.log && grep -q "SF-PROBE\] v1 rescued" run/logs/latest.log && echo 0 || echo 1) \
    "$(grep -m1 'class rescue:' run/logs/latest.log | cut -c1-140)"
  rm -f run/sf-rescue.flag
else
  check "class file missing while the game needs it -> waited for, no crash" 1 "no $cls"
fi
out=$(sh "$sfbin" cmd time set noon); check "sf cmd -> what the server said" $([ $? = 0 ] && echo "$out" | grep -q "Set the time" && echo 0 || echo 1) "$out"
out=$(sh "$sfbin" shot); png=$(echo "$out" | tail -1 | tr -d '\r')
check "sf shot -> a PNG" $([ -s "$(echo "$png" | sed 's#\\#/#g')" ] && echo 0 || echo 1) "$png"
probe=src/main/java/dev/sulfurframe/showcase/SfHotswapProbe.java
cp "$probe" "$probe.orig"
sed -i 's/return "v1";/return "vHL";/' "$probe"
out=$(sh "$sfbin" wait); rc=$?; sleep 6
check "edit on disk + sf wait -> swapped" $([ $rc = 0 ] && echo "$out" | grep -q "redefined 1" && grep -q "SF-PROBE\] vHL" run/logs/latest.log && echo 0 || echo 1) "$(echo "$out" | head -2 | tr '\n' ' ')"
sed -i 's/return "vHL";/return "vHL2 " + extra();/; s/    private SfHotswapProbe() {}/    static String extra() { return "added"; }\n\n    private SfHotswapProbe() {}/' "$probe"
out=$(sh "$sfbin" wait); rc=$?; sleep 6
check "a new method (JBR) -> swapped and running" $([ $rc = 0 ] && grep -q "SF-PROBE\] vHL2 added" run/logs/latest.log && echo 0 || echo 1) "$(echo "$out" | grep hotswap | tail -1 | cut -c1-100)"
mv "$probe.orig" "$probe"; sh "$sfbin" wait >/dev/null
out=$(sh "$sfbin" restart); rc=$?
check "sf restart -> back in the same world, no Gradle" $([ $rc = 0 ] && echo "$out" | grep -q "world 'SFHeadless'" && grep -q "started the game without Gradle" "$log" && echo 0 || echo 1) "$out"
out=$(sh "$sfbin" quit); rc=$?
gone=1; for i in $(seq 1 30); do kill -0 $host 2>/dev/null || { gone=0; break; }; sleep 1; done
check "sf quit -> the game saves and quits, sf run ends" $([ $rc = 0 ] && [ $gone = 0 ] && echo 0 || echo 1) "$out"
[ $gone = 0 ] || kill $host 2>/dev/null
echo "failures: $fails"
[ $fails = 0 ]
