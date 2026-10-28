package io.github.narendra1996.nowplaying;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Leanback Android TV activity providing playback display and transport controls for active media sessions.
 */
public class MainActivity extends Activity {

    private TextView mAppName;
    private TextView mTrackTitle;
    private TextView mTrackArtist;
    private ImageView mAppIcon;
    private LinearLayout mControlsRow;
    private ImageButton mBtnPrevious;
    private ImageButton mBtnPlayPause;
    private ImageButton mBtnNext;
    private Button mBtnResume;
    private Button mBtnPermission;

    private NowPlayingInfo mCurrentInfo;

    private final NowPlayingService.Callback mServiceCallback = new NowPlayingService.Callback() {
        @Override
        public void onNowPlayingChanged(NowPlayingInfo info) {
            runOnUiThread(() -> updateUI(info));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAppName = findViewById(R.id.appName);
        mTrackTitle = findViewById(R.id.trackTitle);
        mTrackArtist = findViewById(R.id.trackArtist);
        mAppIcon = findViewById(R.id.appIcon);
        mControlsRow = findViewById(R.id.controlsRow);
        mBtnPrevious = findViewById(R.id.btnPrevious);
        mBtnPlayPause = findViewById(R.id.btnPlayPause);
        mBtnNext = findViewById(R.id.btnNext);
        mBtnResume = findViewById(R.id.btnResume);
        mBtnPermission = findViewById(R.id.btnPermission);

        mBtnPrevious.setOnClickListener(v -> NowPlayingService.skipToPrevious());
        mBtnPlayPause.setOnClickListener(v -> {
            if (mCurrentInfo != null && mCurrentInfo.isPlaying) {
                NowPlayingService.pause();
            } else {
                NowPlayingService.play();
            }
        });
        mBtnNext.setOnClickListener(v -> NowPlayingService.skipToNext());
        mBtnResume.setOnClickListener(v -> {
            if (mCurrentInfo != null) {
                mCurrentInfo.launch(MainActivity.this);
            }
        });

        mBtnPermission.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception ignored) {}
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        NowPlayingService.setCallback(mServiceCallback);
        NowPlayingService.requestRefresh();
        updateUI(NowPlayingService.getCurrentInfo());
    }

    @Override
    protected void onPause() {
        super.onPause();
        NowPlayingService.setCallback(null);
    }

    private void updateUI(NowPlayingInfo info) {
        mCurrentInfo = info;
        PackageManager pm = getPackageManager();

        if (info != null) {
            mAppName.setText(info.appName.isEmpty() ? getString(R.string.app_name) : info.appName);
            mAppName.setTextColor(info.accentColor);

            mTrackTitle.setText(info.title.isEmpty() ? getString(R.string.no_media) : info.title);
            mTrackTitle.setSelected(true); // Start marquee scroll

            mTrackArtist.setText(info.artist.isEmpty() ? info.appName : info.artist);

            mBtnPlayPause.setImageResource(info.isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
            mBtnResume.setText(getString(R.string.resume_app, info.appName));

            try {
                Drawable icon = pm.getApplicationIcon(info.packageName);
                mAppIcon.setImageDrawable(icon);
            } catch (Exception e) {
                mAppIcon.setImageResource(R.drawable.ic_launcher);
            }

            mControlsRow.setVisibility(View.VISIBLE);
            mBtnPermission.setVisibility(View.GONE);

            if (!mBtnPlayPause.hasFocus() && !mBtnResume.hasFocus() && !mBtnNext.hasFocus() && !mBtnPrevious.hasFocus()) {
                mBtnPlayPause.requestFocus();
            }
        } else {
            mAppIcon.setImageResource(R.drawable.ic_launcher);
            mAppName.setText(R.string.app_name);
            mAppName.setTextColor(0xFF1DB954);
            mTrackTitle.setText(R.string.no_media);
            mTrackTitle.setSelected(false);

            if (!NowPlayingService.isConnected()) {
                mTrackArtist.setText(R.string.permission_hint);
                mBtnPermission.setVisibility(View.VISIBLE);
                mControlsRow.setVisibility(View.GONE);
                mBtnPermission.requestFocus();
            } else {
                mTrackArtist.setText(R.string.play_music_prompt);
                mBtnPermission.setVisibility(View.GONE);
                mControlsRow.setVisibility(View.GONE);
            }
        }
    }
}
