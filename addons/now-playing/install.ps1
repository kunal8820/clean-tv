# Windows version of install.sh: install Now Playing on the TV and give it what it needs.
#
# Usage: addons\now-playing\install.ps1 -TvHost 192.168.1.50              # build if needed, install, grant
#        addons\now-playing\install.ps1 -TvHost 192.168.1.50 -NoUsage     # the same without usage access (optional)
#        addons\now-playing\install.ps1 -TvHost 192.168.1.50 -Uninstall
param(
    [string]$TvHost = $env:TV_HOST,
    [switch]$NoUsage,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
$here = $PSScriptRoot
$tools = Join-Path (Split-Path (Split-Path $here -Parent) -Parent) 'tools'
$py = Join-Path $tools 'venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt' }
$pkg = 'io.github.ptrinh.nowplaying'
$listener = "$pkg/$pkg.SessionListener"
$apk = Join-Path $here 'app\build\outputs\apk\release\app-release.apk'
function Tv([string]$cmd) { & $py -I (Join-Path $tools 'tv.py') $cmd; if ($LASTEXITCODE) { throw "tv.py failed: $cmd" } }

# No double quotes inside these commands: Windows PowerShell 5.1 mangles them on the way to python, and the
# values have no spaces.
if ($Uninstall) {
    Tv "cmd notification disallow_listener $listener 2>/dev/null; pm uninstall $pkg"
    exit 0
}

if (-not (Test-Path $apk)) {
    Push-Location $here
    # javac notes go to stderr, which Windows PowerShell 5.1 turns into errors under 'Stop'.
    $ErrorActionPreference = 'Continue'
    try {
        & .\gradlew.bat assembleRelease -q
    } finally {
        Pop-Location
    }
    if ($LASTEXITCODE) { throw 'build failed (JDK 17 and Android SDK 34 needed)' }
    $ErrorActionPreference = 'Stop'
}
if (-not (Test-Path $apk)) { throw 'no APK: build it with gradlew.bat assembleRelease' }

& $py -I (Join-Path $tools 'b64push.py') $apk /data/local/tmp/nowplaying.apk | Out-Null
if ($LASTEXITCODE) { throw 'sending the APK failed; run this again' }
Tv 'pm install -r /data/local/tmp/nowplaying.apk; rm -f /data/local/tmp/nowplaying.apk'

Write-Host 'Notification access (SessionListener): Android lists other apps music only to an app with this. It reads no notifications.'
# cmd notification on Android 8.1+; on 8.0 add it to the enabled list, keeping the ones already there.
Tv ('cmd notification allow_listener LST 2>/dev/null || { s=$(settings get secure enabled_notification_listeners); case :$s: in *:LST:*) ;; *) if [ -z $s ] || [ $s = null ]; then s=LST; else s=$s:LST; fi; settings put secure enabled_notification_listeners $s;; esac; }'.Replace('LST', $listener))
Write-Host 'Display over other apps: the pop-up in the corner when a new track starts while another app is in front.'
Tv "appops set $pkg SYSTEM_ALERT_WINDOW allow"
if ($NoUsage) {
    Write-Host 'No usage access: the pop-up also shows while the music app itself is on screen.'
} else {
    Write-Host 'Usage access (GET_USAGE_STATS, optional): no pop-up while the music app itself is on screen. Skip it with -NoUsage.'
    Tv "appops set $pkg GET_USAGE_STATS allow"
}
# TCL's auto-start gate (the op doesn't exist elsewhere), so the system binds the listener after a reboot.
Tv "appops set $pkg AUTO_START allow 2>/dev/null; appops set $pkg APP_AUTO_START allow 2>/dev/null; appops write-settings 2>/dev/null; true" | Out-Null
Tv "am start -n $pkg/.MainActivity >/dev/null; echo opened"
Write-Host 'Installed. Now Playing is open on the TV. Press Home and answer Show card for the card.'
