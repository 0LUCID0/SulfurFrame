#!/usr/bin/env bash
# Start a sandbox IntelliJ IDEA with the freshly built plugin: its own config, system, plugins and log folders, so the
# user's IDE settings and plugins are never touched. One sandbox per IDE build (an IDE upgrade gets a fresh one).
#
# usage: ./sandbox.sh <projectDir> [-Dsulfurframe.autotest.pid=<pid>] [-Dsulfurframe.autotest.runclient=true] ...
#   IDEA_HOME   default: %LOCALAPPDATA%/Programs/IntelliJ IDEA Community Edition
#   SF_SANDBOX  default: %TEMP%/sf-idea-sandbox-<build number>
# The self-test (AutoTest) writes %TEMP%/sulfurframe-autotest.txt.
set -euo pipefail
cd "$(dirname "$0")"
IDEA_HOME="${IDEA_HOME:-$LOCALAPPDATA/Programs/IntelliJ IDEA Community Edition}"
PROJECT="${1:?project directory}"; shift
BUILD=$(grep -o '"buildNumber": *"[^"]*"' "$IDEA_HOME/product-info.json" | sed 's/.*"\([^"]*\)"$/\1/')
SB="${SF_SANDBOX:-${TEMP:-/tmp}/sf-idea-sandbox-$BUILD}"
[ -d build/SulfurFrame ] || bash build.sh
mkdir -p "$SB/config" "$SB/system" "$SB/plugins" "$SB/log"
rm -rf "$SB/plugins/SulfurFrame" && cp -r build/SulfurFrame "$SB/plugins/"

w() { cygpath -m "$1" 2>/dev/null || echo "$1"; }   # forward slashes: idea.properties treats '\' as an escape
cat > "$SB/idea.properties" <<EOF
idea.config.path=$(w "$SB/config")
idea.system.path=$(w "$SB/system")
idea.plugins.path=$(w "$SB/plugins")
idea.log.path=$(w "$SB/log")
EOF
# the IDE's own options plus: no consent/tips dialogs, trust the test project, and whatever the caller passed
{ cat "$IDEA_HOME/bin/idea64.exe.vmoptions"
  printf '%s\n' -Djb.consents.confirmation.enabled=false '-Djb.privacy.policy.text=<!--999.999-->' \
    -Didea.trust.all.projects=true -Dide.show.tips.on.startup.default.value=false -Didea.initially.ask.config=never "$@"
} > "$SB/idea64.vmoptions"

export IDEA_PROPERTIES="$(cygpath -w "$SB/idea.properties" 2>/dev/null || echo "$SB/idea.properties")"
export IDEA_VM_OPTIONS="$(cygpath -w "$SB/idea64.vmoptions" 2>/dev/null || echo "$SB/idea64.vmoptions")"
echo "sandbox IDE build $BUILD: $SB"
exec "$IDEA_HOME/bin/idea64.exe" "$(cygpath -w "$PROJECT" 2>/dev/null || echo "$PROJECT")"
