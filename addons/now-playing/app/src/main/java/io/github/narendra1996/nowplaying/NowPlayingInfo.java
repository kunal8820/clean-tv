package io.github.narendra1996.nowplaying;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Immutable snapshot of active media playback (Spotify, YouTube Music, etc.).
 */
public final class NowPlayingInfo {
    public final String packageName;
    public final String appName;
    public final String title;
    public final String artist;
    public final int accentColor;
    public final boolean isPlaying;
    public final PendingIntent sessionActivity;
    public final Intent launchIntent;

    public NowPlayingInfo(String packageName, String appName, String title, String artist,
                          int accentColor, boolean isPlaying, PendingIntent sessionActivity, Intent launchIntent) {
        this.packageName = packageName != null ? packageName : "";
        this.appName = appName != null ? appName : "";
        this.title = title != null && !title.trim().isEmpty() ? title.trim() : "";
        this.artist = artist != null && !artist.trim().isEmpty() ? artist.trim() : "";
        this.accentColor = accentColor != 0 ? accentColor : 0xFF1DB954;
        this.isPlaying = isPlaying;
        this.sessionActivity = sessionActivity;
        this.launchIntent = launchIntent;
    }

    public void launch(Context context) {
        if (sessionActivity != null) {
            try {
                sessionActivity.send();
                return;
            } catch (Exception ignored) {}
        }
        if (launchIntent != null && context != null) {
            try {
                context.startActivity(launchIntent);
            } catch (Exception ignored) {}
        }
    }
}
