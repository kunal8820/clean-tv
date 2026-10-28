package io.github.nitzzzu.voicechat;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Optional (install.sh VOICE_KEY=1). Makes the remote's mic button open Ask from any app instead of the TV's
 * search, and lets Ask press Back, put the TV to sleep and read the screen behind its panel. It filters key events
 * only to take the mic key: every other key is passed on untouched. Force-stopping the app switches it off.
 */
public class KeyService extends AccessibilityService {
    private static volatile KeyService instance;
    private static volatile String front;
    private final Handler main = new Handler(Looper.getMainLooper());

    static boolean connected() { return instance != null; }

    /** The package of the app the user was in before Ask came up, or null. */
    static String frontApp() { return instance == null ? null : front; }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        if (k != KeyEvent.KEYCODE_SEARCH && k != KeyEvent.KEYCODE_VOICE_ASSIST && k != KeyEvent.KEYCODE_ASSIST) {
            return false;
        }
        // Take both halves of the press, so the TV's search never sees it; open Ask on the release.
        if (event.getAction() == KeyEvent.ACTION_UP) {
            startActivity(new Intent(this, ChatActivity.class)
                    .setAction(ChatActivity.ACTION_TALK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui") || pkg.equals("android")) return;
        front = pkg;
    }

    @Override
    public void onInterrupt() {}

    /** Back, once the panel has gone (it would otherwise take the Back itself). */
    static boolean back() {
        KeyService s = instance;
        if (s == null) return false;
        s.main.postDelayed(() -> s.performGlobalAction(GLOBAL_ACTION_BACK), 600);
        return true;
    }

    /** Sleep: the lock-screen action puts an Android TV into standby. */
    static boolean sleep() {
        KeyService s = instance;
        if (s == null || android.os.Build.VERSION.SDK_INT < 28) return false;
        s.main.postDelayed(() -> s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN), 1500);
        return true;
    }

    /** Which element to press: given an element's text, folded (lower case, letters and digits). */
    interface Match { boolean test(String folded, String raw); }

    /**
     * Presses the first element whose text matches, in the app behind Ask, once Ask's panel has gone. The app may
     * still be loading (Stremio fetching streams), so it looks again every half second until timeoutMs.
     */
    static boolean clickWhen(String pkg, Match match, long timeoutMs) {
        KeyService s = instance;
        if (s == null) return false;
        long until = System.currentTimeMillis() + timeoutMs;
        s.main.postDelayed(new Runnable() {
            @Override public void run() {
                if (s.tryClick(pkg, match)) return;
                if (System.currentTimeMillis() < until) s.main.postDelayed(this, 500);
            }
        }, 800);
        return true;
    }

    /** Presses the n-th (1-based) element that matches in pkg's window. */
    static boolean clickNth(String pkg, Match match, int n) {
        KeyService s = instance;
        if (s == null || n < 1) return false;
        try {
            for (AccessibilityWindowInfo w : s.getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) continue;
                java.util.List<AccessibilityNodeInfo> hits = new java.util.ArrayList<>();
                all(root, match, 0, hits);
                if (n <= hits.size()) return press(hits.get(n - 1));
            }
        } catch (RuntimeException e) {
            // window changed
        }
        return false;
    }

    private static void all(AccessibilityNodeInfo n, Match match, int depth, java.util.List<AccessibilityNodeInfo> out) {
        if (n == null || depth > 120) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0 && match.test(Tools.fold(t.toString()), t.toString())) {
            out.add(n);
            return;
        }
        for (int i = 0; i < n.getChildCount(); i++) all(n.getChild(i), match, depth + 1, out);
    }

    /** A spoken target ("Settings", "the 4.33 GB stream") as a Match on the words it shares with an element. */
    static Match textMatch(String target) {
        String t = Tools.fold(target).replaceAll("\\b(the|a|an|click|press|select|open|play|button|stream|on)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return (f, r) -> false;
        return (f, r) -> f.contains(t) || (t.length() > 3 && f.replace(" ", "").contains(t.replace(" ", "")));
    }

    /** Whether an element that matches is on screen now, in an app other than Ask. */
    static boolean onScreen(Match match) {
        KeyService s = instance;
        if (s == null) return false;
        try {
            for (AccessibilityWindowInfo w : s.getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || root.getPackageName().toString().equals(s.getPackageName())) continue;
                if (find(root, match, 0) != null) return true;
            }
        } catch (RuntimeException e) {
            // changed under us
        }
        return false;
    }

    private boolean tryClick(String pkg, Match match) {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence p = root.getPackageName();
                if (p == null || p.toString().equals(getPackageName())) continue;
                if (pkg != null && !pkg.equals(p.toString())) continue;
                AccessibilityNodeInfo hit = find(root, match, 0);
                if (hit != null) return press(hit);
            }
        } catch (RuntimeException e) {
            // the window changed under us: try again next time
        }
        return false;
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo n, Match match, int depth) {
        if (n == null || depth > 120) return null;
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if (t != null && t.length() > 0 && match.test(Tools.fold(t.toString()), t.toString())) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = find(n.getChild(i), match, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Clicks the element, or the nearest clickable parent; a focus-only element (a TV tab) gets the focus. */
    private static boolean press(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo focusable = null;
        for (AccessibilityNodeInfo c = n; c != null; c = c.getParent()) {
            if (c.isClickable()) {
                c.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            if (focusable == null && c.isFocusable()) focusable = c;
        }
        return focusable != null && focusable.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    /** The text of the app window behind Ask's panel. */
    static String screenText() {
        KeyService s = instance;
        if (s == null) return "Reading the screen is not switched on.";
        StringBuilder b = new StringBuilder();
        try {
            List<AccessibilityWindowInfo> windows = s.getWindows();
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg != null && pkg.toString().equals(s.getPackageName())) continue;
                collect(root, b, 0);
                if (b.length() > 0) break;
            }
        } catch (RuntimeException e) {
            return "Couldn't read the screen.";
        }
        String text = b.toString().trim();
        if (text.isEmpty()) return "There is no readable text on the screen (video and pictures can't be read).";
        return Net.untrusted(text.length() > 4000 ? text.substring(0, 4000) + " …" : text, "the screen");
    }

    private static void collect(AccessibilityNodeInfo n, StringBuilder b, int depth) {
        if (n == null || depth > 120 || b.length() > 4000) return;
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        // Not isVisibleToUser: what is under Ask's own panel counts as on screen.
        if (t != null && t.toString().trim().length() > 0) b.append(t.toString().trim()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), b, depth + 1);
    }
}
