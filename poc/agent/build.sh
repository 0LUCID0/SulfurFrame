#!/usr/bin/env bash
# Build sf-agent.jar (Java 8 bytecode; runs on Java 8..25) with the JDK alone, no Gradle.
#   sf-agent.jar = SfAgent + SfHooks (system class path) + nested sf-impl.jar (transformer + private copy of ASM)
# usage: ./build.sh   -> poc/agent/sf-agent.jar
set -euo pipefail
cd "$(dirname "$0")"
# newest installed JDK 25 (an update changes the folder name)
# (by version number: sorting whole paths compared ~/.jdks against Program Files instead of the versions)
newest_jdk25() {
  for d in "$HOME/.jdks/temurin-25."* "/c/Program Files/Eclipse Adoptium/jdk-25."*; do
    [ -x "$d/bin/javac" ] || [ -x "$d/bin/javac.exe" ] || continue
    printf '%s\t%s\n' "$(basename "$d" | grep -oE '[0-9]+(\.[0-9]+)*' | head -1)" "$d/bin"
  done | sort -V -k1,1 | tail -1 | cut -f2
}
JDK="${JDK:-$(newest_jdk25)}"
[ -x "$JDK/javac" ] || [ -x "$JDK/javac.exe" ] || { echo "no JDK 25 found; set JDK=<jdk>/bin" >&2; exit 1; }
# ASM: one pinned release (keep it the latest: https://repo1.maven.org/maven2/org/ow2/asm/asm/), fetched once from
# Maven Central into ~/.sulfurframe/lib. It used to be "whatever is newest in the Gradle cache", which changed under us.
ASM_VERSION=9.11.1
fetch_asm() {   # artifact -> ~/.sulfurframe/lib/<artifact>-<version>.jar (checked against Maven Central's SHA-1)
  local jar="$HOME/.sulfurframe/lib/$1-$ASM_VERSION.jar"
  if [ ! -f "$jar" ]; then
    mkdir -p "$(dirname "$jar")"
    local url="https://repo1.maven.org/maven2/org/ow2/asm/$1/$ASM_VERSION/$1-$ASM_VERSION.jar"
    curl -fsSL -o "$jar.tmp" "$url"
    [ "$(sha1sum "$jar.tmp" | cut -c1-40)" = "$(curl -fsSL "$url.sha1" | cut -c1-40)" ] || { rm -f "$jar.tmp"; echo "$1 download: checksum mismatch" >&2; exit 1; }
    mv "$jar.tmp" "$jar"
  fi
  echo "$jar"
}
ASM_JAR="${ASM_JAR:-$(fetch_asm asm)}"
ASM_COMMONS_JAR="${ASM_COMMONS_JAR:-$(fetch_asm asm-commons)}"   # ClassRemapper (runtime registration's shadow copy)
echo "using $(basename "$ASM_JAR"), $(basename "$(dirname "$JDK")")"

rm -rf out && mkdir -p out/impl out/agent out/asm
# --release 8 warns that it is deprecated on JDK 25; the warning is expected
"$JDK/javac" --release 8 -nowarn -Xlint:-options -cp "$(cygpath -w "$ASM_JAR");$(cygpath -w "$ASM_COMMONS_JAR")" -d out/impl $(find src/impl -name '*.java')
(cd out/asm && "$JDK/jar" xf "$(cygpath -w "$ASM_JAR")" && "$JDK/jar" xf "$(cygpath -w "$ASM_COMMONS_JAR")" && rm -rf META-INF module-info.class)
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
