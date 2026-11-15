#!/usr/bin/env bash
# Build sf-agent.jar (Java 8 bytecode; runs on Java 8..25) with the JDK alone, no Gradle.
#   sf-agent.jar = SfAgent + SfHooks (system class path) + nested sf-impl.jar (transformer + private copy of ASM)
# usage: ./build.sh   -> poc/agent/sf-agent.jar
set -euo pipefail
cd "$(dirname "$0")"
JDK="${JDK:-$HOME/.jdks/temurin-25.0.4.1/bin}"
# newest ASM 9.x in the Gradle cache (9.8+ reads Java 25 class files); Gradle rotates its cache, so do not pin one
ASM_JAR="${ASM_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm" -name 'asm-9*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -1)}"
[ -f "$ASM_JAR" ] || { echo "no ASM 9.x jar in the Gradle cache; set ASM_JAR" >&2; exit 1; }
echo "using $(basename "$ASM_JAR")"

rm -rf out && mkdir -p out/impl out/agent out/asm
# --release 8 warns that it is deprecated on JDK 25; the warning is expected
"$JDK/javac" --release 8 -nowarn -Xlint:-options -cp "$(cygpath -w "$ASM_JAR")" -d out/impl $(find src/impl -name '*.java')
(cd out/asm && "$JDK/jar" xf "$(cygpath -w "$ASM_JAR")" && rm -rf META-INF module-info.class)
cp -r out/asm/org out/impl/
"$JDK/jar" --create --file out/agent/sf-impl.jar -C out/impl .

"$JDK/javac" --release 8 -nowarn -Xlint:-options -d out/agent $(find src/agent ../profile/src -name '*.java')   # + the options sync core
cat > out/MANIFEST.MF <<'EOF'
Premain-Class: dev.sulfurframe.agent.SfAgent
Agent-Class: dev.sulfurframe.agent.SfAgent
Can-Retransform-Classes: true
Can-Redefine-Classes: true
EOF
"$JDK/jar" --create --file sf-agent.jar --manifest out/MANIFEST.MF -C out/agent .
echo "built $(pwd)/sf-agent.jar ($(du -k sf-agent.jar | cut -f1) KB)"
