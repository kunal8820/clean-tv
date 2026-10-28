package io.github.nitzzzu.voicechat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * What install.sh sets through SetupProvider (the LiteLLM address and key, the model, SearXNG, ntfy, webhooks, the
 * speech language) and what the chat keeps on its own (the chosen model, voice replies, remembered facts).
 */
final class Settings {
    static final String URL = "url", KEY = "key", MODEL = "model", LANGUAGE = "language", PROMPT = "prompt",
            SEARCH = "search", NTFY = "ntfy", NTFY_TOKEN = "ntfy_token", HOOKS = "hooks", ROUTINES = "routines";
    static final String[] SETUP = {URL, KEY, MODEL, LANGUAGE, PROMPT, SEARCH, NTFY, NTFY_TOKEN, HOOKS, ROUTINES};
    private static final String VOICE = "voice", FACTS = "facts";
    private static final int MAX_FACTS = 40;

    private final SharedPreferences prefs;

    Settings(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    boolean ready() { return !url().isEmpty() && !key().isEmpty(); }

    /** The OpenAI-compatible base, always ending in /v1: "https://litellm.example" and ".../v1/" both work. */
    String url() {
        String u = get(URL).replaceAll("/+$", "");
        if (u.isEmpty()) return u;
        return u.endsWith("/v1") ? u : u + "/v1";
    }

    String key() { return get(KEY); }

    String model() { return get(MODEL); }

    void model(String m) { prefs.edit().putString(MODEL, m).apply(); }

    /** A BCP-47 tag ("de-DE") for the recognizer and the voice, or empty for the TV's own language. */
    String language() { return get(LANGUAGE); }

    /** Extra instructions appended to the built-in prompt, or empty. */
    String prompt() { return get(PROMPT); }

    /** A SearXNG base URL with the JSON format on, or empty: then there is no web search. */
    String search() { return get(SEARCH).replaceAll("/+$", ""); }

    /** An ntfy topic URL ("https://ntfy.example/tv"), or empty: then nothing is sent to the phone. */
    String ntfy() { return get(NTFY); }

    String ntfyToken() { return get(NTFY_TOKEN); }

    /** Webhooks offered as tools: a JSON array of {"name", "description", "url"}. */
    String hooks() { return get(HOOKS); }

    /** Routines as a JSON object, name to request (Routines). */
    String routines() { return get(ROUTINES); }

    /** A typed question from the ADB shell (SetupProvider "ask"), taken once, within a minute. */
    void pendingAsk(String text) {
        prefs.edit().putString("pending_ask", text).putLong("pending_at", System.currentTimeMillis()).apply();
    }

    String takePendingAsk() {
        String t = prefs.getString("pending_ask", "");
        long at = prefs.getLong("pending_at", 0);
        prefs.edit().remove("pending_ask").remove("pending_at").apply();
        return t != null && !t.isEmpty() && System.currentTimeMillis() - at < 60000 ? t : null;
    }

    boolean voice() { return prefs.getBoolean(VOICE, true); }

    void voice(boolean on) { prefs.edit().putBoolean(VOICE, on).apply(); }

    List<String> facts() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(FACTS, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException e) {
            // start over
        }
        return out;
    }

    void facts(List<String> list) {
        JSONArray a = new JSONArray();
        for (int i = Math.max(0, list.size() - MAX_FACTS); i < list.size(); i++) a.put(list.get(i));
        prefs.edit().putString(FACTS, a.toString()).apply();
    }

    /** Keeps the server settings aside (to try another provider) and puts them back. Keys never leave the app. */
    void backup() {
        SharedPreferences.Editor e = prefs.edit();
        for (String n : new String[] {URL, KEY, MODEL}) e.putString("backup_" + n, get(n));
        e.apply();
    }

    boolean restore() {
        if (prefs.getString("backup_" + URL, "").isEmpty()) return false;
        SharedPreferences.Editor e = prefs.edit();
        for (String n : new String[] {URL, KEY, MODEL}) e.putString(n, prefs.getString("backup_" + n, ""));
        for (String n : new String[] {URL, KEY, MODEL}) e.remove("backup_" + n);
        e.apply();
        return true;
    }

    void put(String name, String value) {
        if (value == null) return;
        prefs.edit().putString(name, value.trim()).apply();
    }

    private String get(String name) {
        String v = prefs.getString(name, "");
        return v == null ? "" : v.trim();
    }
}
