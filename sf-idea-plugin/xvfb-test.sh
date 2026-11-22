#!/usr/bin/env bash
# Linux auto test on a real X window manager: Xvfb (:99, 1920x1080) + openbox, instead of WSLg.
#   xvfb-test.sh f11                      vanilla 1.21.1 attached by pid: embed, float, hide, F11 in/out, editor tab
#   xvfb-test.sh runclient [hotswap-file]  the plugin's own Run client in $SF_PROJ (default ~/sfproj); with a probe file also hotswap + restart (world SFWorld)
# Needs: ~/idea (IntelliJ Linux), ~/sfs (sandbox config: idea.properties, idea64.vmoptions with -Dawt.toolkit.name=XToolkit),
#   ~/sf (poc/embed sources incl. McFetch.java), ~/sfproj (a Fabric project), and sudo apt install xvfb openbox xdotool x11-apps imagemagick
set -uo pipefail
MODE="${1:-f11}"
PROJ="${SF_PROJ:-$HOME/sfproj}"   # the release check passes a fresh copy of demo/showcase-mod
REPO="$(cd "$(dirname "$0")/.." && pwd)"   # this repository (run from WSL: /mnt/c/...)
J25=/usr/lib/jvm/java-25-openjdk-amd64/bin/java
export DISPLAY=:99
unset WAYLAND_DISPLAY
ulimit -c 0
# games of earlier runs: vanilla (McFetch) and Fabric dev clients (devlaunchinjector, no net.minecraft... on their
# command line). A dev client left running keeps the test world's session.lock, and quick play then cannot open it.
pkill -f "$HOME/idea/bin/idea" 2>/dev/null; pkill -f net.minecraft.client.main.Main 2>/dev/null; pkill -f fabric.dli.config 2>/dev/null
for i in $(seq 1 30); do pgrep -f "$HOME/idea/bin/idea" >/dev/null || break; sleep 1; done; pkill -9 -f "$HOME/idea/bin/idea" 2>/dev/null
for i in $(seq 1 15); do pgrep -f fabric.dli.config >/dev/null || break; sleep 1; done; pkill -9 -f fabric.dli.config 2>/dev/null
if ! pgrep -x Xvfb >/dev/null; then
  setsid nohup Xvfb :99 -screen 0 1920x1080x24 -nolisten tcp >/tmp/sf-xvfb.log 2>&1 < /dev/null &
  sleep 2
fi
pgrep -x openbox >/dev/null || { setsid nohup openbox >/tmp/sf-openbox.log 2>&1 < /dev/null & sleep 2; }
rm -rf ~/sfs/plugins/SulfurFrame && cp -r "$REPO/sf-idea-plugin/build/SulfurFrame" ~/sfs/plugins/
sed -i '/sulfurframe\.autotest/d' ~/sfs/idea64.vmoptions
if [ "$MODE" = f11 ]; then
  cd ~/sf
  setsid nohup "$J25" McFetch.java launch 1.21.1 >/tmp/sf-x11-launch.log 2>&1 < /dev/null &
  P=""
  for i in $(seq 1 180); do P=$(pgrep -f net.minecraft.client.main.Main | head -1); [ -n "$P" ] && break; sleep 2; done
  [ -z "$P" ] && { echo "game did not start"; tail -20 /tmp/sf-x11-launch.log; exit 1; }
  echo "game pid $P"; sleep 25
  printf -- '-Dsulfurframe.autotest.pid=%s\n-Dsulfurframe.autotest.keep=true\n' "$P" >> ~/sfs/idea64.vmoptions
else
  printf -- '-Dsulfurframe.autotest.runclient=true\n-Dsulfurframe.autotest.keep=true\n' >> ~/sfs/idea64.vmoptions
  [ -n "${2:-}" ] && printf -- '-Dsulfurframe.autotest.hotswap=%s
-Dsulfurframe.autotest.world=SFWorld
' "$2" >> ~/sfs/idea64.vmoptions
fi
rm -f /tmp/sulfurframe-autotest.txt /tmp/sulfurframe-autotest-*.png
IDEA_PROPERTIES=~/sfs/idea.properties IDEA_VM_OPTIONS=~/sfs/idea64.vmoptions setsid nohup ~/idea/bin/idea "$PROJ" >/tmp/sf-idea.log 2>&1 < /dev/null &
sleep 15
for i in $(seq 1 400); do [ -f /tmp/sulfurframe-autotest.txt ] && break; pgrep -f "$HOME/idea/bin/idea" >/dev/null || break; sleep 3; done
sleep 2
cat /tmp/sulfurframe-autotest.txt 2>/dev/null || { echo "no result"; tail -30 /tmp/sf-idea.log; }
grep -a 'SulfurFrame\].*\(fullscreen\|embedded again\|hotswap\|error\)' /tmp/sf-idea.log | tail -12
ls ~/java_error_in_idea_*.log 2>/dev/null
xwd -root -silent 2>/dev/null | convert xwd:- /tmp/sf-xvfb-screen.png 2>/dev/null && echo "screen /tmp/sf-xvfb-screen.png"
