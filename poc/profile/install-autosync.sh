#!/usr/bin/env bash
# Install the SulfurFrame agent for automatic options sync and print the JVM argument to add in each launcher.
# The jar goes to ~/.sulfurframe (an ASCII path: Java 8 cannot load a -javaagent from a path with e.g. Korean
# characters). Nothing in any launcher is changed by this script.
set -euo pipefail
cd "$(dirname "$0")/../agent"
bash build.sh > /dev/null
mkdir -p "$HOME/.sulfurframe"
cp sf-agent.jar "$HOME/.sulfurframe/sf-agent.jar"
JAR="$(cygpath -w "$HOME/.sulfurframe/sf-agent.jar" 2>/dev/null || echo "$HOME/.sulfurframe/sf-agent.jar")"
[ -f "$HOME/.sulfurframe/profile/options.master" ] || echo "note: no master profile yet; run: options-sync.sh pull --apply <gameDir>... first"
echo
echo "Add this JVM argument to every instance that should sync (play instances):"
echo "  -javaagent:$JAR=mode=sync"
echo "Mod-dev runs started by the SulfurFrame IDE plugin already load the agent and sync too."
