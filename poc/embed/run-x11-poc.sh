#!/usr/bin/env bash
# Linux/X11 embedding PoC (tested under WSL2 + WSLg): start vanilla Minecraft on X11, then embed it with X11Host.
#   ./run-x11-poc.sh [version] [host args...]      e.g.  ./run-x11-poc.sh 1.21.1 --hold 30 --halt
# Needs: a JDK 25 for the host (FFM), the game's Java (Debian layout /usr/lib/jvm/java-N-openjdk-amd64), libX11.
set -euo pipefail
cd "$(dirname "$0")"
V="${1:-1.21.1}"; shift || true
JAVA25="${JAVA25:-/usr/lib/jvm/java-25-openjdk-amd64/bin/java}"
export DISPLAY="${DISPLAY:-:0}"
unset WAYLAND_DISPLAY            # GLFW 3.4 would pick Wayland under WSLg; the PoC is about X11
ulimit -c 0                      # a crashed JVM (crash tests!) would leave a multi-GB core: WSL copies it to %TEMP%wsl-crashes
if ! pgrep -f "client.main.Main" >/dev/null; then
  setsid nohup "$JAVA25" McFetch.java launch "$V" ${SF_AGENT:+--agent "$SF_AGENT"} ${SF_GAME_ARGS:+-- $SF_GAME_ARGS} >/tmp/sf-x11-launch.log 2>&1 < /dev/null &
  echo "[poc] launching Minecraft $V (log /tmp/sf-mc-$V.log)"
fi
exec "$JAVA25" --add-opens java.desktop/sun.awt=ALL-UNNAMED --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED \
     --enable-native-access=ALL-UNNAMED X11Host.java "$@"
