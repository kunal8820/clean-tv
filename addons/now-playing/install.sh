#!/usr/bin/env bash
# Install Now Playing on the TV and give it what it needs, saying what each grant is for.
#
#   TV_HOST=<tv ip> addons/now-playing/install.sh               # build if needed, install, grant
#   TV_HOST=<tv ip> addons/now-playing/install.sh --no-usage    # the same without usage access (optional)
#   TV_HOST=<tv ip> addons/now-playing/install.sh --uninstall
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$HERE/../../tools"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY="$TOOLS/venv/bin/python"
[ -x "$PY" ] || PY="$TOOLS/venv/Scripts/python.exe"
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=io.github.ptrinh.nowplaying
LISTENER=$PKG/$PKG.SessionListener
APK="$HERE/app/build/outputs/apk/release/app-release.apk"
tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }

if [ "${1:-}" = --uninstall ]; then
  # Take the listener off the enabled list, keeping any others, then remove the app.
  tv "cmd notification disallow_listener $LISTENER 2>/dev/null; pm uninstall $PKG"
  exit 0
fi

if [ ! -f "$APK" ]; then
  [ -f "$HERE/local.properties" ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=$ANDROID_HOME" > "$HERE/local.properties"; } || true
  if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
  (cd "$HERE" && ./gradlew assembleRelease -q --console=plain) \
    || { echo "build failed (JDK 17 and Android SDK 34 needed)" >&2; exit 1; }
fi
[ -f "$APK" ] || { echo "no APK: build it first (cd addons/now-playing && ./gradlew assembleRelease)" >&2; exit 1; }

"$PY" -I "$TOOLS/b64push.py" "$APK" /data/local/tmp/nowplaying.apk >/dev/null
OUT=$(tv 'pm install -r /data/local/tmp/nowplaying.apk; rm -f /data/local/tmp/nowplaying.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

echo "Notification access (SessionListener): Android lists other apps' music only to an app with this. It reads no notifications."
# cmd notification on Android 8.1+; on 8.0 add it to the enabled list, keeping the ones already there.
tv "cmd notification allow_listener $LISTENER 2>/dev/null || { s=\$(settings get secure enabled_notification_listeners); case :\$s: in *:$LISTENER:*) ;; *) if [ -z \$s ] || [ \$s = null ]; then s=$LISTENER; else s=\$s:$LISTENER; fi; settings put secure enabled_notification_listeners \$s;; esac; }"
echo "Display over other apps: the pop-up in the corner when a new track starts while another app is in front."
tv "appops set $PKG SYSTEM_ALERT_WINDOW allow"
if [ "${1:-}" = --no-usage ]; then
  echo "No usage access: the pop-up also shows while the music app itself is on screen."
else
  echo "Usage access (GET_USAGE_STATS, optional): no pop-up while the music app itself is on screen. Skip it with --no-usage."
  tv "appops set $PKG GET_USAGE_STATS allow"
fi
# TCL's auto-start gate (the op doesn't exist elsewhere), so the system binds the listener after a reboot.
tv "appops set $PKG AUTO_START allow 2>/dev/null; appops set $PKG APP_AUTO_START allow 2>/dev/null; appops write-settings 2>/dev/null; true" >/dev/null
tv "am start -n $PKG/.MainActivity >/dev/null; echo opened"
echo "Installed. Now Playing is open on the TV. Press Home and answer Show card for the card."
