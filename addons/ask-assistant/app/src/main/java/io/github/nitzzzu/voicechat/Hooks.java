package io.github.nitzzzu.voicechat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Webhooks set up with install.sh (HOOKS='[{"name":"movie night","description":"Dim the lights...","url":"https://n8n.../webhook/..."}]'),
 * each offered to the model as its own tool. The call is a POST of {"input": "...", "source": "tv"}.
 */
final class Hooks {
    static final class Hook {
        String name, description, url;

        String tool() { return "hook_" + Tools.fold(name).replace(' ', '_'); }
    }

    private Hooks() {}

    static List<Hook> parse(String json) {
        List<Hook> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length() && out.size() < 20; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Hook h = new Hook();
                h.name = o.optString("name", "").trim();
                h.url = o.optString("url", "").trim();
                h.description = o.optString("description", "Run the " + h.name + " workflow.").trim();
                if (!h.name.isEmpty() && (h.url.startsWith("https://") || h.url.startsWith("http://"))) out.add(h);
            }
        } catch (JSONException e) {
            // no hooks
        }
        return out;
    }

    static Hook byTool(String json, String tool) {
        for (Hook h : parse(json)) if (h.tool().equals(tool)) return h;
        return null;
    }
}
