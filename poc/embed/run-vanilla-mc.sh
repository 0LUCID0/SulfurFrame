#!/usr/bin/env bash
# Launch vanilla Minecraft 26.3 straight from the local Gradle/Loom cache (no mod project build needed),
# in an isolated game dir, with the chosen graphics backend. Prints the log path; find the PID afterwards
# with:  powershell "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ? CommandLine -like '*sf-mc-game*'"
#
# usage: ./run-vanilla-mc.sh opengl|vulkan [extra game args, e.g. --quickPlaySingleplayer SFTest]
set -euo pipefail
# Asset index "34" does not exist, so the game runs without sounds/translations (irrelevant for embedding).
BACKEND="${1:-opengl}"
JAVA="${JAVA:-$HOME/.jdks/temurin-25.0.4.1/bin/java.exe}"
LOOM="$HOME/.gradle/caches/fabric-loom"
INFO="$LOOM/26.3/mojang_minecraft_info.json"
M2="$HOME/.gradle/caches/modules-2/files-2.1"
GD="${TEMP:-/tmp}/sf-mc-game"
LOG="${TEMP:-/tmp}/sf-mc-$BACKEND.log"

CP=""
for n in $(grep -o '"name": *"[^"]*:[^"]*"' "$INFO" | sed 's/"name": *"//;s/"$//' | sort -u); do
  IFS=: read -r g a v c <<< "$n"
  case "${c:-}" in *linux*|*macos*|*arm64*|*x86) continue;; esac
  p=$(find "$M2/$g/$a/$v" -name "$a-$v${c:+-$c}.jar" 2>/dev/null | head -1)
  [ -n "$p" ] && CP="$CP;$(cygpath -w "$p")" || echo "missing library $n" >&2
done
CP="${CP:1};$(cygpath -w "$LOOM/26.3/minecraft-client.jar")"

mkdir -p "$GD"
# Only edit an options.txt the game wrote itself. A hand-written one has no "version:" line, and the game's
# OptionsForceDefaultGraphicsApiFix then resets the backend to "default" (= OpenGL here). So on the very first launch,
# let the game create the file, close it (normally, not kill), and run again.
# Also: if a startup dies, the next start logs "Detected unexpected shutdown during last game startup" and resets the
# backend to default once. On this machine some starts died with exit 139 right after "Reloading ResourceManager",
# with or without the PoC host attached. Check the log for "Using graphics backend ..." before testing.
if [ -f "$GD/options.txt" ] && grep -q '^version:' "$GD/options.txt"; then
  sed -i "s/^preferredGraphicsBackend:.*/preferredGraphicsBackend:\"$BACKEND\"/; s/^pauseOnLostFocus:.*/pauseOnLostFocus:${SF_PAUSE_ON_LOST_FOCUS:-true}/" "$GD/options.txt"
else
  echo "no game-written options.txt yet: this first run uses the default backend; close the game and run again" >&2
fi

echo "log: $LOG"
cd "${TEMP:-/tmp}"   # log4j writes logs/ relative to the working directory
AGENT_ARGS=()
# SF_AGENT=/path/to/sf-agent.jar loads the SulfurFrame javaagent at startup (otherwise the host can attach it later)
[ -n "${SF_AGENT:-}" ] && AGENT_ARGS=("-javaagent:$(cygpath -w "$SF_AGENT")=codingFps=${SF_CODING_FPS:-15}")
exec "$JAVA" -Xmx2G --enable-native-access=ALL-UNNAMED "${AGENT_ARGS[@]}" -cp "$CP" net.minecraft.client.main.Main \
  --version 26.3 --gameDir "$(cygpath -w "$GD")" --assetsDir "$(cygpath -w "$LOOM/assets")" \
  --assetIndex "${SF_ASSET_INDEX:-34}" --accessToken 0 --username SFDev --width 854 --height 480 "${@:2}" > "$LOG" 2>&1
