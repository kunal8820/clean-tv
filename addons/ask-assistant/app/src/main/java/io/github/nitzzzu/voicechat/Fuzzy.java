package io.github.nitzzzu.voicechat;

import java.util.Locale;

/**
 * How alike two titles are, from 0 to 1, forgiving what speech recognition does to them: "the offis" and "The Office",
 * "breaking bat" and "Breaking Bad", "severns" and "Severance". Edit distance on the folded words, on the letters
 * alone, and on a rough sound key, whichever is kindest.
 */
final class Fuzzy {
    private Fuzzy() {}

    static double score(String heard, String title) {
        String a = norm(heard), b = norm(title);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        double words = ratio(a, b);
        double letters = ratio(a.replace(" ", ""), b.replace(" ", ""));
        double sound = ratio(sound(a), sound(b)) * 0.95;
        // "office" for "The Office (US)": a heard title inside the real one counts nearly as a match.
        double inside = b.contains(a) && a.length() >= 4 ? 0.9 * Math.min(1, a.length() / (double) Math.max(6, b.length()) + 0.3) : 0;
        return Math.max(Math.max(words, letters), Math.max(sound, inside));
    }

    /** Lower case, accents and punctuation gone, a leading "the"/"a" and a trailing "(US)"/year dropped. */
    static String norm(String s) {
        String f = Tools.fold(s).replaceAll("\\b(us|uk|19\\d\\d|20\\d\\d)\\b", " ").replaceAll("\\s+", " ").trim();
        return f.replaceFirst("^(the|a|an) ", "");
    }

    static double ratio(String a, String b) {
        int d = distance(a, b);
        return 1.0 - d / (double) Math.max(a.length(), b.length());
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** A rough English sound key: soft letters merged, vowels after the first dropped, doubles collapsed. */
    static String sound(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace(" ", "")
                .replace("ph", "f").replace("ck", "k").replace("qu", "kw").replace("x", "ks")
                .replace("c", "k").replace("z", "s").replace("dt", "t").replace("d", "t").replace("b", "p")
                .replace("v", "f").replace("g", "k").replace("w", "").replace("h", "").replace("y", "i");
        if (t.isEmpty()) return t;
        StringBuilder out = new StringBuilder().append(t.charAt(0));
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("aeiou".indexOf(c) >= 0) continue;
            if (c != out.charAt(out.length() - 1)) out.append(c);
        }
        return out.toString();
    }
}
