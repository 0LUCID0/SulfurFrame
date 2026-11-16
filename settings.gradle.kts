// Release build of the SulfurFrame IntelliJ plugin. The quick no-Gradle scripts (sf-idea-plugin/build.sh,
// poc/agent/build.sh) still work for local hacking; this build is what gets verified, signed and published.
rootProject.name = "sulfurframe"

include(":agent")
project(":agent").projectDir = file("poc/agent")
include(":sf-idea-plugin")
