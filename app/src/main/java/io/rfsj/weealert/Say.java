package io.rfsj.weealert;

import java.text.Normalizer;
import java.util.ArrayList;

/**
 * Tiny letter-to-sound rules: turns a name into syllables of phone symbols. Pure Java.
 * Symbols: vowels a e i o u @(schwa); consonants b d g p t k f v s z S(sh) Z(zh) T(th) C(ch) J(j) h m n N(ng) l r w y.
 * Good for names spelled the way they sound (most names in Latin letters). Anything else, such as other
 * scripts, becomes a stable made-up mumble. A "say it as" spelling per contact fixes the rest.
 */
final class Say {
    static final class Syl {
        final StringBuilder on = new StringBuilder(), co = new StringBuilder();
        char nuc = '@';
    }

    static boolean isV(char c) { return "aeiou@".indexOf(c) >= 0; }

    private static boolean isLatinLetter(char c) { return c >= 'a' && c <= 'z'; }

    private static boolean isVowelLetter(char c) { return "aeiouy".indexOf(c) >= 0; }

    /** Splits text into words, stripping accents from Latin letters; other scripts are kept as they are. */
    static ArrayList<String> words(String text) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD);
        boolean prevLatin = false;
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean mark = type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
            if (mark && prevLatin) continue;                       // accent on a Latin letter
            if (Character.isLetter(cp) || mark) {
                int lo = Character.toLowerCase(cp);
                cur.appendCodePoint(lo);
                prevLatin = lo < 128 && isLatinLetter((char) lo);
            } else {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                prevLatin = false;
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static void pseudo(int cp, StringBuilder out) {
        String cons = "ptkbdgmnslrhwyfz";
        out.append(cons.charAt(cp % cons.length())).append("aeiou".charAt((cp / cons.length()) % 5));
    }

    private static int vowelGroups(String w) {
        int g = 0;
        boolean in = false;
        for (int i = 0; i < w.length(); i++) {
            boolean v = isVowelLetter(w.charAt(i));
            if (v && !in) g++;
            in = v;
        }
        return g;
    }

    private static void latin(String w, StringBuilder out) {
        int n = w.length();
        boolean silentE = n > 2 && w.charAt(n - 1) == 'e' && !isVowelLetter(w.charAt(n - 2))
                && vowelGroups(w.substring(0, n - 1)) >= 1;
        for (int i = 0; i < n; ) {
            char c = w.charAt(i);
            String two = i + 1 < n ? w.substring(i, i + 2) : "";
            String sym = null;
            int adv = 2;
            switch (two) {
                case "sh": sym = "S"; break;
                case "ch": sym = "C"; break;
                case "th": sym = "T"; break;
                case "ph": sym = "f"; break;
                case "kh": sym = "k"; break;
                case "gh": sym = "g"; break;
                case "dh": sym = "d"; break;
                case "bh": sym = "b"; break;
                case "ck": sym = "k"; break;
                case "wh": sym = "w"; break;
                case "zh": sym = "Z"; break;
                case "qu": sym = "kw"; break;
                case "ee": case "ea": case "ie": sym = "i"; break;
                case "oo": case "ou": case "ue": case "ui": sym = "u"; break;
                case "ow": case "oa": sym = "o"; break;
                case "ai": case "ay": case "ei": case "ey": sym = "e"; break;
                case "au": case "aw": sym = "o"; break;
                case "ng":
                    if (i + 2 >= n || !isVowelLetter(w.charAt(i + 2))) sym = "N";
                    break;
                default: break;
            }
            if (sym == null && i + 1 < n && c == w.charAt(i + 1) && !isVowelLetter(c)) { i++; continue; } // doubled consonant
            if (sym != null) { out.append(sym); i += adv; continue; }
            switch (c) {
                case 'a': out.append('a'); break;
                case 'e': if (!(i == n - 1 && silentE)) out.append('e'); break;
                case 'i': out.append('i'); break;
                case 'o': out.append('o'); break;
                case 'u': out.append('u'); break;
                case 'y':
                    if (i > 0 && (i == n - 1 || !isVowelLetter(w.charAt(i + 1)))) out.append('i'); else out.append('y');
                    break;
                case 'c': out.append(i + 1 < n && "eiy".indexOf(w.charAt(i + 1)) >= 0 ? 's' : 'k'); break;
                case 'x': out.append("ks"); break;
                case 'q': out.append('k'); break;
                case 'j': out.append('J'); break;
                default: out.append(c); break;
            }
            i++;
        }
    }

    /** Phone symbols for the text; firstOnly keeps just the first word. */
    static String phones(String text, boolean firstOnly) {
        ArrayList<String> ws = words(text);
        StringBuilder out = new StringBuilder();
        for (String w : ws) {
            StringBuilder latinRun = new StringBuilder();
            for (int i = 0; i < w.length(); ) {
                int cp = w.codePointAt(i);
                i += Character.charCount(cp);
                if (cp < 128 && isLatinLetter((char) cp)) {
                    latinRun.append((char) cp);
                } else {
                    if (latinRun.length() > 0) { latin(latinRun.toString(), out); latinRun.setLength(0); }
                    pseudo(cp, out);
                }
            }
            if (latinRun.length() > 0) latin(latinRun.toString(), out);
            if (firstOnly) break;
            out.append(' ');
        }
        return out.toString().trim();
    }

    /** Groups phones into syllables (at most maxSyl). */
    static ArrayList<Syl> syllables(String text, boolean firstOnly, int maxSyl) {
        String p = phones(text, firstOnly).replace(" ", "");
        ArrayList<Syl> out = new ArrayList<>();
        int n = p.length();
        ArrayList<Integer> nuclei = new ArrayList<>();
        for (int i = 0; i < n; i++) if (isV(p.charAt(i))) nuclei.add(i);
        if (n == 0) return out;
        if (nuclei.isEmpty()) {                       // no vowel at all: say the consonants with a schwa
            Syl s = new Syl();
            s.on.append(p.length() > 3 ? p.substring(0, 3) : p);
            out.add(s);
            return out;
        }
        for (int k = 0; k < nuclei.size() && out.size() < maxSyl; k++) {
            int v = nuclei.get(k);
            Syl s = new Syl();
            s.nuc = p.charAt(v);
            int from = k == 0 ? 0 : nuclei.get(k - 1) + 1;
            String gap = p.substring(from, v);
            if (k == 0) {
                s.on.append(gap.length() > 3 ? gap.substring(gap.length() - 3) : gap);
            } else {
                int give = gap.length() <= 1 ? 0 : gap.length() == 2 ? 1 : gap.length() - 2;  // goes to the previous coda
                if (give > 0) out.get(out.size() - 1).co.append(gap, 0, Math.min(give, 2));
                s.on.append(gap.substring(give));
            }
            out.add(s);
        }
        if (out.size() == nuclei.size()) {            // every syllable fitted: trailing consonants end the last one
            String tail = p.substring(nuclei.get(nuclei.size() - 1) + 1);
            out.get(out.size() - 1).co.append(tail.length() > 2 ? tail.substring(0, 2) : tail);
        }
        return out;
    }
}
