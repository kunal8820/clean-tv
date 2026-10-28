package io.github.ptrinh.nowplaying;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The card's screen: the track playing in the background with its artwork, title, artist and app, the
 * progress, and Previous, Play/Pause and Next, plus Open (the playing app), the pop-up switch and what
 * install.sh granted. The remote's play/pause, next and previous keys act on the track shown. In the tvOS
 * style: black, a glow in the artwork's colour, glass buttons that turn white when focused, everything on
 * one 960x540 dp screen without scrolling.
 */
public class MainActivity extends Activity {
    private static final int ACCENT = 0xFFFF6B4A;
    private static final int INK = 0xFF0B0B0D;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float d;
    private Sessions sessions;
    private Sessions.Track track;

    private GradientDrawable glow;
    private ImageView art;
    private TextView eyebrow, title, subtitle, elapsed, remaining;
    private Bar bar;
    private View progressRow;
    private LinearLayout controls, checks;
    private ImageView previous, playPause, next;
    private TextView open, popup, settings;
    private Bitmap artSource;
    private String artPkg;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!sessions.started() && Sessions.granted(MainActivity.this)) sessions.start();
            drawProgress();
            drawChecks();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;
        sessions = new Sessions(this, handler, this::draw);

        FrameLayout root = new FrameLayout(this);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(720 * d);
        glow.setGradientCenter(0.22f, 0.45f);
        setGlow(ACCENT);
        root.setBackground(glow);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // A focused round button grows by a tenth: every layer up to here lets it draw past its edges.
        row.setClipChildren(false);
        // A TV screen is 960x540 dp: the artwork and the column beside it fit without scrolling.
        row.setPadding((int) (64 * d), (int) (32 * d), (int) (64 * d), (int) (40 * d));
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float artRadius = 20 * d;
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), artRadius);
            }
        });
        art.setClipToOutline(true);
        art.setElevation(0);
        int side = (int) (280 * d);
        row.addView(art, new LinearLayout.LayoutParams(side, side));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = (int) (48 * d);
        row.addView(col, colLp);

        eyebrow = text("", 13, 0x99FFFFFF);
        eyebrow.setLetterSpacing(0.08f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(eyebrow);

        title = text("", 34, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title, top(match(), 6));

        subtitle = text("", 20, 0xB3FFFFFF);
        subtitle.setMaxLines(3);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setLineSpacing(0, 1.12f);
        col.addView(subtitle, top(match(), 4));

        LinearLayout prog = new LinearLayout(this);
        prog.setOrientation(LinearLayout.VERTICAL);
        bar = new Bar(this);
        prog.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (6 * d)));
        FrameLayout times = new FrameLayout(this);
        elapsed = text("", 14, 0x99FFFFFF);
        times.addView(elapsed, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));
        remaining = text("", 14, 0x99FFFFFF);
        times.addView(remaining, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        prog.addView(times, top(match(), 6));
        progressRow = prog;
        col.addView(prog, top(match(), 22));

        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setClipChildren(false);
        previous = round(R.drawable.ic_previous, getString(R.string.previous));
        previous.setOnClickListener(v -> sessions.previous());
        playPause = round(R.drawable.ic_play, getString(R.string.play));
        playPause.setOnClickListener(v -> sessions.playPause());
        next = round(R.drawable.ic_next, getString(R.string.next));
        next.setOnClickListener(v -> sessions.next());
        controls.addView(previous, roundLp(0));
        controls.addView(playPause, roundLp(16));
        controls.addView(next, roundLp(16));
        col.addView(controls, top(wrap(), 18));

        LinearLayout buttons = new LinearLayout(this);
        open = button("");
        open.setOnClickListener(v -> openApp());
        buttons.addView(open);
        settings = button(getString(R.string.open_settings));
        settings.setOnClickListener(v -> openListenerSettings());
        buttons.addView(settings, left(wrap(), 12));
        popup = button("");
        popup.setOnClickListener(v -> togglePopup());
        buttons.addView(popup, left(wrap(), 12));
        col.addView(buttons, top(wrap(), 18));

        // What install.sh granted: three short lines under the buttons.
        checks = new LinearLayout(this);
        checks.setOrientation(LinearLayout.VERTICAL);
        col.addView(checks, top(wrap(), 16));

        TextView credit = text(getString(R.string.credit), 12, 0x4DFFFFFF);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cp.bottomMargin = (int) (14 * d);
        root.addView(credit, cp);

        setContentView(root);
        draw();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SessionListener.cardOpen = true;
        sessions.start();
        draw();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        SessionListener.cardOpen = false;
        handler.removeCallbacks(tick);
        sessions.stop();
        super.onPause();
    }

    /** The remote's media keys act on the track shown here, whichever app Android would have picked. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean media = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_NEXT
                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        if (!media || sessions.shown() == null) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            MediaController c = sessions.shown();
            switch (code) {
                case KeyEvent.KEYCODE_MEDIA_PLAY: c.getTransportControls().play(); break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: c.getTransportControls().pause(); break;
                case KeyEvent.KEYCODE_MEDIA_NEXT: sessions.next(); break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: sessions.previous(); break;
                default: sessions.playPause(); break;
            }
        }
        return true;
    }

    // ---- drawing -----------------------------------------------------------------------------------

    private void draw() {
        View focused = getCurrentFocus();
        boolean granted = Sessions.granted(this);
        track = granted ? sessions.track() : null;
        boolean has = track != null;
        controls.setVisibility(has ? View.VISIBLE : View.GONE);
        progressRow.setVisibility(has ? View.VISIBLE : View.GONE);
        open.setVisibility(has ? View.VISIBLE : View.GONE);
        settings.setVisibility(!granted && settingsIntent() != null ? View.VISIBLE : View.GONE);
        popup.setText(SessionListener.popupOn(this) ? R.string.popup_on : R.string.popup_off);
        if (!has) {
            eyebrow.setText(R.string.now_playing_upper);
            title.setText(granted ? R.string.nothing_title : R.string.no_access_title);
            subtitle.setText(granted ? R.string.nothing_hint : R.string.no_access_hint);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            subtitle.setMaxLines(6);
            art.setPadding(0, 0, 0, 0);
            art.setImageResource(R.drawable.ic_note);
            art.setScaleType(ImageView.ScaleType.CENTER);
            art.setBackground(glassTile());
            art.setElevation(0);
            artSource = null;
            artPkg = null;
            setGlow(ACCENT);
        } else {
            eyebrow.setText(track.active() ? R.string.now_playing_upper : R.string.paused_upper);
            title.setText(track.title);
            subtitle.setText(track.subtitle);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            subtitle.setMaxLines(2);
            subtitle.setVisibility(track.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            open.setText(getString(R.string.open_app, track.appLabel));
            playPause.setImageResource(track.active() ? R.drawable.ic_pause : R.drawable.ic_play);
            playPause.setContentDescription(getString(track.active() ? R.string.pause : R.string.play));
            drawArt();
        }
        if (!has) subtitle.setVisibility(View.VISIBLE);
        drawProgress();
        drawChecks();
        // Keep focus where it was; when that button went away, take the first one there is.
        if (focused == null || focused.getVisibility() != View.VISIBLE || !focused.isShown()) {
            View first = has ? playPause : settings.getVisibility() == View.VISIBLE ? settings : popup;
            first.requestFocus();
        }
    }

    /** Metadata comes again with every state change: scale a new picture only when it changed. */
    private void drawArt() {
        if (track.art != null) {
            if (track.art != artSource || !track.pkg.equals(artPkg)) {
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setBackground(null);
                art.setElevation(16 * d);
                art.setImageBitmap(Sessions.squareThumb(track.art, (int) (280 * d)));
                setGlow(NowPlaying.glowColor(Sessions.averageColor(track.art), ACCENT));
            }
        } else if (artSource != null || !track.pkg.equals(artPkg)) {
            // No artwork: the app's square icon on a glass tile.
            Drawable icon = Sessions.appIcon(this, track.pkg);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setBackground(glassTile());
            // A shadow shows through the see-through glass as stacked squares: only opaque artwork casts one.
            art.setElevation(0);
            int p = (int) (64 * d);
            art.setPadding(p, p, p, p);
            if (icon != null) art.setImageDrawable(icon);
            else art.setImageResource(R.drawable.ic_note);
            setGlow(ACCENT);
        }
        if (track.art != null) art.setPadding(0, 0, 0, 0);
        artSource = track.art;
        artPkg = track.pkg;
    }

    private void drawProgress() {
        if (track == null) return;
        long at = track.now();
        float p = NowPlaying.progress(at, track.duration);
        bar.set(p);
        if (p < 0) {
            elapsed.setText(at >= 0 ? NowPlaying.clock(at) : "");
            remaining.setText(R.string.live);
        } else {
            elapsed.setText(NowPlaying.clock(at));
            remaining.setText(getString(R.string.remaining, NowPlaying.clock(track.duration - at)));
        }
    }

    /** What install.sh grants, one line each: green On, or what to do. */
    private void drawChecks() {
        checks.removeAllViews();
        check(R.string.check_listener, Sessions.granted(this), R.string.check_off);
        check(R.string.check_overlay, Pill.allowed(this), R.string.check_off);
        check(R.string.check_usage, Foreground.allowed(this), R.string.check_off_optional);
    }

    private void check(int label, boolean on, int off) {
        LinearLayout r = new LinearLayout(this);
        r.addView(text(getString(label), 14, 0x99FFFFFF), new LinearLayout.LayoutParams((int) (220 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(text(getString(on ? R.string.check_on : off), 14, on ? 0xFF30D158 : 0xFFFF9F0A));
        checks.addView(r, top(wrap(), 2));
    }

    private void setGlow(int color) {
        glow.setColors(new int[]{(color & 0x00FFFFFF) | 0x55000000, 0x00000000});
    }

    // ---- actions -----------------------------------------------------------------------------------

    /** The playing app's own player when it says which, else the app. */
    private void openApp() {
        MediaController c = sessions.shown();
        if (c == null) return;
        PendingIntent pi = c.getSessionActivity();
        if (pi != null) {
            try {
                pi.send();
                return;
            } catch (PendingIntent.CanceledException ignored) {
                // Fall back to the app's launcher activity.
            }
        }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void togglePopup() {
        boolean on = !SessionListener.popupOn(this);
        SessionListener.prefs(this).edit().putBoolean(SessionListener.PREF_POPUP, on).apply();
        popup.setText(on ? R.string.popup_on : R.string.popup_off);
        if (on && !Pill.allowed(this)) Toast.makeText(this, R.string.popup_needs_overlay, Toast.LENGTH_LONG).show();
    }

    /** Android's notification access list; many TVs leave it out, and then the button isn't shown. */
    private Intent settingsIntent() {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        return i.resolveActivity(getPackageManager()) != null ? i : null;
    }

    private void openListenerSettings() {
        Intent i = settingsIntent();
        if (i == null) return;
        try {
            startActivity(i);
        } catch (Exception ignored) {
            // Listed but not startable on this TV.
        }
    }

    // ---- views -------------------------------------------------------------------------------------

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String label) {
        TextView b = text(label, 18, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setSingleLine();
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMaxWidth((int) (260 * d));
        b.setPadding((int) (20 * d), 0, (int) (20 * d), 0);
        b.setMinHeight((int) (48 * d));
        b.setMinimumWidth((int) (110 * d));
        b.setBackground(glass(16 * d, false));
        b.setOnFocusChangeListener((v, has) -> ((TextView) v).setTextColor(has ? INK : Color.WHITE));
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return b;
    }

    /** A round glass control with a glyph that turns dark on white when focused, and grows a little. */
    private ImageView round(int icon, String description) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setContentDescription(description);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = (int) (18 * d);
        b.setPadding(p, p, p, p);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(glass(0, true));
        b.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{INK, Color.WHITE}));
        b.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(150).start());
        return b;
    }

    private LinearLayout.LayoutParams roundLp(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (64 * d), (int) (64 * d));
        lp.leftMargin = (int) (leftDp * d);
        return lp;
    }

    private static StateListDrawable glass(float radius, boolean oval) {
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(0x1FFFFFFF);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFFF5F5F7);
        if (oval) {
            idle.setShape(GradientDrawable.OVAL);
            focused.setShape(GradientDrawable.OVAL);
        } else {
            idle.setCornerRadius(radius);
            focused.setCornerRadius(radius);
        }
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, idle);
        return s;
    }

    private GradientDrawable glassTile() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1FFFFFFF);
        g.setCornerRadius(20 * d);
        return g;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams top(LinearLayout.LayoutParams lp, int dp) {
        lp.topMargin = (int) (dp * d);
        return lp;
    }

    private LinearLayout.LayoutParams left(LinearLayout.LayoutParams lp, int dp) {
        lp.leftMargin = (int) (dp * d);
        return lp;
    }

    /**
     * The progress line: an orange fill with round ends growing from the left on a transparent track, as
     * on the launcher fork's card, so it reads on the dark screen and over a light picture behind the
     * pop-up; nothing for live streams.
     */
    static final class Bar extends View {
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float p = -1f;

        Bar(android.content.Context ctx) {
            super(ctx);
            back.setColor(0x00000000);   // transparent: only the played part shows
            fill.setColor(0xFFFF9F0A);
        }

        void set(float progress) {
            if (progress == p) return;
            p = progress;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, back);
            if (p <= 0) return;
            r.set(0, 0, Math.max(h, w * p), h);
            c.drawRoundRect(r, h / 2, h / 2, fill);
        }
    }
}
