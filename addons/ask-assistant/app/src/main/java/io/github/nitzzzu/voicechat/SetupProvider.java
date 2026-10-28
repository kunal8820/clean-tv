package io.github.nitzzzu.voicechat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/**
 * Setup over ADB, so keys are never typed with a remote and never sit in an exported activity's extras:
 *
 *   content call --uri content://io.github.nitzzzu.voicechat.setup --method set \
 *       --extra url:s:https://litellm.example --extra key:s:sk-... [--extra model:s:...] [--extra search:s:...]
 *   content call --uri content://io.github.nitzzzu.voicechat.setup --method get
 *
 * Only the shell (ADB) may call it. "get" never hands a key back, only whether one is set.
 */
public class SetupProvider extends ContentProvider {
    /** Process.SHELL_UID and ROOT_UID, which the SDK only names from API 29. */
    private static final int SHELL_UID = 2000, ROOT_UID = 0;

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != ROOT_UID) {
            throw new SecurityException("Set up over ADB only");
        }
        Settings s = new Settings(getContext());
        if ("set".equals(method) && extras != null) {
            for (String name : Settings.SETUP) {
                if (extras.containsKey(name)) s.put(name, String.valueOf(extras.get(name)));
            }
        } else if ("ask".equals(method) && arg != null && !arg.trim().isEmpty()) {
            // Typed instead of spoken, for testing and scripts: the shell's question, asked as if heard.
            s.pendingAsk(arg.trim());
            getContext().startActivity(new android.content.Intent(getContext(), ChatActivity.class)
                    .setAction(ChatActivity.ACTION_ASK_PENDING)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
            Bundle out = new Bundle();
            out.putString("asked", arg.trim());
            return out;
        } else if ("backup".equals(method)) {
            // Try another provider and come back: "backup", "set" the other one, later "restore".
            s.backup();
        } else if ("restore".equals(method)) {
            if (!s.restore()) throw new IllegalStateException("No backup to restore");
        } else if ("forget".equals(method)) {
            s.facts(new java.util.ArrayList<>());
        } else if (!"get".equals(method)) {
            return null;
        }
        Bundle out = new Bundle();
        out.putString("url", s.url());
        out.putBoolean("key_set", !s.key().isEmpty());
        out.putString("model", s.model());
        out.putString("language", s.language());
        out.putString("search", s.search());
        out.putBoolean("ntfy_set", !s.ntfy().isEmpty());
        out.putInt("hooks", Hooks.parse(s.hooks()).size());
        out.putInt("facts", s.facts().size());
        out.putInt("routines", Routines.all(s).size());
        return out;
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
