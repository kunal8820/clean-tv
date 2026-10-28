package io.github.nitzzzu.voicechat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ask: a panel over whatever is on screen. It listens as soon as it opens (OK or the mic button talks again), sends
 * the words to LiteLLM with the TV's tools, shows the answer as it streams and reads it aloud sentence by sentence.
 * A tool that opens something else (an app, a film, an input) closes the panel. Up reaches New chat, the model and
 * the voice switch.
 */
public class ChatActivity extends Activity {
    static final String ACTION_TALK = "io.github.nitzzzu.voicechat.TALK";
    /** Ask the question SetupProvider stored (typed over ADB); the intent itself carries no text. */
    static final String ACTION_ASK_PENDING = "io.github.nitzzzu.voicechat.ASK_PENDING";

    private enum State { IDLE, LISTENING, THINKING, SPEAKING }

    private static final int ACCENT = 0xFF2BB3A3;
    private static final int RED = 0xFFE5484D;
    /** Conversation turns kept as context (a turn is a question with its tool calls and answer). */
    private static final int MAX_TURNS = 8;
    private static final int MAX_ROUNDS = 6;
    /** Opened with the mic key over another app: the panel leaves this long after the answer is read. */
    private static final long IDLE_CLOSE_MS = 12000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Settings settings;
    private Tools tools;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private ToneGenerator tones;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private Llm.Call call;

    private State state = State.IDLE;
    private final List<JSONArray> turns = new ArrayList<>();
    private final List<String> models = new ArrayList<>();
    private String model = "";
    private boolean overApp;

    private ScrollView scroll;
    private LinearLayout transcript;
    private TextView status, liveYou, modelButton, voiceButton;
    private View orb, talkTarget;
    private LinearLayout buttons;
    private FrameLayout picker;

    /** The bubble streaming now: raw text, how much of its visible text went to the voice. */
    private final StringBuilder raw = new StringBuilder();
    private TextView answerView;
    private int spokenUpTo;
    private int utterances;
    private boolean speaking;

    private final Runnable idleClose = () -> {
        if (state == State.IDLE && overApp) finish();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        tools = new Tools(this, settings);
        audio = getSystemService(AudioManager.class);
        setContentView(buildLayout());
        // Only as tall as the panel: the app behind stays on screen for accessibility (read_screen), and the window
        // draws nothing it doesn't need.
        getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        getWindow().setGravity(Gravity.BOTTOM);
        // Not modal: accessibility lists windows only down to the first modal one, so without this the app behind is
        // invisible to read_screen.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        try {
            tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 35);
        } catch (RuntimeException e) {
            tones = null;
        }
        tts = new TextToSpeech(this, code -> {
            ttsReady = code == TextToSpeech.SUCCESS;
            if (ttsReady && !settings.language().isEmpty()) tts.setLanguage(Locale.forLanguageTag(settings.language()));
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { main.post(() -> spoke(id)); }
            @Override public void onError(String id) { main.post(() -> spoke(id)); }
        });
        overApp = isTalk(getIntent());
        showVoice();
        if (settings.ready()) main.post(ACTION_ASK_PENDING.equals(getIntent().getAction()) ? this::askPending : this::talk);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (ACTION_ASK_PENDING.equals(intent.getAction())) askPending();
        else if (isTalk(intent)) talk();
    }

    @Override
    protected void onResume() {
        super.onResume();
        talkTarget.requestFocus();
        if (!settings.ready()) {
            // First run: the setup screen, then back here.
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        if (models.isEmpty()) loadModels();
        if (state == State.IDLE && status.getText().length() == 0) show(State.IDLE, getString(R.string.hint));
    }

    @Override
    protected void onPause() {
        // Nothing listens or talks behind another app.
        stopAll();
        show(State.IDLE, getString(R.string.hint));
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (recognizer != null) recognizer.destroy();
        tts.shutdown();
        if (tones != null) tones.release();
        super.onDestroy();
    }

    /** Opened by the mic button: through KeyService (ACTION_TALK) or as the TV's assist activity. */
    private static boolean isTalk(Intent i) {
        String a = i == null ? null : i.getAction();
        return ACTION_TALK.equals(a) || Intent.ACTION_ASSIST.equals(a) || Intent.ACTION_SEARCH_LONG_PRESS.equals(a)
                || Intent.ACTION_VOICE_COMMAND.equals(a) || "android.speech.action.WEB_SEARCH".equals(a);
    }

    // ---- keys ---------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        // The mic key always talks, wherever the focus is.
        if (k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST) {
            if (e.getAction() == KeyEvent.ACTION_UP) talk();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) main.removeCallbacks(idleClose);
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                scroll.smoothScrollBy(0, -dp(160));
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                scroll.smoothScrollBy(0, dp(160));
                return true;
            case KeyEvent.KEYCODE_MENU:
                newChat();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (closePicker()) return true;
                if (state != State.IDLE) {
                    stopAll();
                    show(State.IDLE, getString(R.string.hint));
                    return true;
                }
                finish();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onSearchRequested() {
        talk();
        return true;
    }

    /** Talk while idle; while listening, stop and send; while thinking or speaking, cut in and listen again. */
    private void talk() {
        if (!settings.ready()) return;
        main.removeCallbacks(idleClose);
        if (state == State.LISTENING) {
            if (recognizer != null) recognizer.stopListening();
            return;
        }
        stopAll();
        listen();
    }

    private void askPending() {
        String q = settings.takePendingAsk();
        if (q == null || !settings.ready()) return;
        if (models.isEmpty()) loadModels();
        stopAll();
        addYou(q);
        ask(q);
    }

    // ---- listening ----------------------------------------------------------------------------------

    private void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            show(State.IDLE, getString(R.string.no_mic));
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            show(State.IDLE, getString(R.string.no_recognizer));
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        if (!settings.language().isEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.language());
        // What is playing pauses while you speak, so the microphone hears you and not the film.
        takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        tone(ToneGenerator.TONE_PROP_BEEP);
        liveYou = addYou("");
        show(State.LISTENING, getString(R.string.listening));
        recognizer.startListening(i);
    }

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEvent(int type, Bundle b) {}

        @Override
        public void onEndOfSpeech() {
            orb.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        }

        @Override
        public void onRmsChanged(float rms) {
            if (state != State.LISTENING) return;
            float s = 1f + Math.max(0f, Math.min(10f, rms)) / 10f;
            orb.animate().scaleX(s).scaleY(s).setDuration(80).start();
        }

        @Override
        public void onPartialResults(Bundle b) {
            String t = first(b);
            if (t != null && liveYou != null && !t.trim().isEmpty()) {
                liveYou.setText(t);
                liveYou.setAlpha(0.75f);
                scrollToEnd();
            }
        }

        @Override
        public void onResults(Bundle b) {
            if (state != State.LISTENING) return;
            String t = first(b);
            if (t == null || t.trim().isEmpty()) {
                heardNothing();
                return;
            }
            tone(ToneGenerator.TONE_PROP_ACK);
            liveYou.setText(t.trim());
            liveYou.setAlpha(1f);
            liveYou = null;
            ask(t.trim());
        }

        @Override
        public void onError(int error) {
            if (state != State.LISTENING) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                dropLiveYou();
                dropFocus();
                show(State.IDLE, getString(R.string.no_mic));
            } else if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                heardNothing();
            } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || error == SpeechRecognizer.ERROR_SERVER) {
                dropLiveYou();
                dropFocus();
                show(State.IDLE, getString(R.string.recognizer_offline));
            } else {
                dropLiveYou();
                dropFocus();
                show(State.IDLE, getString(R.string.hint));
            }
        }
    }

    private void heardNothing() {
        dropLiveYou();
        dropFocus();
        show(State.IDLE, getString(R.string.heard_nothing));
        scheduleClose();
    }

    private static String first(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    private void dropLiveYou() {
        if (liveYou != null) transcript.removeView((View) liveYou.getParent());
        liveYou = null;
    }

    // ---- asking -------------------------------------------------------------------------------------

    private void ask(String question) {
        tools.utterance(question);
        JSONArray turn = new JSONArray();
        // A routine's name runs its request: matched here, so it never depends on the model noticing.
        String routine = Routines.match(settings, question);
        if (routine != null) {
            turn.put(msg("user", "Run my \"" + routine + "\" routine now: " + Routines.all(settings).get(routine)
                    + "\n(Do every part. Don't describe what you did.)"));
        } else {
            turn.put(msg("user", question));
        }
        turns.add(turn);
        while (turns.size() > MAX_TURNS) turns.remove(0);
        newAnswer();
        if (routine != null) addChip("Routine · " + routine);
        show(State.THINKING, getString(R.string.thinking));
        Llm.Call c = new Llm.Call();
        call = c;
        String m = model;
        io.execute(() -> run(c, m, turn));
    }

    /** The agent loop, off the main thread: ask, run the tools it calls, ask again, until it answers. */
    private void run(Llm.Call c, String m, JSONArray turn) {
        try {
            JSONArray defs = tools.definitions();
            Set<String> names = tools.names();
            for (int round = 0; round < MAX_ROUNDS && !c.cancelled(); round++) {
                JSONArray messages = messages();
                Llm.Reply reply;
                try {
                    reply = Llm.chat(settings, m, messages, defs, c, text -> main.post(() -> {
                        if (c == call) streamed(text);
                    }));
                } catch (IOException e) {
                    // A model that takes no tools (some on OpenRouter): answer as plain chat rather than fail.
                    String why = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                    if (c.cancelled() || !why.contains("tool")) throw e;
                    defs = new JSONArray();
                    reply = Llm.chat(settings, m, messages, defs, c, text -> main.post(() -> {
                        if (c == call) streamed(text);
                    }));
                }
                if (c.cancelled()) return;
                List<Llm.ToolCall> calls = new ArrayList<>(reply.calls);
                if (calls.isEmpty()) ToolText.rescue(reply.text.toString(), names, calls);
                String said = clean(ToolText.beforeCalls(reply.text.toString())).trim();
                if (calls.isEmpty()) {
                    synchronized (turns) { turn.put(msg("assistant", said)); }
                    main.post(() -> { if (c == call) answered(said); });
                    return;
                }
                calls = narrow(calls);
                JSONObject assistant = msg("assistant", said);
                JSONArray tc = new JSONArray();
                for (Llm.ToolCall t : calls) {
                    if (t.id == null) t.id = "call_" + round + "_" + tc.length();
                    tc.put(new JSONObject().put("id", t.id).put("type", "function").put("function",
                            new JSONObject().put("name", t.name).put("arguments", t.arguments().toString())));
                }
                assistant.put("tool_calls", tc);
                synchronized (turns) { turn.put(assistant); }
                Tools.Result leaving = null;
                for (Llm.ToolCall t : calls) {
                    if (c.cancelled()) return;
                    JSONObject args = t.arguments();
                    String chip = tools.chip(t.name, args);
                    main.post(() -> { if (c == call) addChip(chip); });
                    Tools.Result r = tools.run(t.name, args);
                    if (android.util.Log.isLoggable("Ask", android.util.Log.DEBUG)) {
                        android.util.Log.d("Ask", t.name + " " + args + " -> " + r.text);
                    }
                    synchronized (turns) {
                        turn.put(new JSONObject().put("role", "tool").put("tool_call_id", t.id).put("content", r.text));
                    }
                    if (r.leaves) leaving = r;
                }
                if (leaving != null) {
                    String done = leaving.text;
                    synchronized (turns) { turn.put(msg("assistant", done)); }
                    main.post(() -> { if (c == call) left(); });
                    return;
                }
                main.post(() -> {
                    if (c != call) return;
                    // use_model may have switched the model for the next question.
                    model = settings.model();
                    showModel();
                    newAnswer();
                });
            }
            main.post(() -> { if (c == call) answered(""); });
        } catch (IOException e) {
            String msg = friendly(e);
            main.post(() -> { if (c == call && !c.cancelled()) failed(msg); });
        } catch (JSONException e) {
            main.post(() -> { if (c == call) failed("a malformed tool call"); });
        }
    }

    /** One app to open per turn: a tool that opens something specific beats open_app; leaving tools go last. */
    private static List<Llm.ToolCall> narrow(List<Llm.ToolCall> calls) {
        boolean specific = false;
        for (Llm.ToolCall t : calls) {
            if (Tools.LEAVING.contains(t.name) && !t.name.equals("open_app")) specific = true;
        }
        List<Llm.ToolCall> stay = new ArrayList<>(), leave = new ArrayList<>();
        for (Llm.ToolCall t : calls) {
            if (t.name == null) continue;
            if (specific && t.name.equals("open_app")) continue;
            (Tools.LEAVING.contains(t.name) ? leave : stay).add(t);
        }
        if (leave.size() > 1) leave = leave.subList(leave.size() - 1, leave.size());
        stay.addAll(leave);
        return stay;
    }

    /** The system prompt with this moment's context, then the kept turns. */
    private JSONArray messages() {
        JSONArray out = new JSONArray();
        String prompt = getString(R.string.system_prompt);
        if (!settings.prompt().isEmpty()) prompt += "\n" + settings.prompt();
        out.put(msg("system", prompt + "\n\n" + tools.context()));
        synchronized (turns) {
            for (JSONArray t : turns) {
                for (int i = 0; i < t.length(); i++) out.put(t.opt(i));
            }
        }
        return out;
    }

    private void streamed(String text) {
        raw.append(text);
        String visible = clean(ToolText.beforeCalls(raw.toString()));
        if (visible.trim().isEmpty()) return;
        answerView.setText(visible.trim());
        ((View) answerView.getParent()).setVisibility(View.VISIBLE);
        scrollToEnd();
        if (state == State.THINKING) show(State.SPEAKING, getString(settings.voice() ? R.string.speaking : R.string.answering));
        // Read each finished sentence as soon as it is there, so the voice starts before the answer is complete.
        int end = lastSentenceEnd(visible, spokenUpTo);
        if (end > spokenUpTo) {
            say(visible.substring(spokenUpTo, end));
            spokenUpTo = end;
        }
    }

    private void answered(String said) {
        call = null;
        String visible = clean(ToolText.beforeCalls(raw.toString()));
        if (spokenUpTo < visible.length()) say(visible.substring(spokenUpTo));
        spokenUpTo = visible.length();
        if (said.isEmpty() && visible.trim().isEmpty()) {
            dropAnswerIfEmpty();
            finishTurn();
            return;
        }
        if (!speaking) finishTurn();
        else show(State.SPEAKING, getString(R.string.speaking));
    }

    /** A tool opened another screen: get out of its way. */
    private void left() {
        call = null;
        stopSpeech();
        dropFocus();
        finish();
    }

    private void failed(String message) {
        call = null;
        dropAnswerIfEmpty();
        dropFocus();
        show(State.IDLE, message);
    }

    /** The answer is complete and read: listen again if it asked something, else wait (and leave, over an app). */
    private void finishTurn() {
        dropFocus();
        String last = answerView == null ? "" : answerView.getText().toString().trim();
        if (last.endsWith("?") && settings.voice()) {
            main.postDelayed(() -> { if (state == State.IDLE && !isFinishing()) listen(); }, 300);
            show(State.IDLE, "");
            return;
        }
        show(State.IDLE, getString(R.string.hint));
        scheduleClose();
    }

    private void scheduleClose() {
        main.removeCallbacks(idleClose);
        if (overApp) main.postDelayed(idleClose, IDLE_CLOSE_MS);
    }

    private String friendly(IOException e) {
        if (e instanceof UnknownHostException || e instanceof ConnectException || e instanceof SocketTimeoutException) {
            return getString(R.string.unreachable);
        }
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return getString(R.string.error, m);
    }

    /** The answer without a thinking model's <think>…</think> block, or nothing while that block is still open. */
    static String clean(String s) {
        int open = s.indexOf("<think>");
        while (open >= 0) {
            int close = s.indexOf("</think>", open);
            if (close < 0) return s.substring(0, open);
            s = s.substring(0, open) + s.substring(close + 8);
            open = s.indexOf("<think>");
        }
        return s.replaceAll("[*#`]|(?m)^\\s*[-•]\\s+", "");
    }

    /** The end of the last complete sentence after from, or from when there is none yet. */
    static int lastSentenceEnd(String s, int from) {
        int end = from;
        for (int i = from; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') && Character.isWhitespace(s.charAt(i + 1))) {
                end = i + 1;
            }
        }
        return end;
    }

    // ---- speaking -----------------------------------------------------------------------------------

    private void say(String text) {
        if (!ttsReady || !settings.voice() || text.trim().isEmpty()) return;
        if (!speaking) takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        speaking = true;
        utterances++;
        tts.speak(text.trim(), TextToSpeech.QUEUE_ADD, null, "u" + utterances);
    }

    private void spoke(String id) {
        if (!("u" + utterances).equals(id)) return;
        speaking = false;
        if (state == State.SPEAKING && call == null) finishTurn();
    }

    private void stopSpeech() {
        speaking = false;
        if (ttsReady) tts.stop();
    }

    private void stopAll() {
        main.removeCallbacks(idleClose);
        if (call != null) {
            call.cancel();
            call = null;
            dropAnswerIfEmpty();
        }
        if (state == State.LISTENING && recognizer != null) {
            recognizer.cancel();
            dropLiveYou();
        }
        stopSpeech();
        dropFocus();
        state = State.IDLE;
        orb.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
    }

    private void takeFocus(int kind) {
        dropFocus();
        focus = new AudioFocusRequest.Builder(kind)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build();
        audio.requestAudioFocus(focus);
    }

    private void dropFocus() {
        if (focus != null) audio.abandonAudioFocusRequest(focus);
        focus = null;
    }

    private void tone(int t) {
        if (tones != null) tones.startTone(t, 120);
    }

    // ---- models and history -------------------------------------------------------------------------

    private void loadModels() {
        io.execute(() -> {
            try {
                List<String> list = Llm.models(settings);
                main.post(() -> {
                    models.clear();
                    models.addAll(list);
                    useModel(pickModel(settings.model(), models));
                });
            } catch (IOException e) {
                String msg = friendly(e);
                main.post(() -> {
                    model = settings.model();
                    showModel();
                    if (state == State.IDLE) show(State.IDLE, msg);
                });
            }
        });
        model = settings.model();
        showModel();
    }

    /** The model chosen in setup if the server still has it, else the server's first. */
    static String pickModel(String saved, List<String> models) {
        if (models.isEmpty() || models.contains(saved)) return saved;
        return models.get(0);
    }

    private void cycleModel(int step) {
        if (models.size() < 2) return;
        int i = models.indexOf(model);
        useModel(models.get(((i < 0 ? 0 : i) + step + models.size()) % models.size()));
    }

    private void useModel(String m) {
        model = m;
        settings.model(m);
        tools.models(models);
        showModel();
    }

    /** The quick switcher: every model on the server in a list over the panel, the current one ticked. */
    private void openPicker() {
        if (picker != null) return;
        if (models.isEmpty()) {
            loadModels();
            return;
        }
        FrameLayout root = (FrameLayout) findViewById(android.R.id.content);
        picker = new FrameLayout(this);
        picker.setBackgroundColor(0xB3000000);
        picker.setClickable(true);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(20), dp(24), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0xF2121A1C);
        card.setBackground(bg);
        TextView head = text(getString(R.string.pick_model), 15, 0x99FFFFFF, "sans-serif-medium");
        head.setLetterSpacing(0.12f);
        head.setAllCaps(true);
        head.setPadding(dp(14), 0, 0, dp(10));
        card.addView(head);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        View current = null;
        for (String m : models) {
            TextView row = text((m.equals(model) ? "✓   " : "      ") + m, 20, Color.WHITE, "sans-serif");
            row.setPadding(dp(14), dp(10), dp(24), dp(10));
            row.setFocusable(true);
            row.setClickable(true);
            GradientDrawable rb = new GradientDrawable();
            rb.setCornerRadius(dp(12));
            rb.setColor(0);
            row.setBackground(rb);
            row.setOnFocusChangeListener((v, has) -> {
                rb.setColor(has ? Color.WHITE : 0);
                row.setTextColor(has ? Color.BLACK : Color.WHITE);
            });
            // Left and right jump ten rows: a long list (OpenRouter) is quick to cross.
            row.setOnKeyListener((v, k, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int i = list.indexOfChild(v) + (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : -10);
                list.getChildAt(Math.max(0, Math.min(list.getChildCount() - 1, i))).requestFocus();
                return true;
            });
            row.setOnClickListener(v -> {
                useModel(m);
                closePicker();
                show(State.IDLE, getString(R.string.now_using, m));
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (m.equals(model) || current == null) current = row;
        }
        card.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        picker.addView(card, new FrameLayout.LayoutParams(dp(520), -2, Gravity.CENTER));
        root.addView(picker, new FrameLayout.LayoutParams(-1, -1));
        // Tall lists scroll inside the card, which never covers more than most of the screen.
        card.post(() -> {
            int max = root.getHeight() - dp(80);
            if (card.getHeight() > max) sv.getLayoutParams().height = max - dp(80);
            sv.requestLayout();
        });
        View focus = current;
        focus.post(focus::requestFocus);
    }

    private boolean closePicker() {
        if (picker == null) return false;
        ((FrameLayout) findViewById(android.R.id.content)).removeView(picker);
        picker = null;
        modelButton.requestFocus();
        return true;
    }

    private void showModel() {
        modelButton.setText(model.isEmpty() ? "Model" : "◂  " + model + "  ▸");
    }

    private void showVoice() {
        voiceButton.setText(settings.voice() ? "🔊  Voice on" : "🔇  Voice off");
    }

    private void newChat() {
        stopAll();
        synchronized (turns) { turns.clear(); }
        transcript.removeAllViews();
        show(State.IDLE, getString(R.string.new_chat));
    }

    private static JSONObject msg(String role, String content) {
        try {
            return new JSONObject().put("role", role).put("content", content);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- views --------------------------------------------------------------------------------------

    private void show(State s, String text) {
        state = s;
        status.setText(text);
        getWindow().getDecorView().setKeepScreenOn(s != State.IDLE);
        int color = s == State.LISTENING ? RED : s == State.IDLE ? 0x40FFFFFF : ACCENT;
        ((GradientDrawable) orb.getBackground()).setColor(color);
        if (s == State.THINKING) pulse();
    }

    private void pulse() {
        if (state != State.THINKING) {
            orb.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start();
            return;
        }
        boolean up = orb.getScaleX() < 1.2f;
        orb.animate().scaleX(up ? 1.35f : 1f).scaleY(up ? 1.35f : 1f).alpha(up ? 0.6f : 1f).setDuration(450)
                .withEndAction(this::pulse).start();
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        // The film stays visible above the panel, which is nearly opaque so a busy picture never shows through
        // the text; a soft fade joins the two.
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00060B0C, 0xB3060B0C, 0xF2060B0C}));
        sheet.addView(fade, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(64), dp(4), dp(64), dp(28));
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xF2060B0C, 0xFA060B0C}));
        sheet.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.app_name), 22, ACCENT, "sans-serif-medium");
        title.setLetterSpacing(0.04f);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(button("✚  " + getString(R.string.new_chat), v -> newChat()));
        modelButton = button("Model", v -> openPicker());
        modelButton.setOnKeyListener((v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) { cycleModel(-1); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { cycleModel(1); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) { talkTarget.requestFocus(); return true; }
            return false;
        });
        buttons.addView(modelButton);
        voiceButton = button("", v -> {
            settings.voice(!settings.voice());
            if (!settings.voice()) stopSpeech();
            showVoice();
        });
        buttons.addView(voiceButton);
        buttons.addView(button("⚙  " + getString(R.string.setup), v -> startActivity(new Intent(this, SetupActivity.class))));
        top.addView(buttons);
        panel.addView(top);

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(32));
        transcript = new LinearLayout(this);
        transcript.setOrientation(LinearLayout.VERTICAL);
        transcript.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(transcript);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(250)));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(10), 0, 0);
        FrameLayout orbBox = new FrameLayout(this);
        orb = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0x40FFFFFF);
        orb.setBackground(dot);
        orbBox.addView(orb, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(dp(32), dp(32));
        op.rightMargin = dp(12);
        bottom.addView(orbBox, op);
        status = text("", 18, Color.WHITE, "sans-serif-medium");
        status.setMaxLines(3);
        bottom.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        bottom.addView(text(getString(R.string.hint_keys), 13, 0x80FFFFFF, "sans-serif"));
        panel.addView(bottom);

        // Holds the focus while no button has it: OK on it talks, Up goes to the buttons.
        talkTarget = new View(this);
        talkTarget.setFocusable(true);
        talkTarget.setFocusableInTouchMode(true);
        talkTarget.setOnKeyListener((v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (e.getRepeatCount() == 0) talk();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    buttons.getChildAt(0).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    scroll.smoothScrollBy(0, dp(120));
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return false;
            }
        });
        root.addView(talkTarget, new FrameLayout.LayoutParams(1, 1));
        return root;
    }

    private TextView button(String label, View.OnClickListener click) {
        TextView b = text(label, 14, 0xCCFFFFFF, "sans-serif-medium");
        b.setPadding(dp(14), dp(7), dp(14), dp(7));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x1AFFFFFF);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Color.WHITE : 0x1AFFFFFF);
            b.setTextColor(has ? Color.BLACK : 0xCCFFFFFF);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(10);
        b.setLayoutParams(lp);
        // Down from a button goes back to talking.
        b.setOnKeyListener((v, k, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_DPAD_DOWN) {
                talkTarget.requestFocus();
                return true;
            }
            return false;
        });
        return b;
    }

    /** The question: right-aligned, dimmed while it is still being heard. */
    private TextView addYou(String text) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.END);
        wrap.setPadding(dp(200), dp(14), 0, dp(6));
        TextView t = text(text, 20, Color.WHITE, "sans-serif-medium");
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x26FFFFFF);
        t.setBackground(bg);
        wrap.addView(t);
        transcript.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
        scrollToEnd();
        return t;
    }

    /** A new answer bubble, hidden until its first visible words arrive. */
    private void newAnswer() {
        raw.setLength(0);
        spokenUpTo = 0;
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(6), dp(120), dp(6));
        wrap.setVisibility(View.GONE);
        answerView = text("", 26, Color.WHITE, "sans-serif");
        answerView.setLineSpacing(0, 1.12f);
        wrap.addView(answerView);
        transcript.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
    }

    private void dropAnswerIfEmpty() {
        if (answerView != null && answerView.getText().length() == 0) {
            transcript.removeView((View) answerView.getParent());
        }
    }

    /** A small pill for a tool at work: what it is doing, in the accent colour. */
    private void addChip(String label) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(4), 0, dp(4));
        TextView t = text("●  " + label, 14, ACCENT, "sans-serif-medium");
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(600));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x1F2BB3A3);
        t.setBackground(bg);
        wrap.addView(t);
        // Above the answer bubble that is still empty, so the chips read before the answer they lead to.
        int at = answerView != null && ((View) answerView.getParent()).getParent() == transcript
                ? transcript.indexOfChild((View) answerView.getParent()) : transcript.getChildCount();
        transcript.addView(wrap, at, new LinearLayout.LayoutParams(-1, -2));
        scrollToEnd();
    }

    private void scrollToEnd() { scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }

    private TextView text(String s, int sp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
