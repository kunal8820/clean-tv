package io.github.nitzzzu.voicechat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * First-run setup, made for the remote: a QR code opens a form on the phone (no typing keys with a D-pad), the TV
 * checks the server, then you pick the default model, allow the microphone, and set the mic button.
 */
public class SetupActivity extends Activity implements SetupServer.Events {
    private static final int ACCENT = 0xFF2BB3A3;
    private static final int PERMISSIONS = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Settings settings;
    private SetupServer server;
    private FrameLayout stage;
    private TextView pinView, status;
    private int step;
    private List<String> models = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        stage = new FrameLayout(this);
        stage.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0E2A2B, 0xFF071012, 0xFF000000}));
        setContentView(stage);
        connectStep();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (step == 4) micStep();   // back from accessibility settings: show whether it took
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && step == 1 && settings.ready()) {
            // Already set up: Back keeps what is there and moves on.
            server.stop();
            loadModelsThenPick();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- step 1: connect ----------------------------------------------------------------------------

    private void connectStep() {
        step = 1;
        server = new SetupServer(this, this);
        String address;
        try {
            address = server.start();
        } catch (IOException e) {
            address = null;
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(80), dp(48), dp(80), dp(48));

        LinearLayout left = column();
        left.addView(kicker("Set up Ask"));
        left.addView(title("Scan with your phone"));
        left.addView(body("Your phone must be on the same Wi-Fi as the TV. A form opens where you paste your "
                + "LiteLLM address and key. No typing with the remote."));
        if (address != null) {
            TextView url = body("or open  " + address);
            url.setTextColor(0xB3FFFFFF);
            url.setPadding(0, dp(18), 0, 0);
            left.addView(url);
        }
        left.addView(kicker("PIN"));
        ((LinearLayout.LayoutParams) left.getChildAt(left.getChildCount() - 1).getLayoutParams()).topMargin = dp(28);
        pinView = text(spaced(server.pin()), 44, Color.WHITE, "sans-serif-light");
        pinView.setLetterSpacing(0.1f);
        left.addView(pinView);
        status = body(settings.ready() ? "Already set up. Back keeps the current settings." : "Waiting for your phone…");
        status.setTextColor(ACCENT);
        status.setPadding(0, dp(24), 0, 0);
        left.addView(status);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        if (address != null) {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(qr(address, dp(300)));
            qr.setPadding(dp(18), dp(18), dp(18), dp(18));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(24));
            qr.setBackground(bg);
            LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(336), dp(336));
            qp.leftMargin = dp(48);
            row.addView(qr, qp);
        } else {
            status.setText("The TV has no home network address, so the phone form can't open. Use install.sh from a computer instead.");
        }
        show(row);
    }

    @Override public void phoneConnected() { main.post(() -> status.setText("Phone connected. Fill in the form…")); }

    @Override public void checking() { main.post(() -> status.setText("Checking your server…")); }

    @Override public void newPin(String pin) { main.post(() -> pinView.setText(spaced(pin))); }

    @Override
    public void saved(List<String> list) {
        main.post(() -> {
            server.stop();
            models = list;
            modelStep();
        });
    }

    private void loadModelsThenPick() {
        status.setText("Checking your server…");
        new Thread(() -> {
            try {
                List<String> list = Llm.models(settings);
                main.post(() -> {
                    models = list;
                    modelStep();
                });
            } catch (IOException e) {
                main.post(() -> status.setText("Can't reach your server: " + e.getMessage()
                        + ". Fix it from the phone form."));
            }
        }).start();
    }

    // ---- step 2: model ------------------------------------------------------------------------------

    private void modelStep() {
        step = 2;
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(80), dp(48), dp(80), dp(40));
        LinearLayout left = column();
        left.addView(kicker("Step 2 of 4"));
        left.addView(title("Pick the default model"));
        left.addView(body(models.size() + " models on your server. A fast one feels best for talking.\n\n"
                + "Switch any time: ▲ then Model in Ask, or just say \"use " + shortName(models.get(0)) + "\"."));
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = column();
        View focus = null;
        for (String m : models) {
            TextView r = choice(m, m.equals(settings.model()));
            r.setOnClickListener(v -> {
                settings.model(m);
                permissionsStep();
            });
            list.addView(r);
            if (focus == null || m.equals(settings.model())) focus = r;
        }
        sv.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(440), -1);
        lp.leftMargin = dp(40);
        row.addView(sv, lp);
        show(row);
        View f = focus;
        f.post(f::requestFocus);
    }

    // ---- step 3: permissions ------------------------------------------------------------------------

    private void permissionsStep() {
        step = 3;
        List<String> ask = new ArrayList<>();
        for (String p : new String[] {Manifest.permission.RECORD_AUDIO, "android.permission.READ_TV_LISTINGS"}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        }
        if (ask.isEmpty()) {
            micStep();
            return;
        }
        LinearLayout c = page("Step 3 of 4", "Allow the microphone",
                "The TV asks next. The microphone is used only while Ask is listening; TV listings let Ask "
                        + "resume what you were watching in Stremio.");
        show(c);
        main.postDelayed(() -> requestPermissions(ask.toArray(new String[0]), PERMISSIONS), 600);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == PERMISSIONS) micStep();
    }

    // ---- step 4: mic button -------------------------------------------------------------------------

    private void micStep() {
        step = 4;
        boolean on = isAssistant();
        LinearLayout c = page("Step 4 of 4", on ? "The mic button is ready ✓" : "Make the mic button open Ask",
                on ? "Press the mic button on the remote in any app and Ask comes up over it. If the TV asks which "
                        + "app to use the first time, pick Ask, then Always."
                        : "Ask has to be the TV's assistant. Most TVs only allow that from a computer:\n"
                        + "install.sh with VOICE_KEY=1, or\nadb shell cmd role add-role-holder android.app.role.ASSISTANT "
                        + getPackageName() + " 0\nThe first mic press then asks which app to use: pick Ask, then Always.");
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(28), 0, 0);
        TextView next = action(on ? "Finish" : "Skip for now", v -> doneStep());
        if (!on) {
            TextView open = action("Open Assistant settings", v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS));
                } catch (RuntimeException e) {
                    status("This TV hides that screen. Use the command above from a computer.");
                }
            });
            buttons.addView(open);
            open.post(open::requestFocus);
        } else {
            next.post(next::requestFocus);
        }
        buttons.addView(next);
        c.addView(buttons);
        show(c);
    }

    private boolean isAssistant() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                return rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT);
            }
        }
        String a = android.provider.Settings.Secure.getString(getContentResolver(), "assistant");
        return a != null && a.startsWith(getPackageName() + "/");
    }

    // ---- done ---------------------------------------------------------------------------------------

    private void doneStep() {
        step = 5;
        LinearLayout c = page("All set", "Press the mic button and ask",
                "\"Put on The Office, season 3 episode 2\"\n\"Play some lofi on YouTube\"\n\"Will it rain tomorrow?\"\n"
                        + "\"Remind me in 20 minutes to take the pizza out\"\n\"Who won the match last night?\"\n"
                        + "\"Switch to HDMI 1\"   ·   \"Turn it off\"");
        TextView go = action("Try it now", v -> {
            startActivity(new Intent(this, ChatActivity.class));
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(28);
        c.addView(go, lp);
        show(c);
        go.post(go::requestFocus);
    }

    // ---- views --------------------------------------------------------------------------------------

    private void show(View v) {
        stage.removeAllViews();
        v.setAlpha(0f);
        v.setTranslationX(dp(24));
        stage.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.animate().alpha(1f).translationX(0).setDuration(220).start();
    }

    private LinearLayout page(String kicker, String title, String text) {
        LinearLayout c = column();
        c.setPadding(dp(80), dp(64), dp(240), dp(48));
        c.addView(kicker(kicker));
        c.addView(title(title));
        c.addView(body(text));
        status = body("");
        status.setTextColor(ACCENT);
        c.addView(status);
        return c;
    }

    private void status(String s) { if (status != null) status.setText(s); }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView kicker(String s) {
        TextView t = text(s.toUpperCase(), 14, ACCENT, "sans-serif-medium");
        t.setLetterSpacing(0.16f);
        return t;
    }

    private TextView title(String s) {
        TextView t = text(s, 38, Color.WHITE, "sans-serif-light");
        t.setPadding(0, dp(6), 0, dp(14));
        return t;
    }

    private TextView body(String s) {
        TextView t = text(s, 19, 0xD9FFFFFF, "sans-serif");
        t.setLineSpacing(0, 1.25f);
        return t;
    }

    private TextView choice(String label, boolean current) {
        TextView r = text((current ? "✓  " : "") + label, 20, Color.WHITE, "sans-serif");
        r.setPadding(dp(18), dp(12), dp(18), dp(12));
        r.setFocusable(true);
        r.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x14FFFFFF);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Color.WHITE : 0x14FFFFFF);
            r.setTextColor(has ? Color.BLACK : Color.WHITE);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private TextView action(String label, View.OnClickListener click) {
        TextView b = text(label, 18, Color.WHITE, "sans-serif-medium");
        b.setPadding(dp(24), dp(12), dp(24), dp(12));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(0x26FFFFFF);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? ACCENT : 0x26FFFFFF);
            b.setTextColor(has ? 0xFF05201E : Color.WHITE);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String s, int sp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private static String spaced(String pin) { return pin.substring(0, 3) + " " + pin.substring(3); }

    private static String shortName(String model) {
        String s = model.split("[-.]")[0];
        return s.isEmpty() ? model : s;
    }

    private static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF061314 : Color.WHITE;
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (WriterException e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
