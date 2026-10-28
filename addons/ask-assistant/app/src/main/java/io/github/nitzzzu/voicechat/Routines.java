package io.github.nitzzzu.voicechat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routines: a name and the request it stands for, in plain words ("bed time" → "play brown noise from my sleep
 * playlist, volume 15, turn off in 90 minutes"). Saying the name runs the request through the same tools as anything
 * else; the name is matched here, before the model, so a routine never depends on the model noticing it.
 */
final class Routines {
    private static final int MAX = 30;

    private Routines() {}

    static Map<String, String> all(Settings s) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(s.routines().isEmpty() ? "{}" : s.routines());
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                String v = o.optString(k, "").trim();
                if (!k.trim().isEmpty() && !v.isEmpty()) out.put(k.trim(), v);
            }
        } catch (JSONException e) {
            // none
        }
        return out;
    }

    static void save(Settings s, Map<String, String> map) {
        JSONObject o = new JSONObject();
        try {
            int n = 0;
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (n++ >= MAX) break;
                o.put(e.getKey(), e.getValue());
            }
        } catch (JSONException e) {
            return;
        }
        s.put(Settings.ROUTINES, o.toString());
    }

    /** "bed time: play brown noise…" lines (the phone form) as routines; lines without a colon are skipped. */
    static Map<String, String> parseLines(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim(), steps = line.substring(colon + 1).trim();
            if (!name.isEmpty() && !steps.isEmpty()) out.put(name, steps);
        }
        return out;
    }

    static String toLines(Settings s) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : all(s).entrySet()) b.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        return b.toString().trim();
    }

    /**
     * The routine the user named, or null. "bed time", "it's bed time", "run bed time", "start the bed time routine"
     * all match "bed time"; a request that merely contains other words ("what time is bed time usually") does not.
     */
    static String match(Settings s, String said) {
        String f = " " + Tools.fold(said) + " ";
        String core = f.replaceAll(" (ok|okay|hey|please|run|start|do|go|it s|its|time for|the|my|routine|now|mode|ask) ", " ")
                .replaceAll("\\s+", " ").trim();
        String best = null;
        double bestScore = 0;
        for (String name : all(s).keySet()) {
            String n = Tools.fold(name).replaceAll("\\b(routine|mode)\\b", " ").replaceAll("\\s+", " ").trim();
            if (n.isEmpty()) continue;
            double score = core.equals(n) ? 1 : Fuzzy.ratio(core, n);
            // A few extra words around the name are fine ("bed time now please" is handled above); a sentence isn't.
            if (score < 0.85 && core.contains(n) && core.length() <= n.length() + 6) score = 0.9;
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return bestScore >= 0.85 ? best : null;
    }
}
