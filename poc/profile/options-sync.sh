#!/usr/bin/env bash
# Run the options sync tool from source (JDK 11+). usage: ./options-sync.sh pull|push|status [--master dir] [--apply] <gameDir>...
# any Java 11+: JAVA_HOME's, else java on the PATH
JAVA="${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
JAVA="${JAVA:-java}"
exec "$JAVA" "$(dirname "$0")/src/dev/sulfurframe/profile/OptionsSync.java" "$@"
