package io.github.nitzzzu.voicechat;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** A timer going off: a card over whatever is playing, the label read out twice, any key closes it. */
public class ReminderActivity extends Activity {
    private static final long SHOW_MS = 45000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private AudioFocusRequest focus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String label = getIntent().getStringExtra(Timers.EXTRA_LABEL);
        if (label == null) label = "Time's up.";
        setContentView(build(label));
        getWindow().getDecorView().setKeepScreenOn(true);

        AudioManager am = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build();
        am.requestAudioFocus(focus);
        try {
            new ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_ACK, 400);
        } catch (RuntimeException e) {
            // no tone
        }
        final String say = label;
        Settings settings = new Settings(this);
        tts = new TextToSpeech(this, code -> {
            if (code != TextToSpeech.SUCCESS) return;
            if (!settings.language().isEmpty()) tts.setLanguage(Locale.forLanguageTag(settings.language()));
            tts.speak(say, TextToSpeech.QUEUE_FLUSH, null, "r1");
            tts.playSilentUtterance(800, TextToSpeech.QUEUE_ADD, "gap");
            tts.speak(say, TextToSpeech.QUEUE_ADD, null, "r2");
        });
        main.postDelayed(this::finish, SHOW_MS);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        tts.shutdown();
        getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
        super.onDestroy();
    }

    private FrameLayout build(String label) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(40);
        card.setPadding(pad, dp(32), pad, dp(32));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(0xF0101A1C);
        bg.setStroke(dp(2), 0xFF2BB3A3);
        card.setBackground(bg);
        card.addView(text("⏰", 48, Color.WHITE));
        TextView kicker = text("REMINDER  ·  " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date()), 14, 0xFF2BB3A3);
        kicker.setLetterSpacing(0.16f);
        kicker.setPadding(0, dp(10), 0, 0);
        card.addView(kicker);
        String shown = label.isEmpty() ? label : Character.toUpperCase(label.charAt(0)) + label.substring(1);
        TextView t = text(shown, 34, Color.WHITE);
        t.setPadding(0, dp(8), 0, dp(16));
        card.addView(t);
        card.addView(text("Press any button to close", 16, 0x99FFFFFF));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(640), -2, Gravity.CENTER);
        root.addView(card, lp);
        return root;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
