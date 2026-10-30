package io.rfsj.weealert;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-sound tuning. A sound's own settings are kept as "key=value;key=value" and laid over the global
 * settings when that sound is built. Keys are the global setting names (noteMs, fadeMs, ...) plus a few
 * that belong to one sound only: gaps and semis (the pattern itself) for tone patterns, and
 * pitch, fsc, spd, con (voices) or bpit, bspd, blen, bcon (blips) for contacts. Pure Java.
 */
final class Over {
    /** One tunable field: type 0 whole number, 1 decimal, 2 list of whole numbers, 3 list of decimals, 4 yes/no, 5 free text. */
    static final class F {
        final String key, label;
        final int type;
        F(String key, String label, int type) { this.key = key; this.label = label; this.type = type; }
    }

    static LinkedHashMap<String, String> parse(String s) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (s == null) return m;
        for (String part : s.split(";")) {
            int e = part.indexOf('=');
            if (e > 0) m.put(part.substring(0, e), part.substring(e + 1));
        }
        return m;
    }

    static String serialize(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (b.length() > 0) b.append(';');
            b.append(e.getKey()).append('=').append(e.getValue().replace(';', ' ').replace('\n', ' ').replace('\t', ' '));
        }
        return b.toString();
    }

    /** All anonymous sounds' tuning: lines "pattern key TAB overrides". */
    static LinkedHashMap<String, String> parseAll(String s) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (s == null) return m;
        for (String line : s.split("\n")) {
            int t = line.indexOf('\t');
            if (t > 0 && t + 1 < line.length()) m.put(line.substring(0, t), line.substring(t + 1));
        }
        return m;
    }

    static String serializeAll(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        return b.toString();
    }

    /** The pattern this sound really plays: its own gaps and pitches if tuned, else the original. */
    static Codebook.Pattern pattern(Codebook.Pattern base, Map<String, String> kv) {
        int[] g = base.gaps;
        double[] s = base.semis;
        if (kv.containsKey("gaps")) g = Params.parseList(kv.get("gaps"));
        if (kv.containsKey("semis")) s = Params.parseDoubles(kv.get("semis"));
        if (s.length == 0 || g.length != s.length - 1) return base;      // inconsistent: ignore rather than crash
        for (int v : g) if (v < 0) return base;
        return new Codebook.Pattern(g, s);
    }

    private static double d(Map<String, String> kv, String k, double dflt) {
        try { return kv.containsKey(k) ? Double.parseDouble(kv.get(k).trim()) : dflt; } catch (NumberFormatException e) { return dflt; }
    }

    /** A copy of the contact with its own tuning applied. */
    static Contacts.C effective(Contacts.C c) {
        Contacts.C e = c.copy();
        if (c.ov.isEmpty()) return e;
        Map<String, String> kv = parse(c.ov);
        e.pitch = d(kv, "pitch", e.pitch);
        e.fsc = Math.max(1, d(kv, "fsc", e.fsc));
        e.spd = Math.max(1, d(kv, "spd", e.spd));
        e.con = Math.floorMod((int) Math.round(d(kv, "con", e.con)), Contacts.CONTOURS);
        e.bpit = d(kv, "bpit", e.bpit);
        e.bspd = Math.max(1, d(kv, "bspd", e.bspd));
        e.blen = Math.max(1, d(kv, "blen", e.blen));
        e.bcon = Math.floorMod((int) Math.round(d(kv, "bcon", e.bcon)), Contacts.CONTOURS);
        if (kv.containsKey("gaps") || kv.containsKey("semis")) {
            Codebook.Pattern base = c.pattern();
            if (base != null) e.setPattern(pattern(base, kv));
        }
        return e;
    }

    /** Removes the content keys (the sound itself) but keeps tuning of global settings; used when a contact gets a new sound. */
    static String withoutContent(String ov) {
        LinkedHashMap<String, String> kv = parse(ov);
        for (String k : new String[]{"pitch", "fsc", "spd", "con", "bpit", "bspd", "blen", "bcon", "gaps", "semis"}) kv.remove(k);
        return serialize(kv);
    }

    static boolean has(String ov) { return ov != null && !ov.isEmpty(); }

    // ---------------------------------------------------------------- what can be tuned in each mode

    static F[] fields(String mode) {
        F levelDb = new F("levelDb", "Peak level (dBFS)", 1), fade = new F("fadeMs", "Fade-out (ms)", 0),
                rev = new F("reverse", "Play the source backwards", 4);
        switch (mode) {
            case "voices":
                return new F[]{
                        new F("pitch", "Voice pitch (semitones)", 1), new F("fsc", "Vocal tract size (%)", 1),
                        new F("spd", "Speed (%)", 1), new F("con", "Pitch contour (0 wander, 1 rising, 2 falling, 3 bouncy)", 0),
                        new F("vSyll", "Syllable length (ms)", 0), new F("vPitch", "Centre pitch (Hz)", 0),
                        new F("vBreath", "Breathiness (%)", 0), new F("vMaxSyl", "Longest name (syllables)", 0), levelDb};
            case "blips":
                return new F[]{
                        new F("bpit", "Pitch (semitones)", 1), new F("bspd", "Speed (%)", 1), new F("blen", "Blip length (%)", 1),
                        new F("bcon", "Pitch contour (0 wander, 1 rising, 2 falling, 3 bouncy)", 0),
                        new F("bNote", "Blip length base (ms)", 0), new F("vSyll", "Syllable length (ms)", 0),
                        new F("vMaxSyl", "Longest name (syllables)", 0), fade, rev, levelDb};
            case "pblips":
                return new F[]{
                        new F("pbPitch", "Voice pitch (semitones)", 1), new F("pbSpeed", "Speed of the rhythm (%)", 0),
                        new F("pbLen", "Vowel blip length (ms)", 0), new F("pbVowelDur", "Time after a vowel (ms)", 0),
                        new F("pbConsDur", "Time after a consonant (ms)", 0), new F("pbConsLen", "Consonant blip length (%)", 0),
                        new F("pbEffects", "Clicks, hisses and glides on consonants", 4),
                        new F("pbTable", "Phoneme table overrides (sym=pitch/len%/level%)", 5), fade, rev, levelDb,
                        new F("vMaxSyl", "Longest name (syllables)", 0)};
            default: // patterns and name patterns
                return new F[]{
                        new F("gaps", "Gaps between notes (ms)", 2), new F("semis", "Pitch of each note (semitones)", 3),
                        new F("noteMs", "Length of each note (ms)", 0), new F("falloff", "Each note quieter by (%)", 1),
                        new F("cut", "Cut each note when the next one starts", 4), fade, rev, levelDb};
        }
    }
}
