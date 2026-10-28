package io.github.nitzzzu.voicechat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The tools that go online: SearXNG, YouTube through SearXNG, Cinemeta for Stremio, Open-Meteo, ntfy, webhooks. */
final class Online {
    static final String STREMIO = "com.stremio.one";
    /** YouTube players, the TV's own last: SmartTube first, as it is what this TV uses. */
    private static final String[] YOUTUBE = {"org.smarttube.stable", "org.smarttube.beta", "com.liskovsoft.smarttubetv",
            "com.liskovsoft.smarttubetv.beta", "com.teamsmart.videomanager.tv", "com.google.android.youtube.tv"};
    private static final String CINEMETA = "https://v3-cinemeta.strem.io";

    private final Settings settings;

    Online(Context ctx, Settings settings) {
        this.settings = settings;
    }

    // ---- web ----------------------------------------------------------------------------------------

    String search(String query) throws IOException {
        if (settings.search().isEmpty()) return "Web search is not set up.";
        JSONObject o = Net.json(settings.search() + "/search?format=json&q=" + Net.enc(query), null);
        StringBuilder b = new StringBuilder();
        JSONArray answers = o.optJSONArray("answers");
        for (int i = 0; answers != null && i < answers.length() && i < 2; i++) {
            Object a = answers.opt(i);
            String text = a instanceof JSONObject ? ((JSONObject) a).optString("answer") : String.valueOf(a);
            if (!text.isEmpty()) b.append("Answer: ").append(text).append("\n");
        }
        JSONArray boxes = o.optJSONArray("infoboxes");
        if (boxes != null && boxes.length() > 0) {
            JSONObject box = boxes.optJSONObject(0);
            String content = box.optString("content", "");
            if (!content.isEmpty()) b.append(box.optString("infobox", "")).append(": ")
                    .append(cut(content, 600)).append("\n");
        }
        JSONArray results = o.optJSONArray("results");
        int n = 0;
        for (int i = 0; results != null && i < results.length() && n < 6; i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            n++;
            b.append(n).append(". ").append(r.optString("title")).append(" — ").append(r.optString("url"));
            String d = r.optString("publishedDate", "");
            if (!d.isEmpty() && !d.equals("null")) b.append(" (").append(d.length() > 10 ? d.substring(0, 10) : d).append(")");
            b.append("\n   ").append(cut(r.optString("content"), 300)).append("\n");
        }
        if (b.length() == 0) return "No results for " + query + ".";
        return Net.untrusted(b.toString().trim(), "web search");
    }

    String read(String url) throws IOException {
        String text = Net.page(url, 6000);
        return Net.untrusted(text, Uri.parse(url).getHost());
    }

    // ---- YouTube ------------------------------------------------------------------------------------

    Tools.Result youtube(String query, Tools tools) throws IOException {
        String video = null, title = null;
        String q = query.trim();
        // A link (a routine's own playlist) is opened as it is, not searched.
        if (q.matches("https?://(www\\.|m\\.)?(youtube\\.com|youtu\\.be)/\\S+")) {
            video = q;
            title = q.contains("list=") ? "your playlist" : "that video";
        } else if (!settings.search().isEmpty()) {
            JSONObject o = Net.json(settings.search() + "/search?format=json&engines=youtube&q=" + Net.enc(query), null);
            JSONArray results = o.optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                String u = r == null ? "" : r.optString("url");
                if (u.contains("youtube.com/watch") || u.contains("youtu.be/")) {
                    video = u;
                    title = r.optString("title");
                    break;
                }
            }
        }
        Uri uri = Uri.parse(video != null ? video : "https://www.youtube.com/results?search_query=" + Net.enc(query));
        for (String pkg : YOUTUBE) {
            if (tools.installed(pkg) && tools.startAny(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) {
                return Tools.leave(video != null ? "Playing \"" + title + "\" on YouTube." : "Showing YouTube results for " + query + ".");
            }
        }
        return tools.startAny(new Intent(Intent.ACTION_VIEW, uri))
                ? Tools.leave("Opened YouTube for " + query + ".")
                : Tools.ok("There is no YouTube app on this TV.");
    }

    // ---- Stremio ------------------------------------------------------------------------------------

    /** A title found for what was heard, with how sure the match is. */
    private static final class Pick {
        String id, name, year, type;
        double score;
        /** Found by the web search, whose engines already corrected the spelling: never scored below 0.8. */
        boolean web;

        String label() {
            return name + (year.isEmpty() ? "" : " (" + year + ")") + ", " + (type.equals("movie") ? "film" : "series");
        }
    }

    /**
     * Opens a film or an episode in Stremio. Speech recognition mangles titles ("severns", "breaking bat"), so the
     * heard title is matched loosely: Cinemeta's search first, then a web search for its IMDb page (search engines
     * correct spelling), with titles from Continue Watching preferred. Only a confident match opens; otherwise the
     * candidates go back to the model, which asks.
     */
    Tools.Result stremio(String title, String type, int season, int episode, String imdbId, String heard, Context ctx,
            Tools tools) throws IOException {
        if (!tools.installed(STREMIO)) return Tools.ok("Stremio is not installed.");
        if (season > 0 || episode > 0) type = "series";
        Pick pick;
        if (imdbId.matches("tt\\d{3,10}")) {
            pick = byId(imdbId, type);
            if (pick == null) return Tools.ok("Cinemeta doesn't know " + imdbId + ".");
        } else {
            java.util.List<String> watching = Watching.titles(ctx);
            java.util.List<Pick> found = candidates(title, type, watching);
            // What was really heard, when the model "corrected" it: its matches compete too, and a model title that
            // sounds nothing like what was said (an unrelated title for "in caption") loses its head start.
            String said = titleWords(heard);
            if (!said.isEmpty() && Fuzzy.score(said, title) < 0.95) {
                for (Pick p : candidates(said, type, watching)) {
                    boolean dup = false;
                    for (Pick q : found) if (q.id.equals(p.id)) dup = true;
                    if (!dup) found.add(p);
                }
                // Scored by how well each explains the words actually heard; the model's guess only adds a smaller
                // weight, so "Gun" can't beat "Shogun" for "show gun".
                for (Pick q : found) {
                    q.score = Math.max(Math.max(Fuzzy.score(said, q.name), 0.85 * Fuzzy.score(title, q.name)), q.web ? 0.8 : 0)
                            + bonus(q.name, watching);
                }
            }
            // An episode asked for must exist: a show without that season and episode drops out.
            if (season > 0 && episode > 0) {
                for (int i = 0; i < found.size() && i < 4; i++) {
                    Pick p = found.get(i);
                    if (!p.type.equals("series")) continue;
                    Boolean has = hasEpisode(p.id, season, episode);
                    if (has != null) p.score += has ? 0.05 : -0.4;
                }
            }
            found.sort((a, b) -> Double.compare(b.score, a.score));
            if (found.isEmpty()) {
                return Tools.ok("Found nothing like \"" + title + "\", not even on the web. Ask the user to say it again.");
            }
            pick = found.get(0);
            boolean clear = pick.score >= 0.82
                    && (found.size() < 2 || pick.score - found.get(1).score >= 0.08 || pick.score >= 0.97);
            if (!clear) {
                StringBuilder b = new StringBuilder("Not sure which title \"" + title
                        + "\" is (speech recognition may have misheard). Candidates:\n");
                for (int i = 0; i < found.size() && i < 5; i++) {
                    Pick p = found.get(i);
                    b.append(i + 1).append(". ").append(p.label()).append(" imdb_id=").append(p.id).append('\n');
                }
                b.append("If one is clearly what the user meant, call stremio again with its imdb_id; otherwise ask, "
                        + "naming two or three.");
                return Tools.ok(b.toString());
            }
        }
        String id = pick.id;
        String name = pick.name + (pick.year.isEmpty() ? "" : " (" + pick.year + ")");
        String link;
        if (pick.type.equals("movie")) {
            link = "stremio:///detail/movie/" + id + "/" + id;
        } else if (season > 0 && episode > 0) {
            link = "stremio:///detail/series/" + id + "/" + id + ":" + season + ":" + episode;
            name += String.format(Locale.ROOT, ", season %d episode %d", season, episode);
        } else {
            link = "stremio:///detail/series/" + id;
        }
        // A running Stremio ignores a new link on its open screen: restart its task, as its own rows do.
        boolean done = tools.startAny(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(STREMIO)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
        if (!done) return Tools.ok("Stremio didn't open the link.");
        return Tools.leave("Opened " + name + " in Stremio.");
    }

    /** The title words in a request: "put on the movie in caption" is "in caption". */
    static String titleWords(String said) {
        return Tools.fold(said).replaceAll("\\b(season|episode|series|part)\\s+\\w+", " ")
                .replaceAll("\\b(hey|ok|please|can you|could you|i want to|i d like to|let s|put on|play|watch|start|"
                        + "open|resume|continue|show me|find|the movie|the film|the show|the series|a movie|a film|"
                        + "movie|film|show|series|on stremio|in stremio|stremio|for me|now)\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** Cinemeta's matches for the heard title, then the web's, scored and best first. */
    private java.util.List<Pick> candidates(String heard, String type, java.util.List<String> watching) {
        java.util.Map<String, Pick> all = new java.util.LinkedHashMap<>();
        String[] types = type.equals("movie") || type.equals("series") ? new String[] {type} : new String[] {"series", "movie"};
        for (String t : types) {
            try {
                JSONArray metas = Net.json(CINEMETA + "/catalog/" + t + "/top/search=" + Uri.encode(heard) + ".json", null)
                        .optJSONArray("metas");
                for (int i = 0; metas != null && i < metas.length() && i < 8; i++) {
                    add(all, metas.optJSONObject(i), t, heard, watching);
                }
            } catch (IOException e) {
                // try the web
            }
        }
        if (best(all) < 0.82 && !settings.search().isEmpty()) {
            for (Pick p : fromWeb(heard, type)) {
                p.web = true;
                p.score = Math.max(Fuzzy.score(heard, p.name), 0.8) + bonus(p.name, watching);
                if (!all.containsKey(p.id) || all.get(p.id).score < p.score) all.put(p.id, p);
            }
        }
        java.util.List<Pick> out = new java.util.ArrayList<>(all.values());
        out.sort((a, b) -> Double.compare(b.score, a.score));
        return out;
    }

    private static void add(java.util.Map<String, Pick> all, JSONObject m, String type, String heard,
            java.util.List<String> watching) {
        if (m == null) return;
        Pick p = new Pick();
        p.id = m.optString("imdb_id", m.optString("id"));
        p.name = m.optString("name");
        p.year = m.optString("releaseInfo", m.optString("year", ""));
        p.type = type;
        p.score = Fuzzy.score(heard, p.name) + bonus(p.name, watching);
        if (!p.id.startsWith("tt") || p.name.isEmpty()) return;
        if (!all.containsKey(p.id) || all.get(p.id).score < p.score) all.put(p.id, p);
    }

    /** Something the user is already watching wins a close call. */
    private static double bonus(String name, java.util.List<String> watching) {
        for (String w : watching) if (Fuzzy.score(w, name) >= 0.9) return 0.1;
        return 0;
    }

    private static double best(java.util.Map<String, Pick> all) {
        double b = 0;
        for (Pick p : all.values()) b = Math.max(b, p.score);
        return b;
    }

    /**
     * IMDb pages the web search finds for the heard words: the search engines behind SearXNG correct spelling and
     * sound-alikes far better than a catalogue search. The id comes from the link, the name from Cinemeta.
     */
    private java.util.List<Pick> fromWeb(String heard, String type) {
        java.util.List<Pick> out = new java.util.ArrayList<>();
        try {
            String q = heard + (type.equals("movie") ? " film" : type.equals("series") ? " tv series" : "") + " imdb";
            JSONArray results = Net.json(settings.search() + "/search?format=json&q=" + Net.enc(q), null)
                    .optJSONArray("results");
            java.util.regex.Pattern idp = java.util.regex.Pattern.compile("imdb\\.com/title/(tt\\d{3,10})");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; results != null && i < results.length() && out.size() < 4; i++) {
                JSONObject r = results.optJSONObject(i);
                java.util.regex.Matcher m = idp.matcher(r == null ? "" : r.optString("url"));
                if (!m.find() || !seen.add(m.group(1))) continue;
                Pick p = byId(m.group(1), type);
                if (p != null) out.add(p);
            }
        } catch (IOException e) {
            // nothing from the web
        }
        return out;
    }

    /** Whether a series has that episode (Cinemeta's video list), or null when it can't tell. */
    private static Boolean hasEpisode(String id, int season, int episode) {
        try {
            JSONObject meta = Net.json(CINEMETA + "/meta/series/" + id + ".json", null).optJSONObject("meta");
            JSONArray videos = meta == null ? null : meta.optJSONArray("videos");
            if (videos == null || videos.length() == 0) return null;
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.optJSONObject(i);
                if (v != null && v.optInt("season") == season && v.optInt("episode", v.optInt("number")) == episode) return true;
            }
            return false;
        } catch (IOException e) {
            return null;
        }
    }

    /** Cinemeta's own record for an IMDb id: its name, year and whether it is a series. */
    private static Pick byId(String id, String type) {
        String[] types = type.equals("movie") ? new String[] {"movie", "series"} : new String[] {"series", "movie"};
        for (String t : types) {
            try {
                JSONObject meta = Net.json(CINEMETA + "/meta/" + t + "/" + id + ".json", null).optJSONObject("meta");
                if (meta == null || meta.optString("name").isEmpty()) continue;
                Pick p = new Pick();
                p.id = id;
                p.name = meta.optString("name");
                p.year = meta.optString("releaseInfo", meta.optString("year", ""));
                p.type = t;
                p.score = 1;
                return p;
            } catch (IOException e) {
                // the other type
            }
        }
        return null;
    }

    /**
     * A stream card in Stremio's list: the element that shows a size or a resolution over several lines, which the
     * title and episode lines never do.
     */
    private static final java.util.regex.Pattern STREAM = java.util.regex.Pattern.compile(
            "\\d+(\\.\\d+)?\\s?(GB|MB)|\\b(2160p|1080p|720p|4K)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    static final KeyService.Match STREAM_CARD = (folded, raw) -> raw.contains("\n") && STREAM.matcher(raw).find();

    private static String yearOf(JSONObject meta) {
        String y = meta.optString("releaseInfo", meta.optString("year", ""));
        return y.isEmpty() ? "" : " (" + y + ")";
    }

    // ---- weather ------------------------------------------------------------------------------------

    String weather(String place, int days) throws IOException {
        days = Math.max(1, Math.min(7, days));
        JSONObject g = Net.json("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name="
                + Net.enc(place), null);
        JSONArray res = g.optJSONArray("results");
        if (res == null || res.length() == 0) return "Don't know a place called " + place + ".";
        JSONObject p = res.optJSONObject(0);
        JSONObject f = Net.json(String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&timezone=auto&forecast_days=%d", p.optDouble("latitude"), p.optDouble("longitude"), days),
                null);
        StringBuilder b = new StringBuilder();
        b.append(p.optString("name")).append(", ").append(p.optString("country")).append(". ");
        JSONObject c = f.optJSONObject("current");
        if (c != null) {
            b.append(String.format(Locale.ROOT, "Now %s, %.0f°C (feels %.0f°C), humidity %d%%, wind %.0f km/h.\n",
                    sky(c.optInt("weather_code")), c.optDouble("temperature_2m"), c.optDouble("apparent_temperature"),
                    c.optInt("relative_humidity_2m"), c.optDouble("wind_speed_10m")));
        }
        JSONObject d = f.optJSONObject("daily");
        JSONArray dates = d == null ? null : d.optJSONArray("time");
        for (int i = 0; dates != null && i < dates.length(); i++) {
            b.append(String.format(Locale.ROOT, "%s: %s, %.0f to %.0f°C, rain chance %d%%.\n", dates.optString(i),
                    sky(d.optJSONArray("weather_code").optInt(i)), d.optJSONArray("temperature_2m_min").optDouble(i),
                    d.optJSONArray("temperature_2m_max").optDouble(i),
                    d.optJSONArray("precipitation_probability_max").optInt(i)));
        }
        return b.toString().trim();
    }

    private static String sky(int code) {
        if (code == 0) return "clear";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "overcast";
        if (code <= 48) return "fog";
        if (code <= 57) return "drizzle";
        if (code <= 67) return "rain";
        if (code <= 77) return "snow";
        if (code <= 82) return "showers";
        if (code <= 86) return "snow showers";
        return "thunderstorms";
    }

    // ---- phone and webhooks -------------------------------------------------------------------------

    String ntfy(String message, String link) throws IOException {
        if (settings.ntfy().isEmpty()) return "Sending to the phone is not set up.";
        Map<String, String> h = new HashMap<>();
        h.put("Title", "From the TV");
        h.put("Tags", "tv");
        if (!link.isEmpty() && (link.startsWith("https://") || link.startsWith("http://"))) h.put("Click", link);
        if (!settings.ntfyToken().isEmpty()) h.put("Authorization", "Bearer " + settings.ntfyToken());
        String body = link.isEmpty() || message.contains(link) ? message : message + "\n" + link;
        int code = Net.post(settings.ntfy(), "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), h);
        return code / 100 == 2 ? "Sent to the phone." : "The phone notification failed (HTTP " + code + ").";
    }

    String hook(Hooks.Hook hook, String input) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("input", input).put("source", "tv");
        } catch (JSONException e) {
            throw new IOException(e);
        }
        int code = Net.post(hook.url, "application/json", body.toString().getBytes(StandardCharsets.UTF_8), null);
        return code / 100 == 2 ? hook.name + " ran." : hook.name + " failed (HTTP " + code + ").";
    }

    private static String cut(String s, int n) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
