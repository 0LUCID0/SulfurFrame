#!/usr/bin/env bash
# Prepares a recording session: a fresh copy of the showcase mod and a separate sandbox IDE (its own config, so the
# self-test sandbox is not touched) with a large editor font, then starts that IDE on the copy.
#   demo/tools/record-setup.sh            copy: %TEMP%/sf-rec/showcase-mod, sandbox: %TEMP%/sf-idea-sandbox-rec
# Then, in the IDE: SulfurFrame tool window -> Run client (or Worlds -> Test World), and drive the scenes with
#   python demo/tools/scenes.py --mod "$TEMP/sf-rec/showcase-mod" run particles banner sky texture shader --hold 7 --log <file>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
copy="${TEMP:-/tmp}/sf-rec/showcase-mod"   # folder name = window title
sb="${TEMP:-/tmp}/sf-idea-sandbox-rec"
bash "$here/test-copy.sh" "$copy" >/dev/null
mkdir -p "$sb/config/options"
cat > "$sb/config/options/editor-font.xml" <<'EOF'
<application>
  <component name="DefaultFont">
    <option name="VERSION" value="1" />
    <option name="FONT_SIZE" value="20" />
    <option name="FONT_SIZE_2D" value="20.0" />
    <option name="FONT_FAMILY" value="JetBrains Mono" />
    <option name="LINE_SPACING" value="1.1" />
  </component>
</application>
EOF
# a clean frame: no AI session widget, no "Start Free Trial", no feature trainer (this sandbox only)
printf '%s\n' com.intellij.air com.intellij.modules.ultimate training > "$sb/config/disabled_plugins.txt"
echo "copy:    $copy"
echo "sandbox: $sb"
SF_SANDBOX="$sb" exec "$here/../../sf-idea-plugin/sandbox.sh" "$copy" -Dsulfurframe.mute=true
