package io.rfsj.weealert;

import java.util.ArrayList;
import java.util.Arrays;

/** Every tunable setting. Defaults reproduce the original behaviour exactly. Pure Java. */
final class Params {
    int maxMs = 1500;        // hard cap on the length of one whole sound
    int noteMs = 350;        // longest piece of the source used per note
    int[] gaps = {200, 300, 400};          // allowed gaps between note onsets, ms (multiples of 50)
    int minNotes = 1, maxNotes = 3;
    int[] semis = {-5, -2, 0, 3, 7};       // allowed pitch offsets, semitones
    int rhythmW = 5;         // how much a rhythm difference counts compared to 1 semitone
    int seed = 0;            // 0 = default set; anything else = a different but equally spread set
    boolean cut = false;     // end each note when the next one starts
    int fadeMs = 30;         // fade-out at the end of the source note and of cut notes
    int falloff = 0;         // each later note is this % quieter than the one before
    boolean reverse = false; // play the source backwards
    int rate = 0;            // output sample rate in Hz, 0 = same as the source
    int levelDb = -1;        // peak level of the exported files
    boolean numbered = false; // 01-name.wav so the folder sorts by pick order
    String prefix = "";
    // contact voices
    int vSyll = 150;         // length of one syllable in ms at normal speed
    int vPitch = 300;        // centre pitch of the voices, Hz
    int vBreath = 4;         // breathiness, percent
    int vMaxSyl = 4;         // longest name, in syllables
    boolean vFirst = true;   // use only the first word of a name

    Params copy() { return parse(serialize()); }

    private boolean ch;

    private int clamp(int v, int lo, int hi) {
        int r = Math.max(lo, Math.min(hi, v));
        if (r != v) ch = true;
        return r;
    }

    private int[] cleanList(int[] a, int lo, int hi, int step, int maxLen, int[] fallback) {
        ArrayList<Integer> l = new ArrayList<>();
        for (int v : a) {
            int s = step > 1 ? Math.round(v / (float) step) * step : v;
            s = clamp(s, lo, hi);
            if (s != v) ch = true;
            if (!l.contains(s)) l.add(s);
        }
        java.util.Collections.sort(l);
        if (l.isEmpty()) { ch = true; return fallback.clone(); }
        if (l.size() > maxLen) { ch = true; l = new ArrayList<>(l.subList(0, maxLen)); }
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    /** Clamps everything into its valid range; returns true if anything had to be adjusted. */
    boolean normalize() {
        ch = false;
        maxMs = clamp(maxMs, 300, 5000);
        noteMs = clamp(noteMs, 50, 1500);
        if (noteMs > maxMs) { noteMs = maxMs; ch = true; }
        gaps = cleanList(gaps, 50, 1000, 50, 4, new int[]{200, 300, 400});
        semis = cleanList(semis, -24, 24, 1, 6, new int[]{-5, -2, 0, 3, 7});
        minNotes = clamp(minNotes, 1, 4);
        maxNotes = clamp(maxNotes, 1, 4);
        if (minNotes > maxNotes) { minNotes = maxNotes; ch = true; }
        rhythmW = clamp(rhythmW, 1, 20);
        seed = clamp(seed, 0, 9999);
        fadeMs = clamp(fadeMs, 0, 200);
        falloff = clamp(falloff, 0, 60);
        levelDb = clamp(levelDb, -12, 0);
        if (rate != 0) rate = clamp(rate, 8000, 48000);
        StringBuilder b = new StringBuilder();
        for (char c : prefix.toLowerCase().toCharArray())
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') b.append(c);
        String p = b.length() > 16 ? b.substring(0, 16) : b.toString();
        if (!p.equals(prefix)) ch = true;
        prefix = p;
        vSyll = clamp(vSyll, 100, 300);
        vPitch = clamp(vPitch, 150, 500);
        vBreath = clamp(vBreath, 0, 30);
        vMaxSyl = clamp(vMaxSyl, 1, 6);
        return ch;
    }

    static int[] parseList(String s) {
        ArrayList<Integer> l = new ArrayList<>();
        for (String t : s.split("[\\s,;]+")) {
            if (t.isEmpty()) continue;
            try { l.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { }
        }
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    static String list(int[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(' '); b.append(a[i]); }
        return b.toString();
    }

    String serialize() {
        return "maxMs=" + maxMs + "\nnoteMs=" + noteMs + "\ngaps=" + list(gaps) + "\nminNotes=" + minNotes
                + "\nmaxNotes=" + maxNotes + "\nsemis=" + list(semis) + "\nrhythmW=" + rhythmW + "\nseed=" + seed
                + "\ncut=" + cut + "\nfadeMs=" + fadeMs + "\nfalloff=" + falloff + "\nreverse=" + reverse
                + "\nrate=" + rate + "\nlevelDb=" + levelDb + "\nnumbered=" + numbered + "\nprefix=" + prefix
                + "\nvSyll=" + vSyll + "\nvPitch=" + vPitch + "\nvBreath=" + vBreath + "\nvMaxSyl=" + vMaxSyl + "\nvFirst=" + vFirst;
    }

    static Params parse(String s) {
        Params p = new Params();
        if (s != null) {
            for (String line : s.split("\n")) {
                int e = line.indexOf('=');
                if (e < 0) continue;
                String k = line.substring(0, e), v = line.substring(e + 1).trim();
                try {
                    switch (k) {
                        case "maxMs": p.maxMs = Integer.parseInt(v); break;
                        case "noteMs": p.noteMs = Integer.parseInt(v); break;
                        case "gaps": p.gaps = parseList(v); break;
                        case "minNotes": p.minNotes = Integer.parseInt(v); break;
                        case "maxNotes": p.maxNotes = Integer.parseInt(v); break;
                        case "semis": p.semis = parseList(v); break;
                        case "rhythmW": p.rhythmW = Integer.parseInt(v); break;
                        case "seed": p.seed = Integer.parseInt(v); break;
                        case "cut": p.cut = Boolean.parseBoolean(v); break;
                        case "fadeMs": p.fadeMs = Integer.parseInt(v); break;
                        case "falloff": p.falloff = Integer.parseInt(v); break;
                        case "reverse": p.reverse = Boolean.parseBoolean(v); break;
                        case "rate": p.rate = Integer.parseInt(v); break;
                        case "levelDb": p.levelDb = Integer.parseInt(v); break;
                        case "numbered": p.numbered = Boolean.parseBoolean(v); break;
                        case "prefix": p.prefix = v; break;
                        case "vSyll": p.vSyll = Integer.parseInt(v); break;
                        case "vPitch": p.vPitch = Integer.parseInt(v); break;
                        case "vBreath": p.vBreath = Integer.parseInt(v); break;
                        case "vMaxSyl": p.vMaxSyl = Integer.parseInt(v); break;
                        case "vFirst": p.vFirst = Boolean.parseBoolean(v); break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
        p.normalize();
        return p;
    }

    @Override
    public String toString() { return Arrays.toString(gaps) + Arrays.toString(semis); }
}
