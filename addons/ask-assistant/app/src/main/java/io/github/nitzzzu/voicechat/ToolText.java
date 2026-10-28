package io.github.nitzzzu.voicechat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool calls a local model wrote as text instead of in tool_calls, as llama.cpp hands some templates back
 * (the dialects caret's toolcall_rescue reads):
 *
 *   <tool_call>{"name": "x", "arguments": {...}}</tool_call>          Qwen, Hermes
 *   <function name="x"><param name="k">v</param></function>           MiniCPM
 *   <function=x><parameter=k>v</parameter></function>                 Nanbeige, Qwen3-Coder
 *   <|tool_call>call:x{k:<|"|>v<|"|>,n:5}<tool_call|>                  Gemma 4
 *
 * Only complete calls to tools that exist are taken; a call cut off mid-way stays text and is hidden.
 */
final class ToolText {
    private static final Pattern HERMES = Pattern.compile("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", Pattern.DOTALL);
    private static final Pattern MINICPM = Pattern.compile(
            "<function\\s+name=[\"'](\\w+)[\"']\\s*>(.*?)</function>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern MINICPM_PARAM = Pattern.compile(
            "<param\\s+name=[\"'](\\w+)[\"']\\s*>(.*?)</param>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern EQUALS = Pattern.compile(
            "<function\\s*=\\s*(\\w+)\\s*>(.*?)</function>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern EQUALS_PARAM = Pattern.compile(
            "<parameter\\s*=\\s*(\\w+)\\s*>(.*?)</parameter>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern GEMMA = Pattern.compile(
            "<\\|tool_call>\\s*call:\\s*(\\w+)\\s*\\{(.*?)\\}\\s*<tool_call\\|>", Pattern.DOTALL);
    private static final String GEMMA_QUOTE = "<|\"|>";
    /** Where a call starts, complete or not: the text from here on is never shown or read aloud. */
    private static final Pattern OPENER = Pattern.compile(
            "<tool_call>|<\\|tool_call>|<function[\\s=]|\\[\\s*\\w+\\s*\\(\\s*\\w+\\s*=", Pattern.CASE_INSENSITIVE);

    private ToolText() {}

    /** The text up to the first tool-call opener. */
    static String beforeCalls(String text) {
        Matcher m = OPENER.matcher(text);
        return m.find() ? text.substring(0, m.start()) : text;
    }

    /** Adds every complete text-form call to a known tool to out; returns how many it found. */
    static int rescue(String text, Set<String> tools, List<Llm.ToolCall> out) {
        int before = out.size();
        Matcher m = HERMES.matcher(text);
        while (m.find()) {
            try {
                JSONObject o = new JSONObject(m.group(1));
                String name = o.optString("name", "");
                Object args = o.opt("arguments");
                if (args == null) args = o.opt("parameters");
                JSONObject a = args instanceof JSONObject ? (JSONObject) args
                        : args instanceof String ? new JSONObject((String) args) : new JSONObject();
                add(out, tools, name, a);
            } catch (JSONException e) {
                // not a call
            }
        }
        m = MINICPM.matcher(text);
        while (m.find()) add(out, tools, m.group(1), params(MINICPM_PARAM, m.group(2)));
        m = EQUALS.matcher(text);
        while (m.find()) add(out, tools, m.group(1), params(EQUALS_PARAM, m.group(2)));
        m = GEMMA.matcher(text);
        while (m.find()) add(out, tools, m.group(1), gemma(m.group(2)));
        return out.size() - before;
    }

    private static void add(List<Llm.ToolCall> out, Set<String> tools, String name, JSONObject args) {
        if (!tools.contains(name)) return;
        Llm.ToolCall c = new Llm.ToolCall();
        c.id = "call_" + (out.size() + 1) + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFF);
        c.name = name;
        c.args.append(args.toString());
        out.add(c);
    }

    private static JSONObject params(Pattern p, String body) {
        JSONObject o = new JSONObject();
        Matcher m = p.matcher(body);
        while (m.find()) put(o, m.group(1), m.group(2).trim());
        return o;
    }

    /** Gemma's k:v pairs, strings wrapped in <|"|> and not escaped, so the delimiters decide where a value ends. */
    private static JSONObject gemma(String body) {
        JSONObject o = new JSONObject();
        int i = 0;
        while (i < body.length()) {
            int colon = body.indexOf(':', i);
            if (colon < 0) break;
            String key = body.substring(i, colon).replace(",", "").trim();
            int v = colon + 1;
            String value;
            if (body.startsWith(GEMMA_QUOTE, v)) {
                int end = body.indexOf(GEMMA_QUOTE, v + GEMMA_QUOTE.length());
                if (end < 0) break;
                value = body.substring(v + GEMMA_QUOTE.length(), end);
                i = end + GEMMA_QUOTE.length();
            } else {
                int comma = body.indexOf(',', v);
                int end = comma < 0 ? body.length() : comma;
                value = body.substring(v, end).trim();
                i = end;
            }
            if (!key.isEmpty()) put(o, key, value);
            while (i < body.length() && (body.charAt(i) == ',' || Character.isWhitespace(body.charAt(i)))) i++;
        }
        return o;
    }

    /** Numbers and booleans as values; everything else as the text it is. */
    private static void put(JSONObject o, String k, String v) {
        try {
            if (v.matches("-?\\d+")) o.put(k, Long.parseLong(v));
            else if (v.matches("-?\\d*\\.\\d+")) o.put(k, Double.parseDouble(v));
            else if (v.equals("true") || v.equals("false")) o.put(k, Boolean.parseBoolean(v));
            else o.put(k, v.startsWith("\"") && v.endsWith("\"") && v.length() > 1 ? v.substring(1, v.length() - 1) : v);
        } catch (JSONException | NumberFormatException e) {
            // skip
        }
    }
}
