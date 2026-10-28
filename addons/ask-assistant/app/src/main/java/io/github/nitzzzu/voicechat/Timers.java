package io.github.nitzzzu.voicechat;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Timers and reminders: an alarm per timer, and at the time a full-screen reminder that says the label out loud
 * over whatever is playing (ReminderActivity). Kept until they ring; a reboot clears them.
 */
final class Timers {
    static final String EXTRA_ID = "id", EXTRA_LABEL = "label", EXTRA_OFF = "off";
    private static final String PREFS = "timers";

    private Timers() {}

    static String set(Context ctx, double minutes, String label, boolean turnOff) {
        if (minutes <= 0 || minutes > 24 * 60) return "Say how many minutes, up to a day.";
        long at = System.currentTimeMillis() + Math.round(minutes * 60000);
        int id = (int) (at / 1000 % 1_000_000_000);
        String text = turnOff ? "Turning off." : label.trim().isEmpty() ? "Your " + Tools.fmtMinutes(minutes) + " timer is done." : label.trim();
        if (turnOff && !KeyService.connected()) return "A sleep timer needs Ask's accessibility service, which is off.";
        JSONArray all = load(ctx);
        try {
            all.put(new JSONObject().put("id", id).put("at", at).put("label", text).put("off", turnOff));
        } catch (JSONException e) {
            return "Couldn't set the timer.";
        }
        save(ctx, all);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(ctx, id, text, turnOff);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
        return (turnOff ? "The TV turns off at " : "Timer set for ") + clock(at) + " (" + Tools.fmtMinutes(minutes) + ")"
                + (turnOff ? "." : ": \"" + text + "\".");
    }

    static String list(Context ctx) {
        JSONArray all = load(ctx);
        if (all.length() == 0) return "No timers are set.";
        StringBuilder b = new StringBuilder();
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            long left = (t.optLong("at") - now) / 60000;
            b.append("- ").append(clock(t.optLong("at"))).append(" (in ").append(Math.max(0, left)).append(" min): ")
                    .append(t.optString("label")).append('\n');
        }
        return b.toString().trim();
    }

    static String cancelAll(Context ctx) {
        JSONArray all = load(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            am.cancel(pending(ctx, t.optInt("id"), t.optString("label"), t.optBoolean("off")));
        }
        save(ctx, new JSONArray());
        return all.length() == 0 ? "There were no timers." : "Cancelled " + all.length() + " timer(s).";
    }

    static void done(Context ctx, int id) {
        JSONArray all = load(ctx), keep = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            if (t.optInt("id") != id && t.optLong("at") > now - 60000) keep.put(t);
        }
        save(ctx, keep);
    }

    private static PendingIntent pending(Context ctx, int id, String label, boolean turnOff) {
        Intent i = new Intent(ctx, Ring.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_LABEL, label).putExtra(EXTRA_OFF, turnOff);
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String clock(long at) { return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(at)); }

    private static JSONArray load(Context ctx) {
        try {
            return new JSONArray(prefs(ctx).getString("all", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void save(Context ctx, JSONArray all) { prefs(ctx).edit().putString("all", all.toString()).apply(); }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The alarm: brings up the reminder over whatever is on screen. */
    public static final class Ring extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int id = intent.getIntExtra(EXTRA_ID, 0);
            done(ctx, id);
            if (intent.getBooleanExtra(EXTRA_OFF, false) && KeyService.sleep()) return;
            Intent show = new Intent(ctx, ReminderActivity.class)
                    .putExtra(EXTRA_LABEL, intent.getStringExtra(EXTRA_LABEL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            try {
                ctx.startActivity(show);
            } catch (RuntimeException e) {
                // Background starts refused (no "display over other apps"): nothing else can show it.
            }
        }
    }
}
