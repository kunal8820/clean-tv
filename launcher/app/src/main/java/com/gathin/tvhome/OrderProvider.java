package com.gathin.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets tools/order.py read and set the Home tile order (order.txt, see {@link Order}) from a computer, so a long
 * list does not have to be carried tile by tile with the remote. Only for callers holding WRITE_SECURE_SETTINGS,
 * which the adb shell has and ordinary apps cannot get (as {@link ThemeProvider}).
 *
 *   content call --uri content://com.gathin.tvhome.order --method get
 *   content call --uri content://com.gathin.tvhome.order --method set --arg 'com.google.android.youtube.tv,com.netflix.ninja'
 *   content call --uri content://com.gathin.tvhome.order --method clear
 *
 * get answers the saved order, or under "stale" one saved under other dock_apps (which Home ignores), and the name
 * of each saved tile and TV app under "labels", so the list can be read without knowing package names.
 * set takes package names or components, one per line or comma-separated; a package stands for its TV launcher
 * activity. The order is saved under the current dock_apps, as the reorder button saves it, and Home reloads.
 */
public class OrderProvider extends ContentProvider {

    static final String METHOD_GET = "get";
    static final String METHOD_SET = "set";
    static final String METHOD_CLEAR = "clear";
    /**
     * Result keys: the saved tile keys, one per line; a saved order that no longer applies; "key TAB name" per line
     * for the saved tiles and every TV app; how many were saved; the entries that matched no app.
     */
    static final String KEY_ORDER = "order";
    static final String KEY_STALE = "stale";
    static final String KEY_LABELS = "labels";
    static final String KEY_SAVED = "saved";
    static final String KEY_UNKNOWN = "unknown";

    static Uri uri(Context ctx) {
        return Uri.parse("content://" + ctx.getPackageName() + ".order");
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        // A provider's android:permission does not cover call(): check it here.
        if (ctx == null || ctx.checkCallingPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("WRITE_SECURE_SETTINGS required");
        }
        List<String> dockApps = Config.load(ctx).dockApps;
        Bundle out = new Bundle();
        if (METHOD_GET.equals(method)) {
            Order saved = Order.load(Order.file(ctx));
            boolean applies = saved != null && saved.dock.equals(Order.dockKey(dockApps));
            out.putString(KEY_ORDER, applies ? String.join("\n", saved.keys) : "");
            out.putString(KEY_STALE, saved != null && !applies ? String.join("\n", saved.keys) : "");
            out.putString(KEY_LABELS, labels(ctx.getPackageManager(), saved == null ? null : saved.keys));
            return out;
        }
        if (METHOD_CLEAR.equals(method)) {
            boolean gone = !Order.file(ctx).exists() || Order.file(ctx).delete();
            if (gone) ctx.getContentResolver().notifyChange(uri(ctx), null);
            out.putInt(KEY_SAVED, 0);
            return out;
        }
        if (!METHOD_SET.equals(method)) throw new IllegalArgumentException("method: get, set or clear");

        List<String> keys = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String entry : Order.entries(arg)) {
            String key = tileKey(ctx.getPackageManager(), entry);
            if (key == null) unknown.add(entry);
            else if (!keys.contains(key)) keys.add(key);
        }
        if (keys.isEmpty()) throw new IllegalArgumentException("no installed TV app in the list");
        if (!Order.save(Order.file(ctx), new Order(Order.dockKey(dockApps), keys))) {
            throw new IllegalStateException("order.txt could not be written");
        }
        ctx.getContentResolver().notifyChange(uri(ctx), null);
        out.putInt(KEY_SAVED, keys.size());
        out.putString(KEY_UNKNOWN, String.join("\n", unknown));
        return out;
    }

    /** "key TAB name" per line: the saved keys still installed, then every TV launcher activity not among them. */
    private static String labels(PackageManager pm, List<String> savedKeys) {
        Map<String, String> names = new LinkedHashMap<>();
        if (savedKeys != null) {
            for (String key : savedKeys) {
                ComponentName c = ComponentName.unflattenFromString(key);
                if (c == null) continue;
                try {
                    names.put(key, String.valueOf(pm.getActivityInfo(c, 0).loadLabel(pm)));
                } catch (PackageManager.NameNotFoundException e) {
                    // uninstalled since: the launcher drops it too
                }
            }
        }
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String key = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name).flattenToShortString();
            if (!names.containsKey(key)) names.put(key, String.valueOf(ri.loadLabel(pm)));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            // One line per app whatever the name holds.
            sb.append(e.getKey()).append('\t').append(e.getValue().replaceAll("[\\t\\r\\n]+", " ").trim());
        }
        return sb.toString();
    }

    /** The tile key (component, flattened short) for a package or component, or null when no such app is installed. */
    private static String tileKey(PackageManager pm, String entry) {
        if (entry.contains("/")) {
            ComponentName c = ComponentName.unflattenFromString(entry);
            if (c == null) return null;
            try {
                pm.getActivityInfo(c, 0);
                return c.flattenToShortString();
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(entry);
            List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
            if (!found.isEmpty()) {
                return new ComponentName(found.get(0).activityInfo.packageName, found.get(0).activityInfo.name)
                        .flattenToShortString();
            }
        }
        return null;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
