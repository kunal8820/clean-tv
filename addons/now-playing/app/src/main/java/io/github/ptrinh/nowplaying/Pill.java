package io.github.ptrinh.nowplaying;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The pop-up: a glass pill in the top right corner with the artwork, "Now Playing", the title and the
 * artist, for six seconds when a new track starts, over whatever is on screen, the way tvOS shows one. It
 * never takes focus or touches: the remote keeps working in the app underneath, and the remote's media
 * keys go to the playing app as always.
 */
final class Pill {
    private static final long SHOW_MS = 6000;

    private final Context ctx;
    private final Handler handler;
    private final WindowManager wm;
    private final float d;
    private View view;
    private Sessions.Track track;
    private MainActivity.Bar bar;

    private final Runnable hide = this::hide;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (view == null || track == null) return;
            float p = NowPlaying.progress(track.now(), track.duration);
            bar.set(p);
            if (p >= 0) handler.postDelayed(this, 250);
        }
    };

    Pill(Context ctx, Handler handler) {
        this.ctx = ctx;
        this.handler = handler;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.d = ctx.getResources().getDisplayMetrics().density;
    }

    static boolean allowed(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /** Shows the track, replacing a pill that is still up; false when overlays aren't allowed. */
    boolean show(Sessions.Track t) {
        if (!allowed(ctx) || wm == null) return false;
        removeNow();
        LinearLayout pill = new LinearLayout(ctx);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (12 * d);
        pill.setPadding(pad, pad, (int) (24 * d), pad);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xE61C1C1E);
        glass.setCornerRadius(24 * d);
        glass.setStroke(Math.max(1, Math.round(d)), 0x26FFFFFF);
        pill.setBackground(glass);
        pill.setElevation(12 * d);

        ImageView art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float radius = 12 * d;
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        art.setClipToOutline(true);
        if (t.art != null) {
            art.setImageBitmap(Sessions.squareThumb(t.art, (int) (64 * d)));
        } else {
            Drawable icon = Sessions.appIcon(ctx, t.pkg);
            if (icon != null) art.setImageDrawable(icon);
            else art.setImageResource(R.drawable.ic_note);
            GradientDrawable tile = new GradientDrawable();
            tile.setColor(0x1FFFFFFF);
            art.setBackground(tile);
        }
        pill.addView(art, new LinearLayout.LayoutParams((int) (64 * d), (int) (64 * d)));

        LinearLayout lines = new LinearLayout(ctx);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text(ctx.getString(R.string.now_playing_upper), 11, 0x99FFFFFF);
        eyebrow.setLetterSpacing(0.08f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        lines.addView(eyebrow);
        TextView title = text(t.title, 18, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        lines.addView(title);
        if (!t.subtitle.isEmpty()) lines.addView(text(t.subtitle, 14, 0xB3FFFFFF));
        // The progress under the lines, moving while the pill is up; none for a live stream.
        bar = new MainActivity.Bar(ctx);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(2, Math.round(3 * d)));
        bp.topMargin = (int) (8 * d);
        lines.addView(bar, bp);
        if (NowPlaying.progress(t.now(), t.duration) < 0) bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (16 * d);
        pill.addView(lines, lp);

        WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        wp.gravity = Gravity.TOP | Gravity.END;
        wp.x = (int) (48 * d);
        wp.y = (int) (32 * d);
        wp.setTitle("Now Playing");
        try {
            wm.addView(pill, wp);
        } catch (Exception e) {
            return false;   // the overlay permission went away between the check and now
        }
        view = pill;
        track = t;
        tick.run();
        pill.setAlpha(0f);
        pill.setTranslationY(-16 * d);
        pill.animate().alpha(1f).translationY(0f).setDuration(260).start();
        handler.postDelayed(hide, SHOW_MS);
        return true;
    }

    void hide() {
        handler.removeCallbacks(hide);
        final View v = view;
        if (v == null) return;
        v.animate().alpha(0f).translationY(-16 * d).setDuration(300).withEndAction(() -> {
            if (view == v) removeNow();
        }).start();
    }

    void removeNow() {
        handler.removeCallbacks(hide);
        handler.removeCallbacks(tick);
        track = null;
        View v = view;
        view = null;
        if (v != null) {
            try {
                wm.removeView(v);
            } catch (Exception ignored) {
                // Already gone.
            }
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth((int) (380 * d));
        return t;
    }
}
