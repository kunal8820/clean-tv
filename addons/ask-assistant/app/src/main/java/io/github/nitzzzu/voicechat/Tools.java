package io.github.nitzzzu.voicechat;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * What the assistant can do on the TV. Each tool is a JSON-schema definition for the model and a method here.
 * Tools that bring another app to the front ("leaves") end the turn: the panel gets out of the way.
 */
final class Tools {
    static final class Result {
        final String text;
        final boolean leaves;

        Result(String text, boolean leaves) {
            this.text = text;
            this.leaves = leaves;
        }
    }

    /** Tools that bring another screen forward; a turn that has one of these and open_app skips open_app. */
    static final Set<String> LEAVING = new LinkedHashSet<>(java.util.Arrays.asList(
            "open_app", "youtube", "stremio", "resume_watching", "switch_input", "open_settings", "go_home",
            "click_on_screen", "go_back", "play_stream"));

    private final Context ctx;
    private final Settings settings;
    private final Online online;
    private Map<String, Intent> apps;
    private volatile List<String> models = new ArrayList<>();

    Tools(Context ctx, Settings settings) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
        this.online = new Online(ctx, settings);
    }

    /** What the user said this turn, as recognised: the check on a title the model "corrected". */
    private volatile String utterance = "";

    void utterance(String said) { utterance = said == null ? "" : said; }

    /** The server's models, for use_model. */
    void models(List<String> list) { models = new ArrayList<>(list); }

    // ---- definitions --------------------------------------------------------------------------------

    JSONArray definitions() {
        JSONArray t = new JSONArray();
        def(t, "open_app", "Open an app installed on the TV by its name (see the app list).",
                prop("name", "string", "The app's name, as in the app list"), "name");
        def(t, "go_home", "Go to the TV's home screen.");
        def(t, "youtube", "Find a video on YouTube and play it in the TV's YouTube app (opens the app itself; do not "
                        + "also call open_app). Use for music, trailers, clips, how-tos. A YouTube link (video or "
                        + "playlist) is opened as it is.",
                prop("query", "string", "What to search for, or a YouTube link"), "query");
        def(t, "stremio", "Open a film or a series in Stremio, at the episode when season and episode are given; "
                        + "the user picks a stream with the remote. Call it for every watch/play/put-on request for a film "
                        + "or series, at once, even if the words don't look like a title. The title came through speech "
                        + "recognition and may be misheard: pass the real title you think was meant (\"breaking bat\" is "
                        + "Breaking Bad); unclear ones come back as candidates to ask about.",
                props(prop("title", "string", "The film or series title, corrected if it was clearly misheard"),
                        prop("heard", "string", "The title words exactly as they were heard, before any correction"),
                        prop("imdb_id", "string", "An IMDb id (tt…) from a candidate list, to open exactly that title"),
                        enumProp("type", "movie or series, when known", "movie", "series"),
                        prop("season", "integer", "Season number"),
                        prop("episode", "integer", "Episode number")), "title");
        def(t, "continue_watching", "List what the user was watching in Stremio and how far they got.");
        def(t, "resume_watching", "Resume something from the continue-watching list in Stremio; the user picks a "
                        + "stream with the remote.", prop("title", "string", "The title to resume"), "title");
        def(t, "media", "Control what is playing in any app.",
                enumProp("action", "The playback action", "play", "pause", "toggle", "next", "previous", "stop",
                        "forward", "rewind"), "action");
        def(t, "volume", "Change the TV's volume.",
                props(enumProp("action", "up, down, set, mute or unmute", "up", "down", "set", "mute", "unmute"),
                        prop("level", "integer", "0 to 100, for set")), "action");
        def(t, "switch_input", "Switch the TV to an HDMI input (see the input list).",
                prop("name", "string", "The input, e.g. HDMI 1"), "name");
        def(t, "open_settings", "Open a settings screen on the TV.",
                enumProp("page", "Which screen", "main", "wifi", "bluetooth", "display", "sound", "apps", "date",
                        "accessibility", "about", "screensaver"), "page");
        if (!settings.search().isEmpty()) {
            def(t, "web_search", "Search the web for current facts: news, sports results, prices, opening hours, "
                    + "anything after your training or that you are unsure of.", prop("query", "string", "The search"), "query");
            def(t, "read_page", "Read a web page from a search result, for details.",
                    prop("url", "string", "The page address"), "url");
        }
        def(t, "weather", "The weather now and for the next days at a place.",
                props(prop("place", "string", "City or place name"), prop("days", "integer", "Days from today, 1 to 7: tomorrow needs 2")), "place");
        def(t, "set_timer", "Set a timer or reminder: the TV says the label out loud when it is time, over "
                        + "whatever is playing.",
                props(prop("minutes", "number", "Minutes from now"), prop("label", "string", "What to say"),
                        prop("turn_off", "boolean", "true for a sleep timer: turn the TV off at that time")), "minutes");
        def(t, "list_timers", "List the timers that are set.");
        def(t, "cancel_timers", "Cancel all timers.");
        if (!settings.ntfy().isEmpty()) {
            def(t, "send_to_phone", "Send a message or a link to the user's phone as a notification.",
                    props(prop("message", "string", "The text"), prop("link", "string", "An optional URL to open")),
                    "message");
        }
        if (models.size() > 1) {
            // A long list (OpenRouter has hundreds) would weigh on every request: then the name is matched instead.
            def(t, "use_model", "Switch the AI model used from the next question on."
                            + (models.size() <= 25 ? " Models: " + String.join(", ", models) + "." : ""),
                    prop("name", "string", "The model, or words from its name"), "name");
        }
        def(t, "save_routine", "Save a routine: a short name the user will say, and the request it runs, in the "
                        + "user's words with every detail (links, volumes, minutes). Replaces one with the same name.",
                props(prop("name", "string", "What the user will say, e.g. bed time"),
                        prop("steps", "string", "The request to run, as one instruction")), "name", "steps");
        def(t, "delete_routine", "Delete a routine by name.", prop("name", "string", "The routine's name"), "name");
        def(t, "list_routines", "List the saved routines and what each does.");
        def(t, "remember", "Remember a fact about the household for later conversations.",
                prop("fact", "string", "One short sentence"), "fact");
        def(t, "forget", "Forget remembered facts that match.", prop("about", "string", "Words in the fact"), "about");
        if (KeyService.connected()) {
            def(t, "go_back", "Press Back on the TV, in whatever app is open.");
            def(t, "play_stream", "Only when the user asks for a stream by its place in Stremio's list (\"play the "
                            + "second one\"): presses that stream, counting from the left.",
                    prop("number", "integer", "The stream's number, 1 for the first"), "number");
            def(t, "click_on_screen", "Press a button, tab, stream or item in the app behind this panel, by the text "
                            + "it shows (read_screen first when you don't know it). E.g. \"Settings\", \"4.33 GB\", \"Episode 3\".",
                    prop("target", "string", "Words of the element's text"), "target");
            def(t, "turn_off", "Put the projector to sleep (turn it off).");
            def(t, "read_screen", "Read the text on the screen of the app behind this panel, only when the user asks "
                    + "about what is on screen.");
        }
        for (Hooks.Hook h : Hooks.parse(settings.hooks())) {
            def(t, h.tool(), h.description, prop("input", "string", "Optional text to pass along"));
        }
        return t;
    }

    Set<String> names() {
        Set<String> out = new LinkedHashSet<>();
        JSONArray d = definitions();
        for (int i = 0; i < d.length(); i++) out.add(d.optJSONObject(i).optJSONObject("function").optString("name"));
        return out;
    }

    /** What the model should know every turn: the time, the apps, the inputs, what is on screen, the facts. */
    String context() {
        StringBuilder b = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH);
        b.append("Now: ").append(f.format(new Date())).append(" (").append(TimeZone.getDefault().getID()).append(").\n");
        b.append("Apps on the TV: ").append(String.join(", ", apps().keySet())).append(".\n");
        List<String> inputs = inputLabels();
        if (!inputs.isEmpty()) b.append("Inputs: ").append(String.join(", ", inputs)).append(".\n");
        List<String> watching = Watching.titles(ctx);
        if (!watching.isEmpty()) {
            b.append("In Stremio's Continue Watching (only for a title that sounds like one of these): ")
                    .append(String.join(", ", watching)).append(".\n");
        }
        String front = KeyService.frontApp();
        if (front != null) b.append("Behind this panel the user has: ").append(label(front)).append(".\n");
        Map<String, String> routines = Routines.all(settings);
        if (!routines.isEmpty()) b.append("Routines (saying a name runs it): ").append(String.join(", ", routines.keySet())).append(".\n");
        List<String> facts = settings.facts();
        if (!facts.isEmpty()) {
            b.append("Things you were asked to remember:\n");
            for (String s : facts) b.append("- ").append(s).append('\n');
        }
        return b.toString();
    }

    /** A few words for the chip shown while the tool runs. */
    String chip(String name, JSONObject a) {
        switch (name) {
            case "open_app": return "Opening " + a.optString("name");
            case "go_home": return "Going home";
            case "youtube": return "YouTube · " + a.optString("query");
            case "stremio": return "Stremio · " + a.optString("title");
            case "continue_watching": return "Checking Continue Watching";
            case "resume_watching": return "Resuming " + a.optString("title");
            case "media": return "Playback · " + a.optString("action");
            case "volume": return "Volume · " + a.optString("action") + (a.has("level") ? " " + a.optInt("level") : "");
            case "switch_input": return "Switching to " + a.optString("name");
            case "open_settings": return "Settings · " + a.optString("page");
            case "web_search": return "Searching · " + a.optString("query");
            case "read_page": return "Reading " + Uri.parse(a.optString("url")).getHost();
            case "weather": return "Weather · " + a.optString("place");
            case "set_timer": return (a.optBoolean("turn_off") ? "Sleep timer · " : "Timer · ") + fmtMinutes(a.optDouble("minutes", 0));
            case "click_on_screen": return "Pressing " + a.optString("target");
            case "play_stream": return "Playing stream " + a.optInt("number");
            case "send_to_phone": return "Sending to your phone";
            case "remember": return "Remembering";
            case "save_routine": return "Saving routine · " + a.optString("name");
            case "delete_routine": return "Deleting routine · " + a.optString("name");
            case "list_routines": return "Routines";
            case "use_model": return "Switching to " + a.optString("name");
            case "forget": return "Forgetting";
            case "turn_off": return "Turning off";
            case "read_screen": return "Reading the screen";
            default:
                Hooks.Hook h = Hooks.byTool(settings.hooks(), name);
                return h != null ? "Running " + h.name : name.replace('_', ' ');
        }
    }

    // ---- running ------------------------------------------------------------------------------------

    /** Runs one tool, off the main thread. Never throws: a failure is a result the model can explain. */
    Result run(String name, JSONObject a) {
        try {
            switch (name) {
                case "open_app": return openApp(a.optString("name"));
                case "go_home":
                    start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
                    return leave("Home screen opened.");
                case "youtube": return online.youtube(a.optString("query"), this);
                case "stremio": return online.stremio(a.optString("title"), a.optString("type", ""),
                        a.optInt("season", 0), a.optInt("episode", 0), a.optString("imdb_id", ""),
                        a.optString("heard", "").isEmpty() ? utterance : a.optString("heard"), ctx, this);
                case "continue_watching": return ok(Watching.list(ctx));
                case "resume_watching": return Watching.resume(ctx, a.optString("title"), this);
                case "media": return media(a.optString("action"));
                case "volume": return volume(a.optString("action"), a.optInt("level", -1));
                case "switch_input": return switchInput(a.optString("name"));
                case "open_settings": return openSettings(a.optString("page", "main"));
                case "web_search": return ok(online.search(a.optString("query")));
                case "read_page": return ok(online.read(a.optString("url")));
                case "weather": return ok(online.weather(a.optString("place"), a.optInt("days", 3)));
                case "set_timer": return ok(Timers.set(ctx, a.optDouble("minutes", 0), a.optString("label", ""),
                        a.optBoolean("turn_off", false)));
                case "play_stream":
                    return KeyService.clickNth(Online.STREMIO, Online.STREAM_CARD, a.optInt("number", 0))
                            ? leave("Playing stream " + a.optInt("number") + ".")
                            : ok("There is no stream " + a.optInt("number") + " on screen.");
                case "click_on_screen":
                    if (KeyService.connected() && !KeyService.onScreen(KeyService.textMatch(a.optString("target")))) {
                        return ok("Nothing labelled \"" + a.optString("target") + "\" is on screen. Say so in a few words.");
                    }
                    return KeyService.clickWhen(null, KeyService.textMatch(a.optString("target")), 4000)
                            ? leave("Pressing \"" + a.optString("target") + "\".") : ok("Pressing things on screen is not switched on.");
                case "list_timers": return ok(Timers.list(ctx));
                case "cancel_timers": return ok(Timers.cancelAll(ctx));
                case "send_to_phone": return ok(online.ntfy(a.optString("message"), a.optString("link", "")));
                case "remember": return ok(remember(a.optString("fact")));
                case "save_routine": return ok(saveRoutine(a.optString("name"), a.optString("steps")));
                case "delete_routine": return ok(deleteRoutine(a.optString("name")));
                case "list_routines": return ok(listRoutines());
                case "use_model": return ok(useModel(a.optString("name")));
                case "forget": return ok(forget(a.optString("about")));
                case "go_back": return KeyService.back() ? leave("Pressed Back.") : ok("Couldn't press Back.");
                case "turn_off": return KeyService.sleep() ? leave("Turning off.") : ok("Couldn't turn the TV off.");
                case "read_screen": return ok(KeyService.screenText());
                default:
                    Hooks.Hook h = Hooks.byTool(settings.hooks(), name);
                    if (h != null) return ok(online.hook(h, a.optString("input", "")));
                    return ok("There is no tool called " + name + ".");
            }
        } catch (IOException e) {
            return ok("That failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (RuntimeException e) {
            return ok("That failed: " + e.getClass().getSimpleName());
        }
    }

    static Result ok(String text) { return new Result(text, false); }

    static Result leave(String text) { return new Result(text, true); }

    // ---- apps ---------------------------------------------------------------------------------------

    /** Installed apps by label, TV apps first: the same set a launcher shows. */
    synchronized Map<String, Intent> apps() {
        if (apps != null) return apps;
        Map<String, Intent> out = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        PackageManager pm = ctx.getPackageManager();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> list = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            for (ResolveInfo r : list) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName())) continue;
                String label = r.loadLabel(pm).toString().trim();
                if (label.isEmpty() || out.containsKey(label)) continue;
                boolean taken = false;
                for (Intent i : out.values()) if (pkg.equals(i.getComponent().getPackageName())) taken = true;
                if (taken) continue;
                out.put(label, new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(pkg, r.activityInfo.name));
            }
        }
        apps = out;
        return out;
    }

    String label(String pkg) {
        for (Map.Entry<String, Intent> e : apps().entrySet()) {
            if (pkg.equals(e.getValue().getComponent().getPackageName())) return e.getKey();
        }
        return pkg;
    }

    boolean installed(String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private Result openApp(String name) {
        Intent i = match(apps(), name);
        if (i == null) return ok("No app called " + name + ". Installed: " + String.join(", ", apps().keySet()));
        start(i);
        return leave("Opened " + label(i.getComponent().getPackageName()) + ".");
    }

    /** The best entry for a spoken name: exact, then starts-with, then contains, then the most shared words. */
    static <T> T match(Map<String, T> map, String spoken) {
        String s = fold(spoken);
        if (s.isEmpty()) return null;
        T best = null;
        int bestScore = 0;
        for (Map.Entry<String, T> e : map.entrySet()) {
            String k = fold(e.getKey());
            int score = k.equals(s) ? 100 : k.startsWith(s) || s.startsWith(k) ? 60 : k.contains(s) || s.contains(k) ? 40 : 0;
            if (score == 0) {
                for (String w : s.split(" ")) if (w.length() > 2 && k.contains(w)) score += 10;
            }
            if (score > bestScore) {
                bestScore = score;
                best = e.getValue();
            }
        }
        return best;
    }

    static String fold(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    /** Starts the first intent something answers; false when none does. */
    boolean startAny(Intent... intents) {
        for (Intent i : intents) {
            if (i == null) continue;
            try {
                start(i);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                // next
            }
        }
        return false;
    }

    // ---- playback and volume ------------------------------------------------------------------------

    private Result media(String action) {
        int key;
        switch (action) {
            case "play": key = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": key = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "next": key = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": key = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "stop": key = KeyEvent.KEYCODE_MEDIA_STOP; break;
            case "forward": key = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; break;
            case "rewind": key = KeyEvent.KEYCODE_MEDIA_REWIND; break;
            default: key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
        return ok("Sent " + action + " to the player.");
    }

    private Result volume(String action, int level) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int s = AudioManager.STREAM_MUSIC;
        int max = am.getStreamMaxVolume(s);
        switch (action) {
            case "up": am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); break;
            case "down": am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); break;
            case "mute": am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); break;
            case "unmute": am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI); break;
            case "set":
                if (level < 0) return ok("Say which level, 0 to 100.");
                am.setStreamVolume(s, Math.round(Math.min(100, level) * max / 100f), AudioManager.FLAG_SHOW_UI);
                break;
            default: return ok("Unknown volume action " + action + ".");
        }
        return ok("Volume is now " + Math.round(am.getStreamVolume(s) * 100f / max) + " of 100"
                + (am.isStreamMute(s) ? ", muted." : "."));
    }

    // ---- inputs and settings ------------------------------------------------------------------------

    private Map<String, String> inputs() {
        Map<String, String> out = new LinkedHashMap<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.isHidden(ctx)) continue;
                String label = String.valueOf(info.loadLabel(ctx)).trim();
                if (label.isEmpty() || out.containsKey(label)) continue;
                out.put(label, info.getId());
            }
        } catch (RuntimeException e) {
            // no TV input service
        }
        return out;
    }

    private List<String> inputLabels() { return new ArrayList<>(inputs().keySet()); }

    private Result switchInput(String name) {
        Map<String, String> all = inputs();
        String id = match(all, name);
        if (id == null) {
            // "HDMI" alone, or a number: the first passthrough input that carries it.
            for (Map.Entry<String, String> e : all.entrySet()) {
                if (fold(e.getKey()).replace(" ", "").contains(fold(name).replace(" ", ""))) id = e.getValue();
            }
        }
        if (id == null) return ok(all.isEmpty() ? "This TV lists no inputs." : "No input called " + name
                + ". Inputs: " + String.join(", ", all.keySet()));
        Uri uri = TvContract.buildChannelUriForPassthroughInput(id);
        boolean done = startAny(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"),
                // XGIMI's HDMI player answers only its own action (the launcher's input_action).
                new Intent("com.xgimi.action.hdmiPlayer", uri));
        return done ? leave("Switched to " + name + ".") : ok("Nothing on this TV opens that input.");
    }

    private Result openSettings(String page) {
        String action;
        switch (page) {
            case "wifi": action = android.provider.Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": action = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "display": action = android.provider.Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": action = android.provider.Settings.ACTION_SOUND_SETTINGS; break;
            case "apps": action = android.provider.Settings.ACTION_APPLICATION_SETTINGS; break;
            case "date": action = android.provider.Settings.ACTION_DATE_SETTINGS; break;
            case "accessibility": action = android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS; break;
            case "about": action = android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS; break;
            case "screensaver": action = android.provider.Settings.ACTION_DREAM_SETTINGS; break;
            default: action = android.provider.Settings.ACTION_SETTINGS;
        }
        boolean done = startAny(new Intent(action), new Intent(android.provider.Settings.ACTION_SETTINGS));
        return done ? leave("Opened " + page + " settings.") : ok("This TV has no such settings screen.");
    }

    private String useModel(String name) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (String m : models) byName.put(m.replace('-', ' ').replace('.', ' '), m);
        String m = match(byName, name);
        if (m == null) {
            return "No model called " + name + "." + (models.size() <= 25 ? " Models: " + String.join(", ", models) : "");
        }
        settings.model(m);
        return "Now using " + m + " from the next question on.";
    }

    // ---- routines -----------------------------------------------------------------------------------

    private String saveRoutine(String name, String steps) {
        if (name.trim().isEmpty() || steps.trim().isEmpty()) return "A routine needs a name and what it does.";
        Map<String, String> all = Routines.all(settings);
        String existing = null;
        for (String k : all.keySet()) if (fold(k).equals(fold(name))) existing = k;
        if (existing != null) all.remove(existing);
        all.put(name.trim(), steps.trim());
        Routines.save(settings, all);
        return (existing != null ? "Updated" : "Saved") + " the routine \"" + name.trim() + "\". Saying its name runs it.";
    }

    private String deleteRoutine(String name) {
        Map<String, String> all = Routines.all(settings);
        Map<String, String> names = new LinkedHashMap<>();
        for (String k : all.keySet()) names.put(k, k);
        String hit = match(names, name);
        if (hit == null) return "No routine called " + name + ".";
        all.remove(hit);
        Routines.save(settings, all);
        return "Deleted the routine \"" + hit + "\".";
    }

    private String listRoutines() {
        Map<String, String> all = Routines.all(settings);
        if (all.isEmpty()) return "No routines yet.";
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) b.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        return b.toString().trim();
    }

    // ---- memory -------------------------------------------------------------------------------------

    private String remember(String fact) {
        if (fact.trim().isEmpty()) return "Nothing to remember.";
        List<String> facts = settings.facts();
        facts.add(fact.trim());
        settings.facts(facts);
        return "Remembered.";
    }

    private String forget(String about) {
        String f = fold(about);
        List<String> facts = settings.facts();
        int before = facts.size();
        facts.removeIf(s -> f.isEmpty() || fold(s).contains(f));
        settings.facts(facts);
        return "Forgot " + (before - facts.size()) + " fact(s).";
    }

    // ---- helpers ------------------------------------------------------------------------------------

    static String fmtMinutes(double m) {
        if (m < 1) return Math.round(m * 60) + " s";
        if (m < 60) return (m == Math.floor(m) ? String.valueOf((int) m) : String.format(Locale.ROOT, "%.1f", m)) + " min";
        return (int) (m / 60) + " h " + Math.round(m % 60) + " min";
    }

    private static void def(JSONArray to, String name, String description) { def(to, name, description, new JSONObject()); }

    private static void def(JSONArray to, String name, String description, JSONObject properties, String... required) {
        try {
            JSONObject params = new JSONObject().put("type", "object").put("properties", properties);
            if (required.length > 0) params.put("required", new JSONArray(java.util.Arrays.asList(required)));
            to.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                    .put("name", name).put("description", description).put("parameters", params)));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject prop(String name, String type, String description) {
        return props(new JSONObject[] {single(name, type, description, null)});
    }

    private static JSONObject enumProp(String name, String description, String... values) {
        return props(new JSONObject[] {single(name, "string", description, values)});
    }

    /** Several one-property objects merged into one properties object. */
    private static JSONObject props(JSONObject... parts) {
        JSONObject out = new JSONObject();
        try {
            for (JSONObject p : parts) {
                for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) {
                    String k = it.next();
                    out.put(k, p.get(k));
                }
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static JSONObject single(String name, String type, String description, String[] values) {
        try {
            JSONObject p = new JSONObject().put("type", type).put("description", description);
            if (values != null) p.put("enum", new JSONArray(java.util.Arrays.asList(values)));
            return new JSONObject().put(name, p);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
