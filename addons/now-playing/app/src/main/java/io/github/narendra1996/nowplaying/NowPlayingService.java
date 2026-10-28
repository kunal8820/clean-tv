package io.github.narendra1996.nowplaying;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

/**
 * Android NotificationListenerService that listens for active MediaSessions (Spotify, YouTube Music, etc.)
 * across the system and provides live playback information for Leanback TV display.
 */
public class NowPlayingService extends NotificationListenerService {

    public interface Callback {
        void onNowPlayingChanged(NowPlayingInfo info);
    }

    private static volatile Callback sCallback;
    private static volatile NowPlayingInfo sCurrentInfo;
    private static volatile NowPlayingService sInstance;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private MediaSessionManager mSessionManager;
    private final List<MediaController> mRegisteredControllers = new ArrayList<>();
    private MediaController mTrackedController;

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    mMainHandler.post(() -> handleSessions(controllers));
                }
            };

    private final MediaController.Callback mControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            mMainHandler.post(() -> refreshCurrent());
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            mMainHandler.post(() -> refreshCurrent());
        }
    };

    public static boolean isConnected() {
        return sInstance != null;
    }

    public static void setCallback(Callback callback) {
        sCallback = callback;
        if (callback != null) {
            callback.onNowPlayingChanged(sCurrentInfo);
        }
    }

    public static NowPlayingInfo getCurrentInfo() {
        return sCurrentInfo;
    }

    public static void requestRefresh() {
        NowPlayingService instance = sInstance;
        if (instance != null) {
            instance.mMainHandler.post(instance::refreshCurrent);
        }
    }

    public static void play() {
        NowPlayingService instance = sInstance;
        if (instance != null && instance.mTrackedController != null) {
            MediaController.TransportControls tc = instance.mTrackedController.getTransportControls();
            if (tc != null) tc.play();
        }
    }

    public static void pause() {
        NowPlayingService instance = sInstance;
        if (instance != null && instance.mTrackedController != null) {
            MediaController.TransportControls tc = instance.mTrackedController.getTransportControls();
            if (tc != null) tc.pause();
        }
    }

    public static void skipToNext() {
        NowPlayingService instance = sInstance;
        if (instance != null && instance.mTrackedController != null) {
            MediaController.TransportControls tc = instance.mTrackedController.getTransportControls();
            if (tc != null) tc.skipToNext();
        }
    }

    public static void skipToPrevious() {
        NowPlayingService instance = sInstance;
        if (instance != null && instance.mTrackedController != null) {
            MediaController.TransportControls tc = instance.mTrackedController.getTransportControls();
            if (tc != null) tc.skipToPrevious();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        try {
            mSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (mSessionManager != null) {
                ComponentName cn = new ComponentName(this, NowPlayingService.class);
                mSessionManager.addOnActiveSessionsChangedListener(mSessionsListener, cn);
                handleSessions(mSessionManager.getActiveSessions(cn));
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        detachControllers();
        if (mSessionManager != null) {
            try {
                mSessionManager.removeOnActiveSessionsChangedListener(mSessionsListener);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sInstance == this) sInstance = null;
        detachControllers();
        if (mSessionManager != null) {
            try {
                mSessionManager.removeOnActiveSessionsChangedListener(mSessionsListener);
            } catch (Exception ignored) {}
        }
    }

    private void detachControllers() {
        for (MediaController mc : mRegisteredControllers) {
            try {
                mc.unregisterCallback(mControllerCallback);
            } catch (Exception ignored) {}
        }
        mRegisteredControllers.clear();
        mTrackedController = null;
    }

    private void refreshCurrent() {
        if (mSessionManager != null) {
            try {
                handleSessions(mSessionManager.getActiveSessions(new ComponentName(this, NowPlayingService.class)));
                return;
            } catch (Exception ignored) {}
        }
        if (mTrackedController != null) {
            bindController(mTrackedController);
        }
    }

    private void handleSessions(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            detachControllers();
            publish(null);
            return;
        }

        detachControllers();
        String ownPkg = getPackageName();
        MediaController playing = null;

        for (MediaController mc : controllers) {
            if (mc == null) continue;
            String pkg = mc.getPackageName();
            if (ownPkg.equals(pkg)) continue;
            try {
                mc.registerCallback(mControllerCallback);
                mRegisteredControllers.add(mc);
            } catch (Exception ignored) {}

            PlaybackState st = mc.getPlaybackState();
            if (playing == null && st != null && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING)) {
                playing = mc;
            }
        }

        if (playing == null) {
            for (MediaController mc : controllers) {
                if (mc == null || ownPkg.equals(mc.getPackageName())) continue;
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PAUSED) {
                    playing = mc;
                    break;
                }
            }
        }

        if (playing != null) {
            mTrackedController = playing;
            bindController(playing);
        } else {
            mTrackedController = null;
            publish(null);
        }
    }

    private void bindController(MediaController controller) {
        String pkg = controller.getPackageName();
        PlaybackState state = controller.getPlaybackState();
        int pbState = state != null ? state.getState() : PlaybackState.STATE_NONE;
        boolean isPlaying = pbState == PlaybackState.STATE_PLAYING || pbState == PlaybackState.STATE_BUFFERING;
        boolean isPaused = pbState == PlaybackState.STATE_PAUSED;

        if (!isPlaying && !isPaused) {
            publish(null);
            return;
        }

        MediaMetadata metadata = controller.getMetadata();
        String title = null;
        String artist = null;
        if (metadata != null) {
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null && metadata.getDescription() != null && metadata.getDescription().getTitle() != null) {
                title = metadata.getDescription().getTitle().toString();
            }
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null && metadata.getDescription() != null && metadata.getDescription().getSubtitle() != null) {
                artist = metadata.getDescription().getSubtitle().toString();
            }
        }

        PackageManager pm = getPackageManager();
        String appName = pkg;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            appName = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException ignored) {}

        int accent = 0xFF1DB954; // Spotify signature green
        if (pkg.contains("youtube")) accent = 0xFFFF0000;
        else if (pkg.contains("netflix")) accent = 0xFFE50914;
        else if (pkg.contains("amazon") || pkg.contains("prime")) accent = 0xFF00A8E1;
        else if (pkg.contains("hotstar")) accent = 0xFF1F80E0;

        PendingIntent pi = controller.getSessionActivity();
        Intent launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launchIntent == null) launchIntent = pm.getLaunchIntentForPackage(pkg);

        NowPlayingInfo info = new NowPlayingInfo(pkg, appName, title, artist, accent, isPlaying, pi, launchIntent);
        publish(info);
    }

    private void publish(NowPlayingInfo info) {
        sCurrentInfo = info;
        Callback cb = sCallback;
        if (cb != null) {
            cb.onNowPlayingChanged(info);
        }
    }
}
