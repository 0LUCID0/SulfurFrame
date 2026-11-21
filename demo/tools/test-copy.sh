#!/usr/bin/env bash
# Makes a fresh, throwaway copy of the showcase mod for SulfurFrame's self-test (the test edits sources and adds
# translations, so it never runs on the copy in the repository), then prints the sandbox command line.
#   demo/tools/test-copy.sh [copy dir]     default: %TEMP%/sf-showcase-test (or /tmp/sf-showcase-test)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../showcase-mod"
dst="${1:-${TEMP:-/tmp}/sf-showcase-test}"
rm -rf "$dst"
mkdir -p "$dst"
# sources and the Gradle wrapper only; build output and the game folder are made fresh
(cd "$src" && tar cf - --exclude=./build --exclude=./run --exclude=./.gradle .) | (cd "$dst" && tar xf -)
mkdir -p "$dst/run"
# version: without it the game reads the file as pre-1.13 and the synced key bindings break the whole file
printf '%s\n' version:3955 lang:en_us tutorialStep:none onboardAccessibility:false skipMultiplayerWarning:true pauseOnLostFocus:false > "$dst/run/options.txt"
probe="$dst/src/main/java/dev/sulfurframe/showcase/SfHotswapProbe.java"
echo "copy:  $dst"
echo "probe: $probe"
echo
echo "./sf-idea-plugin/sandbox.sh \"$dst\" -Dsulfurframe.autotest.runclient=true -Dsulfurframe.autotest.hotswap=\"$probe\" -Dsulfurframe.autotest.world=SFWorld"
