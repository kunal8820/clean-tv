package io.github.ptrinh.nowplaying;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

/**
 * Which app is on screen, from Android's usage events, so the pop-up stays away while the playing app is
 * the one being watched. Needs usage access (install.sh: appops set … GET_USAGE_STATS allow); without it
 * nothing is known and the pop-up shows for every new track.
 */
final class Foreground {
    private Foreground() {
    }

    @SuppressWarnings("deprecation")   // unsafeCheckOpNoThrow is Android 10+; this one works on 8 too
    static boolean allowed(Context ctx) {
        try {
            AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                    ctx.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** The package of the activity that came to the front last (in the past hour), or null when unknown. */
    @SuppressWarnings("deprecation")   // MOVE_TO_FOREGROUND is ACTIVITY_RESUMED's old name, the one Android 8 has
    static String top(Context ctx) {
        if (!allowed(ctx)) return null;
        try {
            UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 3_600_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String top = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) top = e.getPackageName();
            }
            return top;
        } catch (Exception e) {
            return null;
        }
    }
}
