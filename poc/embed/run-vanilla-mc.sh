#!/usr/bin/env bash
# Launch a vanilla Minecraft client straight from the local Gradle/Loom cache (no mod project build needed),
# in an isolated game dir per version. Find the PID afterwards with:
#   powershell "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ? CommandLine -like '*sf-mc-game*'"
#
# usage: SF_MC_VERSION=26.3 ./run-vanilla-mc.sh [opengl|vulkan] [extra game args, e.g. --quickPlaySingleplayer SFTest]
#   SF_MC_VERSION  version present in ~/.gradle/caches/fabric-loom/<version>/ (default 26.3)
#   SF_AGENT       path to sf-agent.jar to load at startup (-javaagent), SF_AGENT_ARGS its arguments
#   SF_JAVA        override the java executable (default: picked from the version's javaVersion)
#
# Notes from testing on this machine:
# - Only edit an options.txt the game wrote itself: a hand-written one has no "version:" line and the game resets the
#   graphics backend to "default". If a startup dies, the next start also resets the backend once.
# - 26.3 starts died with exit 0xC0000005 right after "Reloading ResourceManager" in roughly half the runs, with or
#   without the PoC attached (no hs_err). Check the log for "Using graphics backend ..." before testing.
set -euo pipefail
BACKEND="${1:-opengl}"
VERSION="${SF_MC_VERSION:-26.3}"
LOOM="$HOME/.gradle/caches/fabric-loom"
INFO="$LOOM/$VERSION/mojang_minecraft_info.json"
M2="$HOME/.gradle/caches/modules-2/files-2.1"
GD="${TEMP:-/tmp}/sf-mc-game-$VERSION"
LOG="${TEMP:-/tmp}/sf-mc-$VERSION.log"
[ -f "$INFO" ] || { echo "no cached metadata for $VERSION ($INFO)" >&2; exit 1; }

JAVA_MAJOR=$(grep -o '"majorVersion": *[0-9]*' "$INFO" | grep -o '[0-9]*$')
case "$JAVA_MAJOR" in
  25) DEFAULT_JAVA="$HOME/.jdks/temurin-25.0.4.1/bin/java.exe" ;;
  21) DEFAULT_JAVA="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin/java.exe" ;;
  17) DEFAULT_JAVA="/c/Program Files/Eclipse Adoptium/jdk-17.0.16.8-hotspot/bin/java.exe" ;;
  8)  DEFAULT_JAVA="/c/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin/java.exe" ;;
  *)  echo "no JDK mapping for Java $JAVA_MAJOR" >&2; exit 1 ;;
esac
JAVA="${SF_JAVA:-$DEFAULT_JAVA}"

CP=""
for n in $(grep -o '"name": *"[^"]*:[^"]*"' "$INFO" | sed 's/"name": *"//;s/"$//' | sort -u); do
  IFS=: read -r g a v c <<< "$n"
  case "${c:-}" in *linux*|*macos*|*osx*|*arm64*|*x86) continue;; esac
  p=$(find "$M2/$g/$a/$v" -name "$a-$v${c:+-$c}.jar" 2>/dev/null | head -1 || true)
  fg="$HOME/.gradle/caches/forge_gradle/maven_downloader/${g//.//}/$a/$v/$a-$v${c:+-$c}.jar"   # ForgeGradle's cache
  [ -z "$p" ] && [ -f "$fg" ] && p="$fg"
  if [ -z "$p" ]; then   # exact version not cached: fall back to the newest cached version of the same artifact
    pref=$(echo "$v" | cut -d. -f1-2)                  # prefer the same major.minor (e.g. netty 4.1.x, not 4.2)
    alt=$( (ls -1 "$M2/$g/$a" 2>/dev/null | grep -F "$pref." || ls -1 "$M2/$g/$a" 2>/dev/null) | sort -V | tail -1 || true)
    [ -n "$alt" ] && p=$(find "$M2/$g/$a/$alt" -name "$a-$alt${c:+-$c}.jar" 2>/dev/null | head -1 || true)
    [ -n "$p" ] && echo "substituted $n -> $alt" >&2
  fi
  [ -n "$p" ] && CP="$CP;$(cygpath -w "$p")" || echo "missing library $n" >&2
done
CP="${CP:1};$(cygpath -w "$LOOM/$VERSION/minecraft-client.jar")"

# SF_LOADER=fabric: Fabric Loader in production mode (Knot, intermediary namespace), from the Gradle cache.
# Libraries as in Fabric's launcher profile for loader 0.16.10 on 1.21.1.
MAIN=net.minecraft.client.main.Main
if [ "${SF_LOADER:-}" = fabric ]; then
  for coord in "net.fabricmc:fabric-loader:${SF_FABRIC_LOADER:-0.16.10}" "net.fabricmc:intermediary:$VERSION" \
               "net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7" org.ow2.asm:asm:9.7.1 org.ow2.asm:asm-analysis:9.7.1 \
               org.ow2.asm:asm-commons:9.7.1 org.ow2.asm:asm-tree:9.7.1 org.ow2.asm:asm-util:9.7.1; do
    IFS=: read -r g a v <<< "$coord"
    # intermediary is published with a -v2 classifier
    p=$(find "$M2/$g/$a/$v" \( -name "$a-$v.jar" -o -name "$a-$v-v2.jar" \) 2>/dev/null | head -1 || true)
    [ -n "$p" ] || { echo "Fabric library $coord not in the Gradle cache" >&2; exit 1; }
    CP="$CP;$(cygpath -w "$p")"
  done
  MAIN=net.fabricmc.loader.impl.launch.knot.KnotClient
  GD="$GD-fabric"; LOG="${LOG%.log}-fabric.log"
fi

ASSETS=$(grep -o '"assets": *"[^"]*"' "$INFO" | sed 's/.*"\([^"]*\)"$/\1/')
INDEX="$VERSION-$ASSETS"                               # Loom names the cached index <version>-<assets>.json
[ -f "$LOOM/assets/indexes/$INDEX.json" ] || INDEX="$ASSETS"

mkdir -p "$GD"
if [ -f "$GD/options.txt" ] && grep -q '^version:' "$GD/options.txt"; then
  sed -i "s/^preferredGraphicsBackend:.*/preferredGraphicsBackend:\"$BACKEND\"/; s/^pauseOnLostFocus:.*/pauseOnLostFocus:${SF_PAUSE_ON_LOST_FOCUS:-true}/" "$GD/options.txt"
fi

JVM_ARGS=(-Xmx2G)
[ "$JAVA_MAJOR" -ge 22 ] && JVM_ARGS+=(--enable-native-access=ALL-UNNAMED)
[ -n "${SF_AGENT:-}" ] && JVM_ARGS+=("-javaagent:$(cygpath -w "$SF_AGENT")${SF_AGENT_ARGS:+=$SF_AGENT_ARGS}")

echo "Minecraft $VERSION on Java $JAVA_MAJOR, assets $INDEX, log: $LOG"
cd "${TEMP:-/tmp}"   # log4j writes logs/ relative to the working directory
exec "$JAVA" "${JVM_ARGS[@]}" -cp "$CP" "$MAIN" \
  --version "$VERSION" --gameDir "$(cygpath -w "$GD")" --assetsDir "$(cygpath -w "$LOOM/assets")" \
  --assetIndex "$INDEX" --accessToken 0 --username SFDev --width 854 --height 480 "${@:2}" > "$LOG" 2>&1
