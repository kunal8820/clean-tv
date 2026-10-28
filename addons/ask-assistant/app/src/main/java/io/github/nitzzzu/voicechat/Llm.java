package io.github.nitzzzu.voicechat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** LiteLLM's OpenAI-compatible API: the model list, and a chat completion streamed with its tool calls. Blocking. */
final class Llm {
    interface Sink { void delta(String text); }

    static final class ToolCall {
        String id, name;
        final StringBuilder args = new StringBuilder();

        JSONObject arguments() {
            String a = args.toString().trim();
            try {
                return a.isEmpty() ? new JSONObject() : new JSONObject(a);
            } catch (JSONException e) {
                return new JSONObject();
            }
        }
    }

    static final class Reply {
        final StringBuilder text = new StringBuilder();
        final List<ToolCall> calls = new ArrayList<>();
    }

    /** One request, so Back can cut it off mid-stream. */
    static final class Call {
        private volatile HttpURLConnection conn;
        private volatile boolean cancelled;

        void cancel() {
            cancelled = true;
            HttpURLConnection c = conn;
            if (c != null) c.disconnect();
        }

        boolean cancelled() { return cancelled; }
    }

    private Llm() {}

    static List<String> models(Settings s) throws IOException {
        HttpURLConnection c = open(s, "/models", 8000);
        try {
            JSONArray data = new JSONObject(Net.read(check(c), 4 << 20)).optJSONArray("data");
            List<String> out = new ArrayList<>();
            // Servers that say which models take tools (OpenRouter: supported_parameters) get only those offered.
            boolean says = false;
            for (int i = 0; data != null && i < data.length(); i++) {
                if (data.getJSONObject(i).has("supported_parameters")) says = true;
            }
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject m = data.getJSONObject(i);
                String id = m.optString("id", "");
                JSONArray params = m.optJSONArray("supported_parameters");
                if (says && (params == null || !params.toString().contains("\"tools\""))) continue;
                // OpenRouter's ":batch" variants are for batch jobs, not a conversation.
                if (!id.isEmpty() && !id.endsWith(":batch")) out.add(id);
            }
            Collections.sort(out);
            return out;
        } catch (JSONException e) {
            throw new IOException("unexpected /models answer");
        } finally {
            c.disconnect();
        }
    }

    /** Streams the reply's text into sink as it comes; tool calls are gathered and returned whole. */
    static Reply chat(Settings s, String model, JSONArray messages, JSONArray tools, Call call, Sink sink)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            body.put("messages", messages);
            body.put("stream", true);
            body.put("temperature", 0.4);
            if (tools != null && tools.length() > 0) body.put("tools", tools);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        // A cold model on llama-swap takes up to a few minutes to load: a long read timeout, not an error.
        HttpURLConnection c = open(s, "/chat/completions", 240000);
        call.conn = c;
        if (call.cancelled()) c.disconnect();
        Reply reply = new Reply();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "text/event-stream");
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            try (OutputStream o = c.getOutputStream()) { o.write(out); }
            BufferedReader r = new BufferedReader(new InputStreamReader(check(c), StandardCharsets.UTF_8));
            String line;
            while (!call.cancelled() && (line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                chunk(data, reply, sink);
            }
            return reply;
        } finally {
            c.disconnect();
        }
    }

    private static void chunk(String json, Reply reply, Sink sink) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            return;
        }
        if (o.has("error")) throw new IOException(message(o));
        JSONArray choices = o.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return;
        JSONObject d = choices.optJSONObject(0) == null ? null : choices.optJSONObject(0).optJSONObject("delta");
        if (d == null) return;
        // reasoning_content (a thinking model's notes) is left out: only the answer is shown and read.
        if (!d.isNull("content")) {
            String text = d.optString("content", "");
            if (!text.isEmpty()) {
                reply.text.append(text);
                sink.delta(text);
            }
        }
        JSONArray calls = d.optJSONArray("tool_calls");
        for (int i = 0; calls != null && i < calls.length(); i++) {
            JSONObject tc = calls.optJSONObject(i);
            if (tc == null) continue;
            String id = tc.isNull("id") ? "" : tc.optString("id", "");
            int index = tc.optInt("index", -1);
            ToolCall slot;
            if (index >= 0 && index < reply.calls.size()) {
                slot = reply.calls.get(index);
            } else if (index < 0 && id.isEmpty() && !reply.calls.isEmpty()) {
                slot = reply.calls.get(reply.calls.size() - 1);
            } else {
                slot = new ToolCall();
                reply.calls.add(slot);
            }
            if (!id.isEmpty()) slot.id = id;
            JSONObject f = tc.optJSONObject("function");
            if (f != null) {
                if (!f.isNull("name") && !f.optString("name", "").isEmpty()) slot.name = f.optString("name");
                if (!f.isNull("arguments")) slot.args.append(f.optString("arguments", ""));
            }
        }
    }

    private static HttpURLConnection open(Settings s, String path, int readTimeout) throws IOException {
        URL url = new URL(s.url() + path);
        if (!"https".equals(url.getProtocol()) && !"http".equals(url.getProtocol())) throw new IOException("bad url");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("Authorization", "Bearer " + s.key());
        return c;
    }

    private static InputStream check(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code == 200) return c.getInputStream();
        String msg = "HTTP " + code;
        InputStream err = c.getErrorStream();
        if (err != null) {
            try {
                msg = message(new JSONObject(Net.read(err, 1 << 16)));
            } catch (JSONException | IOException e) {
                // keep the status line
            }
        }
        if (code == 401) throw new IOException("the key was refused (401)");
        // Free models on OpenRouter and busy servers: say what to do rather than the provider's raw words.
        if (code == 429) throw new IOException("that model is busy right now (rate limit). Try again in a moment, or switch model");
        throw new IOException(msg);
    }

    private static String message(JSONObject o) {
        Object e = o.opt("error");
        String m = e instanceof JSONObject ? ((JSONObject) e).optString("message", "") : String.valueOf(e);
        m = m.trim();
        return m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }
}
