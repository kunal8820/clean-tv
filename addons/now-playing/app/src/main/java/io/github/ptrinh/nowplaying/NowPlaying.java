package io.github.ptrinh.nowplaying;

/**
 * What doesn't need Android: which media session to show, what its two lines say, how far through the
 * track it is, and when a new track earns the pop-up. {@link Sessions} does the media session calls.
 */
final class NowPlaying {
    // android.media.session.PlaybackState values, repeated so this stays testable on the JVM.
    static final int STATE_NONE = 0;
    static final int STATE_STOPPED = 1;
    static final int STATE_PAUSED = 2;
    static final int STATE_PLAYING = 3;
    static final int STATE_FAST_FORWARDING = 4;
    static final int STATE_REWINDING = 5;
    static final int STATE_BUFFERING = 6;
    static final int STATE_ERROR = 7;
    static final int STATE_CONNECTING = 8;
    static final int STATE_SKIPPING_TO_PREVIOUS = 9;
    static final int STATE_SKIPPING_TO_NEXT = 10;
    static final int STATE_SKIPPING_TO_QUEUE_ITEM = 11;

    private NowPlaying() {
    }

    /** Playing, or about to: these come first, and the play button shows pause. */
    static boolean isActive(int state) {
        switch (state) {
            case STATE_PLAYING:
            case STATE_BUFFERING:
            case STATE_FAST_FORWARDING:
            case STATE_REWINDING:
            case STATE_CONNECTING:
            case STATE_SKIPPING_TO_PREVIOUS:
            case STATE_SKIPPING_TO_NEXT:
            case STATE_SKIPPING_TO_QUEUE_ITEM:
                return true;
            default:
                return false;
        }
    }

    /**
     * The session to show, as an index into the lists Android returns (most recently active first): the
     * first one playing, else the first one paused; -1 when nothing is playing or paused. This add-on's
     * own package is skipped.
     */
    static int choose(int[] states, String[] packages, String self) {
        int paused = -1;
        for (int i = 0; i < states.length; i++) {
            if (self.equals(packages[i])) continue;
            if (isActive(states[i])) return i;
            if (states[i] == STATE_PAUSED && paused < 0) paused = i;
        }
        return paused;
    }

    /** The first line: the track's title, else the display title, else the app's name. */
    static String title(String title, String displayTitle, String appLabel) {
        if (!blank(title)) return title.trim();
        if (!blank(displayTitle)) return displayTitle.trim();
        return appLabel;
    }

    /**
     * The second line, "artist · app": the artist (or the album when there is none) and the app's name,
     * leaving out what's missing and the app's name when the first line already is it.
     */
    static String subtitle(String artist, String album, String appLabel, String shownTitle) {
        String who = !blank(artist) ? artist.trim() : !blank(album) ? album.trim() : "";
        boolean appInTitle = appLabel.equals(shownTitle);
        if (who.isEmpty()) return appInTitle ? "" : appLabel;
        return appInTitle ? who : who + " · " + appLabel;
    }

    /**
     * Where playback is now, in ms, or -1 when it can't be told. A playing session's position moves on
     * from its last report at its speed; it never passes the duration when there is one.
     */
    static long position(long position, long updatedAt, float speed, boolean playing, long duration, long now) {
        if (position < 0) return -1;
        long at = position;
        if (playing && updatedAt > 0 && now > updatedAt) at += (long) ((now - updatedAt) * (speed == 0f ? 1f : speed));
        if (at < 0) at = 0;
        return duration > 0 ? Math.min(at, duration) : at;
    }

    /** How far through the track, 0 to 1, or -1 with no duration (a live stream) or no position. */
    static float progress(long at, long duration) {
        if (duration <= 0 || at < 0) return -1f;
        return Math.max(0f, Math.min(1f, (float) at / duration));
    }

    /** A time as the tvOS player writes it: 0:07, 3:45, 1:02:03. */
    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }

    /** What makes a track a new one for the pop-up: the app, the title and the artist. */
    static String trackKey(String pkg, String title, String artist) {
        return pkg + '\n' + (title == null ? "" : title.trim()) + '\n' + (artist == null ? "" : artist.trim());
    }

    /**
     * Whether a track gets the pop-up: it is playing, it is not the one popped last, the pop-up is on, the
     * screen is on, and neither the playing app nor this add-on's own card is on screen.
     */
    static boolean shouldPop(String lastKey, String key, boolean active, boolean enabled, boolean screenOn,
                             boolean appInFront, boolean cardOpen) {
        return enabled && active && screenOn && !appInFront && !cardOpen && key != null && !key.equals(lastKey);
    }

    /**
     * The glow colour from the artwork's average colour (0xRRGGBB): brightened so its strongest channel is
     * full, or the fallback when the picture is too dark or too grey to tint anything.
     */
    static int glowColor(int rgb, int fallback) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max < 40 || (max - min) * 100 < max * 18) return fallback;
        return 0xFF000000 | (r * 255 / max) << 16 | (g * 255 / max) << 8 | (b * 255 / max);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
