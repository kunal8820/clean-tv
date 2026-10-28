package io.github.nitzzzu.voicechat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The setup page for your phone, served by the TV on the LAN only while the setup screen is open. A form takes the
 * LiteLLM address and key (and the optional extras); a six-digit PIN shown on the TV guards it, and five wrong PINs
 * make a new one. The server checks the key against LiteLLM before it answers, so the phone shows the result.
 */
final class SetupServer {
    interface Events {
        void phoneConnected();
        void checking();
        /** Saved and checked: the server's models. */
        void saved(List<String> models);
        void newPin(String pin);
    }

    private static final int PORT = 8765;
    private static final int MAX_BODY = 32 * 1024;

    private final Settings settings;
    private final Events events;
    private final SecureRandom random = new SecureRandom();
    private volatile ServerSocket server;
    private volatile String pin;
    private int wrong;

    SetupServer(Context ctx, Events events) {
        this.settings = new Settings(ctx);
        this.events = events;
        this.pin = newPin();
    }

    String pin() { return pin; }

    /** Starts listening; returns the address for the QR code, or null when the TV has no LAN address. */
    String start() throws IOException {
        String ip = lanAddress();
        if (ip == null) return null;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        try {
            s.bind(new java.net.InetSocketAddress(InetAddress.getByName(ip), PORT));
        } catch (IOException e) {
            s.bind(new java.net.InetSocketAddress(InetAddress.getByName(ip), 0));
        }
        server = s;
        Thread t = new Thread(this::loop, "setup-server");
        t.setDaemon(true);
        t.start();
        return "http://" + ip + ":" + s.getLocalPort() + "/";
    }

    void stop() {
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                // closed
            }
        }
    }

    private void loop() {
        while (server != null) {
            try (Socket c = server.accept()) {
                c.setSoTimeout(30000);
                handle(c);
            } catch (IOException | RuntimeException e) {
                // next client
            }
        }
    }

    private void handle(Socket c) throws IOException {
        InputStream in = new BufferedInputStream(c.getInputStream());
        String request = line(in);
        if (request == null) return;
        Map<String, String> headers = new HashMap<>();
        String h;
        while ((h = line(in)) != null && !h.isEmpty()) {
            int colon = h.indexOf(':');
            if (colon > 0) headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        String[] parts = request.split(" ");
        if (parts.length < 2) return;
        OutputStream out = c.getOutputStream();
        if (parts[0].equals("GET") && (parts[1].equals("/") || parts[1].startsWith("/?"))) {
            events.phoneConnected();
            send(out, 200, "text/html; charset=utf-8", page());
        } else if (parts[0].equals("POST") && parts[1].equals("/save")) {
            int len = 0;
            try {
                len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException e) {
                // empty
            }
            if (len <= 0 || len > MAX_BODY) {
                send(out, 400, "application/json", "{\"ok\":false,\"error\":\"Empty or too large.\"}");
                return;
            }
            byte[] body = new byte[len];
            int read = 0;
            while (read < len) {
                int n = in.read(body, read, len - read);
                if (n < 0) break;
                read += n;
            }
            send(out, 200, "application/json", save(form(new String(body, 0, read, StandardCharsets.UTF_8))));
        } else {
            send(out, 404, "text/plain", "Not found");
        }
    }

    private synchronized String save(Map<String, String> f) {
        JSONObject r = new JSONObject();
        try {
            if (!pin.equals(f.getOrDefault("pin", "").replaceAll("\\D", ""))) {
                if (++wrong >= 5) {
                    wrong = 0;
                    pin = newPin();
                    events.newPin(pin);
                    return r.put("ok", false).put("error", "Wrong PIN too many times. A new PIN is on the TV.").toString();
                }
                return r.put("ok", false).put("error", "That PIN doesn't match the one on the TV.").toString();
            }
            String url = f.getOrDefault("url", "").trim();
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                return r.put("ok", false).put("error", "The LiteLLM address must start with https:// or http://").toString();
            }
            settings.put(Settings.URL, url);
            String key = f.getOrDefault("key", "").trim();
            if (!key.isEmpty()) settings.put(Settings.KEY, key);
            if (settings.key().isEmpty()) return r.put("ok", false).put("error", "The key is missing.").toString();
            for (String name : new String[] {Settings.SEARCH, Settings.NTFY, Settings.LANGUAGE, Settings.HOOKS, Settings.PROMPT}) {
                if (f.containsKey(name)) settings.put(name, f.get(name));
            }
            if (f.containsKey(Settings.ROUTINES)) Routines.save(settings, Routines.parseLines(f.get(Settings.ROUTINES)));
            String token = f.getOrDefault(Settings.NTFY_TOKEN, "").trim();
            if (!token.isEmpty()) settings.put(Settings.NTFY_TOKEN, token);
            events.checking();
            List<String> models;
            try {
                models = Llm.models(settings);
            } catch (IOException e) {
                String why = e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException
                        || e instanceof java.net.SocketTimeoutException
                        ? "The TV can't reach " + url + ". Is Tailscale connected on the TV?"
                        : "LiteLLM answered: " + e.getMessage();
                return r.put("ok", false).put("error", why).toString();
            }
            if (models.isEmpty()) return r.put("ok", false).put("error", "Connected, but the key sees no models.").toString();
            events.saved(models);
            return r.put("ok", true).put("models", new JSONArray(models)).toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":\"internal\"}";
        }
    }

    // ---- the page -----------------------------------------------------------------------------------

    private String page() {
        String hooks = settings.hooks();
        return PAGE.replace("{{url}}", esc(settings.url().replaceAll("/v1$", "")))
                .replace("{{key_hint}}", settings.key().isEmpty() ? "sk-…" : "Saved. Leave empty to keep it")
                .replace("{{search}}", esc(settings.search()))
                .replace("{{ntfy}}", esc(settings.ntfy()))
                .replace("{{ntfy_hint}}", settings.ntfyToken().isEmpty() ? "Optional" : "Saved. Leave empty to keep it")
                .replace("{{language}}", esc(settings.language()))
                .replace("{{prompt}}", esc(settings.prompt()))
                .replace("{{hooks}}", esc(hooks.isEmpty() ? "" : pretty(hooks)))
                .replace("{{routines}}", esc(Routines.toLines(settings)));
    }

    private static String pretty(String json) {
        try {
            return new JSONArray(json).toString(2);
        } catch (JSONException e) {
            return json;
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Ask · TV setup</title><style>"
            + ":root{--a:#2BB3A3}*{box-sizing:border-box}body{margin:0;font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;"
            + "background:radial-gradient(120% 80% at 0 0,#0E2A2B,#05090A 60%);color:#EAF2F2;min-height:100vh}"
            + "main{max-width:560px;margin:0 auto;padding:28px 20px 48px}h1{font-weight:600;font-size:28px;margin:6px 0 2px}"
            + ".sub{color:#9FB3B3;margin:0 0 22px}.logo{display:inline-grid;place-items:center;width:44px;height:44px;border-radius:12px;background:var(--a);color:#06201F;font-weight:700}"
            + "label{display:block;font-weight:600;margin:16px 0 6px}small{display:block;color:#8AA0A0;font-weight:400;margin-top:4px}"
            + "input,textarea{width:100%;padding:13px 14px;border-radius:12px;border:1px solid #23393B;background:#0B1416;color:#fff;font:inherit}"
            + "textarea{min-height:110px;font:13px/1.4 ui-monospace,Menlo,Consolas,monospace}input:focus,textarea:focus{outline:2px solid var(--a);border-color:transparent}"
            + ".pin{font-size:28px;letter-spacing:.4em;text-align:center}details{margin-top:20px;border-top:1px solid #1B2C2E;padding-top:10px}"
            + "summary{cursor:pointer;color:#BFD3D3;font-weight:600}button{margin-top:24px;width:100%;padding:15px;border:0;border-radius:14px;"
            + "background:var(--a);color:#05201E;font:600 17px system-ui;cursor:pointer}button:disabled{opacity:.6}"
            + "#msg{margin-top:18px;padding:14px;border-radius:12px;display:none}#msg.ok{display:block;background:#0F3B33;color:#BFF5EA}"
            + "#msg.err{display:block;background:#3B1416;color:#FFD2D4}code{background:#0B1416;padding:1px 6px;border-radius:6px}</style></head>"
            + "<body><main><div class=logo>▶</div><h1>Set up Ask</h1><p class=sub>The voice assistant on your TV. "
            + "Fill this in once; the TV checks it right away.</p><form id=f>"
            + "<label>PIN on the TV<input class=pin name=pin inputmode=numeric autocomplete=one-time-code maxlength=7 required></label>"
            + "<label>LiteLLM address<input name=url type=url value=\"{{url}}\" placeholder=https://litellm.example.com required>"
            + "<small>Any OpenAI-compatible server works. <code>/v1</code> is added for you.</small></label>"
            + "<label>Key<input name=key type=password placeholder=\"{{key_hint}}\" autocomplete=off>"
            + "<small>Best a key of its own for the TV, limited to the models you want there.</small></label>"
            + "<details open><summary>Extras (optional)</summary>"
            + "<label>SearXNG address<input name=search type=url value=\"{{search}}\" placeholder=https://search.example.com>"
            + "<small>Turns on web search and YouTube search. Needs the JSON format enabled.</small></label>"
            + "<label>ntfy topic<input name=ntfy type=url value=\"{{ntfy}}\" placeholder=https://ntfy.sh/your-topic>"
            + "<small>\"Send that to my phone\".</small></label>"
            + "<label>ntfy token<input name=ntfy_token type=password placeholder=\"{{ntfy_hint}}\" autocomplete=off></label>"
            + "<label>Speech language<input name=language value=\"{{language}}\" placeholder=\"en-US, de-DE … empty: the TV's\"></label>"
            + "<label>Extra instructions<textarea name=prompt placeholder=\"e.g. Keep answers short and family-friendly.\">{{prompt}}</textarea></label>"
            + "<label>Routines<textarea name=routines placeholder=\"bed time: play brown noise from https://youtube.com/playlist?list=… at volume 15 and turn the TV off in 90 minutes\">{{routines}}</textarea>"
            + "<small>One per line, <code>name: what to do</code>. Saying the name runs it. You can also make them by voice.</small></label>"
            + "<label>Webhooks<textarea name=hooks placeholder='[{\"name\":\"movie night\",\"description\":\"Dim the lights for a film\",\"url\":\"https://n8n.example/webhook/movie\"}]'>{{hooks}}</textarea>"
            + "<small>Each one becomes something you can ask for. POSTed with <code>{\"input\":\"…\"}</code>.</small></label>"
            + "</details><button id=b>Save and check</button><div id=msg></div></form></main><script>"
            + "const f=document.getElementById('f'),m=document.getElementById('msg'),b=document.getElementById('b');"
            + "f.onsubmit=async e=>{e.preventDefault();b.disabled=true;b.textContent='Checking…';m.className='';"
            + "try{const r=await fetch('/save',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
            + "body:new URLSearchParams(new FormData(f))});const j=await r.json();"
            + "if(j.ok){m.className='ok';m.textContent='Connected: '+j.models.length+' models. Now pick the default one on the TV.';b.textContent='Saved ✓'}"
            + "else{m.className='err';m.textContent=j.error;b.disabled=false;b.textContent='Save and check'}}"
            + "catch(x){m.className='err';m.textContent='The TV stopped answering. Is its setup screen still open?';b.disabled=false;b.textContent='Save and check'}};"
            + "</script></body></html>";

    // ---- plumbing -----------------------------------------------------------------------------------

    private String newPin() { return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)); }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
                // skip
            }
        }
        return out;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) return null;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    /** The TV's address on the home network: Wi-Fi or Ethernet, never the tailnet or loopback. */
    static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException e) {
            // none
        }
        return null;
    }
}
