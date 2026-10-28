package io.github.ptrinh.nowplaying;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;

/**
 * Reads no notifications: none of the notification callbacks are used. Android lists other apps' media
 * sessions only to an app with an enabled notification listener, and keeps that listener bound while it
 * is enabled, so this is also where the pop-up ({@link Pill}) watches for a new track. No foreground
 * service, no wake locks, no network: it reacts to the media sessions' own callbacks.
 */
public class SessionListener extends NotificationListenerService {
    static final String PREFS = "settings";
    static final String PREF_POPUP = "popup";

    /** Set while the card screen is in front: it shows the track already, so no pop-up over it. */
    static volatile boolean cardOpen;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Sessions sessions;
    private Pill pill;
    private String lastKey;

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static boolean popupOn(Context ctx) {
        return prefs(ctx).getBoolean(PREF_POPUP, true);
    }

    @Override
    public void onListenerConnected() {
        if (pill == null) pill = new Pill(this, handler);
        if (sessions == null) sessions = new Sessions(this, handler, this::changed);
        sessions.start();
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.stop();
        if (pill != null) pill.removeNow();
    }

    @Override
    public void onDestroy() {
        onListenerDisconnected();
        super.onDestroy();
    }

    private void changed() {
        Sessions.Track t = sessions.track();
        if (t == null || !t.active()) return;   // paused or nothing: keep lastKey, so resuming doesn't pop
        String key = NowPlaying.trackKey(t.pkg, t.title, t.artist);
        if (key.equals(lastKey)) return;
        PowerManager pm = getSystemService(PowerManager.class);
        boolean screenOn = pm == null || pm.isInteractive();
        boolean on = popupOn(this);
        // Usage events only when the rest says pop: they are the one costly read here.
        boolean appInFront = on && screenOn && !cardOpen && t.pkg.equals(Foreground.top(this));
        if (NowPlaying.shouldPop(lastKey, key, true, on, screenOn, appInFront, cardOpen)) pill.show(t);
        lastKey = key;   // seen, popped or not: leaving the app later doesn't bring up an old track
    }
}
