# Now Playing

Optional standalone Android TV module card, maintained by [narendra1996](https://github.com/narendra1996).
Build with JDK 17 and Android SDK 34: `./gradlew testReleaseUnitTest assembleRelease`.
The wrapper scripts reuse the repository's pinned Gradle wrapper with this folder as the project;
no wrapper JAR is copied into the add-on and no launcher source is included. Keep this folder inside
the repository when building. Install the APK you build with ADB or your preferred installer.
No binaries or prebuilt APKs are committed.

The launcher discovers the static module card (`com.gathin.tvhome.category.DOCK_CARD`) and asks whether
to show it on Home. Selecting the card opens the Now Playing screen, displaying active playback
metadata (track title with continuous focus marquee, artist, streaming app branding) and TV remote
transport controls (Previous, Play/Pause, Next) with a one-click session resume button into Spotify,
YouTube Music, or the active player.

## Permissions

- `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`: Used by `NowPlayingService` to observe active system `MediaSession` instances and read playback metadata (track title, artist, playback state) to update the UI and route D-pad transport actions.
- **Zero network access**: The `INTERNET` permission is **not** declared. No telemetry, no analytics, no ads, and no external network calls. All media session communication runs locally on the TV.

## Validation

Tested on **VU 43" 4K Android TV (Android TV 11)**.
All unit tests and release builds pass cleanly. Module card discovery, TV remote D-pad navigation, media transport actions, and session resume verified on real TV hardware.
