#!/usr/bin/env bash
# Run the options sync tool from source (JDK 11+). usage: ./options-sync.sh pull|push|status [--master dir] [--apply] <gameDir>...
JAVA="${JAVA:-$HOME/.jdks/temurin-25.0.4.1/bin/java.exe}"
exec "$JAVA" "$(dirname "$0")/src/dev/sulfurframe/profile/OptionsSync.java" "$@"
