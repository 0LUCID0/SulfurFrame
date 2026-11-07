#!/usr/bin/env bash
# Build the SulfurFrame IntelliJ plugin against an installed IntelliJ IDEA (2026.2+, runs on JBR 25: FFM available).
# No Gradle and no IDE download: compiles against the IDE's own lib/*.jar.
#   IDEA_HOME   default: %LOCALAPPDATA%/Programs/IntelliJ IDEA Community Edition
# Output: sf-idea-plugin/build/SulfurFrame/lib/sulfurframe.jar (a plugin directory you can drop into a plugins folder)
set -euo pipefail
cd "$(dirname "$0")"
IDEA_HOME="${IDEA_HOME:-$LOCALAPPDATA/Programs/IntelliJ IDEA Community Edition}"
JDK="${JDK:-$HOME/.jdks/temurin-25.0.4.1/bin}"   # any JDK 25 (JBR has no javac/jar)
AGENT="../poc/agent/sf-agent.jar"
[ -f "$AGENT" ] || (cd ../poc/agent && bash build.sh)
rm -rf build && mkdir -p build/classes build/SulfurFrame/lib
"$JDK/javac" -Xlint:-restricted -nowarn --release 25 -cp "$(cygpath -w "$IDEA_HOME/lib")/*" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/* build/classes/
cp "$AGENT" build/classes/sf-agent.jar
"$JDK/jar" --create --file build/SulfurFrame/lib/sulfurframe.jar -C build/classes .
echo "built $(pwd)/build/SulfurFrame (plugin directory)"
