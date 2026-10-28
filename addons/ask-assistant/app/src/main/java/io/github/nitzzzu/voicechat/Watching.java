package io.github.nitzzzu.voicechat;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stremio's Continue Watching, read from the preview channel Stremio publishes to the TV provider (the same rows the
 * stremio-continue add-on shows; docs/watch-next.md). Needs READ_TV_LISTINGS, granted by install.sh.
 */
final class Watching {
    private static final class Item {
        String title, subtitle, intent;
        long position, duration;
        int weight;
        long id;
    }

    private Watching() {}

    /** The titles in Continue Watching, for matching misheard names; empty when they can't be read. */
    static List<String> titles(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            for (Item it : read(ctx)) out.add(it.title);
        } catch (SecurityException e) {
            // no access: no hints
        }
        return out;
    }

    static String list(Context ctx) {
        List<Item> items;
        try {
            items = read(ctx);
        } catch (SecurityException e) {
            return "Can't read Continue Watching: the READ_TV_LISTINGS permission was not granted.";
        }
        if (items.isEmpty()) return "Nothing in Stremio's Continue Watching.";
        StringBuilder b = new StringBuilder("Stremio, Continue Watching:\n");
        for (Item it : items) {
            b.append("- ").append(it.title);
            if (it.subtitle != null) b.append(" (").append(it.subtitle).append(")");
            if (it.duration > 0 && it.position > 0) {
                b.append(", ").append(Math.max(1, (it.duration - it.position) / 60000)).append(" min left");
            }
            b.append('\n');
        }
        return b.toString().trim();
    }

    static Tools.Result resume(Context ctx, String title, Tools tools) {
        List<Item> items;
        try {
            items = read(ctx);
        } catch (SecurityException e) {
            return Tools.ok("Can't read Continue Watching: the READ_TV_LISTINGS permission was not granted.");
        }
        Map<String, Item> byTitle = new LinkedHashMap<>();
        for (Item it : items) {
            byTitle.put(it.title + (it.subtitle != null ? " " + it.subtitle : ""), it);
        }
        Item it = title.trim().isEmpty() && !items.isEmpty() ? items.get(0) : Tools.match(byTitle, title);
        // Misheard ("severns"): the closest-sounding title on the list, if it is close enough.
        if (it == null) {
            double best = 0;
            for (Item i : items) {
                double s = Fuzzy.score(title, i.title);
                if (s > best) {
                    best = s;
                    it = s >= 0.7 ? i : it;
                }
            }
        }
        if (it == null) return Tools.ok("Nothing called " + title + " in Continue Watching.\n" + list(ctx));
        try {
            Intent i = Intent.parseUri(it.intent, Intent.URI_INTENT_SCHEME);
            i.setSelector(null);
            i.setPackage(Online.STREMIO);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (tools.startAny(i)) return Tools.leave("Opened " + it.title + " in Stremio.");
        } catch (URISyntaxException e) {
            // fall through
        }
        return Tools.ok("Stremio didn't open " + it.title + ".");
    }

    private static List<Item> read(Context ctx) {
        List<Item> out = new ArrayList<>();
        // No selection: without ACCESS_ALL_EPG_DATA the provider throws on one, so filter in code.
        try (Cursor c = ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) return out;
            int pkg = c.getColumnIndex("package_name");
            while (c.moveToNext()) {
                if (pkg >= 0 && !Online.STREMIO.equals(c.getString(pkg))) continue;
                Item it = new Item();
                it.id = num(c, "_id");
                it.title = str(c, TvContract.PreviewPrograms.COLUMN_TITLE);
                it.intent = str(c, TvContract.PreviewPrograms.COLUMN_INTENT_URI);
                it.position = num(c, TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                it.duration = num(c, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS);
                it.weight = (int) num(c, TvContract.PreviewPrograms.COLUMN_WEIGHT);
                String s = str(c, TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
                String e = str(c, TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
                String t = str(c, TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE);
                StringBuilder sub = new StringBuilder();
                if (s != null) sub.append("season ").append(s);
                if (e != null) sub.append(sub.length() > 0 ? " " : "").append("episode ").append(e);
                if (t != null) sub.append(sub.length() > 0 ? ", " : "").append(t);
                it.subtitle = sub.length() > 0 ? sub.toString() : null;
                if (it.title != null && it.intent != null) out.add(it);
            }
        }
        out.sort((a, b) -> a.weight != b.weight ? Integer.compare(b.weight, a.weight) : Long.compare(a.id, b.id));
        return out.size() > 15 ? out.subList(0, 15) : out;
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return null;
        String v = c.getString(i);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static long num(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
