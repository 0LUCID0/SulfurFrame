#!/usr/bin/env bash
# Build the SulfurFrame IntelliJ plugin against an installed IntelliJ IDEA (2026.2+, runs on JBR 25: FFM available).
# No Gradle and no IDE download: compiles against the IDE's own lib/*.jar.
#   IDEA_HOME   default: %LOCALAPPDATA%/Programs/IntelliJ IDEA Community Edition
# Output: sf-idea-plugin/build/SulfurFrame/lib/sulfurframe.jar (a plugin directory you can drop into a plugins folder)
set -euo pipefail
cd "$(dirname "$0")"
IDEA_HOME="${IDEA_HOME:-$LOCALAPPDATA/Programs/IntelliJ IDEA Community Edition}"
# any JDK 25 with javac/jar (JBR has none): the newest installed one by version number (an update changes the folder name)
newest_jdk25() {
  for d in "$HOME/.jdks/temurin-25."* "/c/Program Files/Eclipse Adoptium/jdk-25."*; do
    [ -x "$d/bin/javac" ] || [ -x "$d/bin/javac.exe" ] || continue
    printf '%s\t%s\n' "$(basename "$d" | grep -oE '[0-9]+(\.[0-9]+)*' | head -1)" "$d/bin"
  done | sort -V -k1,1 | tail -1 | cut -f2
}
JDK="${JDK:-$(newest_jdk25)}"
[ -n "$JDK" ] || { echo "no JDK 25 found; set JDK=<jdk>/bin" >&2; exit 1; }
AGENT="../poc/agent/sf-agent.jar"
[ -f "$AGENT" ] || (cd ../poc/agent && bash build.sh)
rm -rf build && mkdir -p build/classes build/SulfurFrame/lib
"$JDK/javac" -Xlint:-restricted -nowarn --release 25 -cp "$(cygpath -w "$IDEA_HOME/lib")/*;$(cygpath -w "$IDEA_HOME/plugins/java/lib")/*;$(cygpath -w "$IDEA_HOME/plugins/java/lib/modules")/*" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/* build/classes/
cp "$AGENT" build/classes/sf-agent.jar
"$JDK/jar" --create --file build/SulfurFrame/lib/sulfurframe.jar -C build/classes .
echo "built $(pwd)/build/SulfurFrame (plugin directory)"
