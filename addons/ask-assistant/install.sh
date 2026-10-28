#!/usr/bin/env bash
# Build Ask (voice chat), install it on the TV and grant what it uses. Everything else is set on the TV itself:
# the first launch shows a QR code for your phone. To set it up from here instead, pass the settings:
#
#   TV_HOST=<tv ip> addons/ask-assistant/install.sh
#   TV_HOST=<tv ip> LITELLM_URL=https://litellm.example LITELLM_KEY=sk-... [LITELLM_MODEL=...] \
#     [SEARXNG_URL=https://search.example] [NTFY_URL=https://ntfy.example/tv] [NTFY_TOKEN=...] \
#     [SPEECH_LANGUAGE=de-DE] [HOOKS='[{"name":"movie night","url":"https://n8n.example/webhook/x"}]'] \
#     [VOICE_KEY=1] addons/ask-assistant/install.sh
#
# VOICE_KEY=1 makes the remote's mic button open Ask instead of the TV's search: Ask becomes the Assistant role
# holder (the mic key is handled before any accessibility service sees it, so a key filter can't take it), and the
# first press may ask which app to use: pick Ask, then Always. It also switches on Ask's accessibility service, for
# "go back", "turn it off" and "what's on screen". Speech recognition stays with the TV's recognizer. Undo: VOICE_KEY=0.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
ROOT="$(cd ../.. && { pwd -W 2>/dev/null || pwd; })"
PY="$ROOT/tools/venv/bin/python"
[ -x "$PY" ] || PY="$ROOT/tools/venv/Scripts/python.exe"
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=io.github.nitzzzu.voicechat
SERVICE=$PKG/$PKG.KeyService
APK=app/build/outputs/apk/release/app-release.apk
tv() { "$PY" -I "$ROOT/tools/tv.py" "$1"; }
# One shell word for the TV's shell, whatever the value holds (JSON, spaces, quotes).
q() { printf "'%s'" "$(printf %s "$1" | sed "s/'/'\\\\''/g")"; }

if [ "${SKIP_BUILD:-}" != 1 ]; then ./gradlew assembleRelease -q --console=plain; fi
"$PY" -I "$ROOT/tools/tv.py" --push "$APK" /data/local/tmp/ask.apk
tv 'pm install -r /data/local/tmp/ask.apk; rm -f /data/local/tmp/ask.apk'
tv "pm grant $PKG android.permission.RECORD_AUDIO; pm grant $PKG android.permission.READ_TV_LISTINGS; appops set $PKG SYSTEM_ALERT_WINDOW allow"

extras=""
# content call splits a binding on every unescaped colon: URLs and "model:free" need theirs escaped.
add() { [ -n "${2:-}" ] && extras="$extras --extra $(q "$1:s:$(printf %s "$2" | sed 's/:/\\:/g')")"; return 0; }
add url "${LITELLM_URL:-}"
add key "${LITELLM_KEY:-}"
add model "${LITELLM_MODEL:-}"
add search "${SEARXNG_URL:-}"
add ntfy "${NTFY_URL:-}"
add ntfy_token "${NTFY_TOKEN:-}"
add language "${SPEECH_LANGUAGE:-}"
add hooks "${HOOKS:-}"
if [ -n "$extras" ]; then
  tv "content call --uri content://$PKG.setup --method set$extras"
fi

case "${VOICE_KEY:-}" in
  1)
    tv "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$SERVICE*) ;; ''|null) settings put secure enabled_accessibility_services $SERVICE ;; *) settings put secure enabled_accessibility_services \"\$s:$SERVICE\" ;; esac; settings put secure accessibility_enabled 1"
    tv "echo \"assistant was: \$(settings get secure assistant)\"; cmd role add-role-holder android.app.role.ASSISTANT $PKG 0; echo \"assistant now: \$(settings get secure assistant)\""
    echo "The mic button now opens Ask. Press it on the real remote; if asked, pick Ask, then Always."
    ;;
  0)
    tv "s=\$(settings get secure enabled_accessibility_services); n=\$(echo \"\$s\" | tr ':' '\n' | grep -v '^$SERVICE\$' | paste -sd: -); if [ -z \"\$n\" ]; then settings delete secure enabled_accessibility_services; else settings put secure enabled_accessibility_services \"\$n\"; fi"
    tv "cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss 0 2>/dev/null; settings get secure assistant"
    echo "The mic button is the TV's search again."
    ;;
esac
echo "Installed. Open Ask from Home (answer Show card), or press the mic button if VOICE_KEY=1."
