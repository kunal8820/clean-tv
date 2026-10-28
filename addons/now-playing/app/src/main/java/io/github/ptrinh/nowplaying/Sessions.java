package io.github.ptrinh.nowplaying;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Follows every active media session and picks the one to show ({@link NowPlaying#choose}): the card
 * screen and {@link SessionListener} each keep one. Android lists other apps' sessions only to an app
 * whose notification listener is enabled, so nothing is followed until install.sh has done that.
 */
final class Sessions {
    interface Listener {
        /** Something changed: a session came or went, or the shown one changed state or track. */
        void changed();
    }

    /** The shown session, read once: the lines, the artwork and the times. */
    static final class Track {
        String pkg, appLabel, title, subtitle, artist;
        Bitmap art;
        int state;
        long position, updatedAt, duration;
        float speed;

        boolean active() {
            return NowPlaying.isActive(state);
        }

        /** Where playback is now, in ms, or -1. */
        long now() {
            return NowPlaying.position(position, updatedAt, speed, active(), duration, android.os.SystemClock.elapsedRealtime());
        }
    }

    private final Context ctx;
    private final Handler handler;
    private final Listener listener;
    private final ComponentName component;
    private final List<MediaController> watched = new ArrayList<>();
    private MediaSessionManager manager;
    private MediaController shown;
    private boolean started;

    private final Runnable notify = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            schedule();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            schedule();
        }

        @Override
        public void onSessionDestroyed() {
            schedule();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::watch;

    Sessions(Context ctx, Handler handler, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.handler = handler;
        this.listener = listener;
        this.component = new ComponentName(this.ctx, SessionListener.class);
    }

    /** Whether the notification listener is enabled, without which Android lists no sessions. */
    static boolean granted(Context ctx) {
        ComponentName cn = new ComponentName(ctx, SessionListener.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(cn);
        }
        String enabled = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && Arrays.asList(enabled.split(":")).contains(cn.flattenToString());
    }

    /** Starts following; false when the listener isn't enabled (or Android refuses), and nothing is followed. */
    boolean start() {
        if (started) return true;
        if (!granted(ctx)) return false;
        try {
            if (manager == null) manager = ctx.getSystemService(MediaSessionManager.class);
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler);
            started = true;
            watch(manager.getActiveSessions(component));
            return true;
        } catch (SecurityException e) {
            stop();
            return false;
        }
    }

    void stop() {
        handler.removeCallbacks(notify);
        if (manager != null && started) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (Exception ignored) {
                // Never added, or the system service went away: nothing to remove.
            }
        }
        started = false;
        unwatch();
        shown = null;
    }

    boolean started() {
        return started;
    }

    MediaController shown() {
        return shown;
    }

    private void watch(List<MediaController> list) {
        unwatch();
        if (list != null) {
            for (MediaController c : list) {
                c.registerCallback(callback, handler);
                watched.add(c);
            }
        }
        pick();
    }

    private void unwatch() {
        for (MediaController c : watched) {
            try {
                c.unregisterCallback(callback);
            } catch (Exception ignored) {
                // A session that died already has nothing to unregister.
            }
        }
        watched.clear();
    }

    /** Callbacks come in bursts (state, then metadata): pick once after them. */
    private void schedule() {
        handler.removeCallbacks(notify);
        handler.postDelayed(notify, 150);
    }

    private void pick() {
        int n = watched.size();
        int[] states = new int[n];
        String[] packages = new String[n];
        for (int i = 0; i < n; i++) {
            PlaybackState st = watched.get(i).getPlaybackState();
            states[i] = st != null ? st.getState() : NowPlaying.STATE_NONE;
            packages[i] = watched.get(i).getPackageName();
        }
        int i = NowPlaying.choose(states, packages, ctx.getPackageName());
        shown = i >= 0 ? watched.get(i) : null;
        listener.changed();
    }

    /** The shown session read into a {@link Track}, or null when nothing is playing or paused. */
    Track track() {
        MediaController c = shown;
        if (c == null) return null;
        Track t = new Track();
        t.pkg = c.getPackageName();
        t.appLabel = appLabel(ctx, t.pkg);
        MediaMetadata md = c.getMetadata();
        t.title = NowPlaying.title(text(md, MediaMetadata.METADATA_KEY_TITLE),
                text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE), t.appLabel);
        t.artist = text(md, MediaMetadata.METADATA_KEY_ARTIST);
        t.subtitle = NowPlaying.subtitle(t.artist, text(md, MediaMetadata.METADATA_KEY_ALBUM), t.appLabel, t.title);
        if (md != null) {
            for (String key : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                t.art = md.getBitmap(key);
                if (t.art != null) break;
            }
            t.duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        PlaybackState st = c.getPlaybackState();
        t.state = st != null ? st.getState() : NowPlaying.STATE_NONE;
        t.position = st != null ? st.getPosition() : -1;
        t.updatedAt = st != null ? st.getLastPositionUpdateTime() : 0;
        t.speed = st != null ? st.getPlaybackSpeed() : 1f;
        return t;
    }

    /** Play or pause the shown session, as OK on the play button and the remote's play/pause key do. */
    void playPause() {
        MediaController c = shown;
        if (c == null) return;
        PlaybackState st = c.getPlaybackState();
        if (st != null && NowPlaying.isActive(st.getState())) c.getTransportControls().pause();
        else c.getTransportControls().play();
    }

    void next() {
        if (shown != null) shown.getTransportControls().skipToNext();
    }

    void previous() {
        if (shown != null) shown.getTransportControls().skipToPrevious();
    }

    static String appLabel(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;   // a package we can't see keeps its package name
        }
    }

    static Drawable appIcon(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(MediaMetadata md, String key) {
        if (md == null) return null;
        CharSequence t = md.getText(key);
        return t != null ? t.toString() : null;
    }

    /** A centre square of the artwork at most {@code size} px, so a 3000 px cover isn't kept or drawn whole. */
    static Bitmap squareThumb(Bitmap src, int size) {
        int side = Math.min(src.getWidth(), src.getHeight());
        if (side <= 0) return src;
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        Bitmap square = side == src.getWidth() && side == src.getHeight() ? src : Bitmap.createBitmap(src, x, y, side, side);
        return side > size ? Bitmap.createScaledBitmap(square, size, size, true) : square;
    }

    /** The artwork's average colour, for the glow behind it: the picture shrunk to one pixel. */
    static int averageColor(Bitmap src) {
        try {
            Bitmap one = Bitmap.createScaledBitmap(src, 1, 1, true);
            return one.getPixel(0, 0) & 0xFFFFFF;
        } catch (Exception e) {
            return 0;
        }
    }
}
