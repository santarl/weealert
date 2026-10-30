package io.rfsj.weealert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Phoneme blips: one consistent voice (the same source sound, pitch, speed and blip length for every
 * contact) in which every sound of the name has its own pitch, so a phoneme sounds the same in every name
 * and the rhythm follows the pronunciation. Vowels sit on a bright-to-dark pitch ladder (ee high, oo low),
 * consonants are short blips whose pitch follows the place in the mouth (lips low, tongue tip high) and
 * which start with a click, hiss, soft start or glide. Nothing is random and nothing depends on the
 * contact, only on the sounds in the name. The pitches, lengths and levels are all configurable. Pure Java.
 */
final class PBlip {
    static final String DEFAULT_TABLE = "a=0 e=3 i=6 o=-3 u=-6 @=0 "
            + "p=-4 b=-5 t=2 d=1 k=0 g=-1 C=3 J=2 "
            + "f=-2 v=-3 s=8 z=7 S=4 Z=3 T=-1 h=0 "
            + "m=-5 n=-4 N=-6 l=1 r=-1 w=-2 y=5";

    /** 0 vowel, 1 plosive or affricate, 2 fricative, 3 nasal, 4 liquid or glide. */
    static int kind(char c) {
        if ("aeiou@".indexOf(c) >= 0) return 0;
        if ("pbtdkgCJ".indexOf(c) >= 0) return 1;
        if ("fvszSZTh".indexOf(c) >= 0) return 2;
        if ("mnN".indexOf(c) >= 0) return 3;
        return 4;
    }

    private static final double[] DEF_LEN = {100, 55, 80, 90, 85};
    private static final double[] DEF_GAIN = {100, 85, 70, 90, 90};

    /** symbol to {pitch, length %, level %}; NaN = not given. Later text overrides earlier text. */
    static Map<Character, double[]> table(String spec) {
        HashMap<Character, double[]> m = new HashMap<>();
        for (String text : new String[]{DEFAULT_TABLE, spec == null ? "" : spec}) {
            for (String tok : text.split("[\\s,;]+")) {
                int e = tok.indexOf('=');
                if (e != 1 || tok.length() < 3) continue;
                char sym = tok.charAt(0);
                String[] f = tok.substring(2).split("/");
                double[] v = m.containsKey(sym) ? m.get(sym).clone() : new double[]{Double.NaN, Double.NaN, Double.NaN};
                try {
                    for (int i = 0; i < 3 && i < f.length; i++) if (!f[i].isEmpty()) v[i] = Double.parseDouble(f[i]);
                } catch (NumberFormatException ex) { continue; }
                m.put(sym, v);
            }
        }
        return m;
    }

    private static boolean has(double v) { return !Double.isNaN(v); }

    static float[] render(String phones, Params p, float[] raw, int rate) {
        if (phones.isEmpty() || raw.length == 0) return new float[0];
        Map<Character, double[]> tbl = table(p.pbTable);
        double maxLen = 100;
        for (double[] v : tbl.values()) if (has(v[1])) maxLen = Math.max(maxLen, v[1]);
        for (double d : DEF_LEN) maxLen = Math.max(maxLen, d * Math.max(1, p.pbConsLen / 100.0));
        Params pb = p.copy();
        pb.noteMs = (int) Math.max(1, Math.round(p.pbLen * maxLen / 100.0));
        pb.rate = 0;
        Synth.Note note = Synth.prepare(raw, rate, pb);
        float[] src = note.data;
        if (src.length < 64) return new float[0];
        double speed = Math.max(1, p.pbSpeed) / 100.0;
        ArrayList<float[]> notes = new ArrayList<>();
        ArrayList<Integer> at = new ArrayList<>();
        double t = 0;
        int total = 0;
        for (int i = 0; i < phones.length(); i++) {
            char ch = phones.charAt(i);
            if (ch == ' ') { t += 2 * p.pbConsDur / speed; continue; }
            int k = kind(ch);
            double[] e = tbl.get(ch);
            double pitch = p.pbPitch + (e != null && has(e[0]) ? e[0] : 0);
            double lenPct = e != null && has(e[1]) ? e[1] : DEF_LEN[k] * (k > 0 ? p.pbConsLen / 100.0 : 1);
            double gain = (e != null && has(e[2]) ? e[2] : DEF_GAIN[k]) / 100.0;
            float[] b = Blip.blip(src, rate, pitch, p.pbEffects && k > 0 ? ch : 0, ch * 0x9e3779b1, (float) gain);
            int want = (int) Math.max(8, Math.round((long) p.pbLen * rate / 1000.0 * lenPct / 100.0));
            if (want < b.length) {
                b = java.util.Arrays.copyOf(b, want);
                int fo = Math.min(want / 2, Math.max(rate / 250, (int) ((long) p.fadeMs * rate / 1000)));
                for (int j = 0; j < fo; j++) b[want - 1 - j] *= j / (float) fo;
            }
            int pos = (int) (t * rate / 1000);
            notes.add(b);
            at.add(pos);
            total = Math.max(total, pos + b.length);
            t += (k == 0 ? p.pbVowelDur : p.pbConsDur) / speed;
        }
        if (total <= 0) return new float[0];
        float[] mix = new float[total];
        for (int n = 0; n < notes.size(); n++) {
            float[] b = notes.get(n);
            int pos = at.get(n);
            for (int j = 0; j < b.length; j++) mix[pos + j] += b[j];
        }
        Synth.normalize(mix, 0.9f);
        int fo = Math.min(total / 4, rate / 100);
        for (int j = 0; j < fo; j++) mix[total - 1 - j] *= j / (float) fo;
        return mix;
    }

    /** The phonemes of a name as a readable string, for the list. */
    static String show(String phones) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < phones.length(); i++) { if (i > 0) b.append(' '); b.append(phones.charAt(i)); }
        return b.toString();
    }
}
