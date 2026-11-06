#!/usr/bin/env bash
# Build sf-agent.jar with the JDK alone (Class-File API is part of JDK 24+; no ASM, no Gradle).
# usage: ./build.sh   -> poc/agent/sf-agent.jar
set -euo pipefail
cd "$(dirname "$0")"
JDK="${JDK:-$HOME/.jdks/temurin-25.0.4.1/bin}"
rm -rf out && mkdir -p out
"$JDK/javac" -Xlint:-restricted --release 25 -d out $(find src -name '*.java')
cat > out/MANIFEST.MF <<'EOF'
Premain-Class: dev.sulfurframe.agent.SfAgent
Agent-Class: dev.sulfurframe.agent.SfAgent
Can-Retransform-Classes: true
Can-Redefine-Classes: true
Enable-Native-Access: ALL-UNNAMED
EOF
"$JDK/jar" --create --file sf-agent.jar --manifest out/MANIFEST.MF -C out dev
echo "built $(pwd)/sf-agent.jar"
