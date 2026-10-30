package io.rfsj.weealert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every tunable setting. Defaults reproduce the original behaviour exactly.
 * There are no range limits: any number you type is used as typed. The only things normalize() fixes
 * are values that cannot work at all (a negative length, an empty list) so nothing can crash.
 */
final class Params {
    int maxMs = 1500;        // hard cap on the length of one whole sound
    int noteMs = 350;        // longest piece of the source used per note
    int[] gaps = {200, 300, 400};          // allowed gaps between note onsets, ms
    int minNotes = 1, maxNotes = 3;
    double[] semis = {-5, -2, 0, 3, 7};    // allowed pitch offsets, semitones (decimals work)
    int rhythmW = 5;         // how much a rhythm difference counts compared to 1 semitone
    int seed = 0;            // 0 = default set; anything else = a different but equally spread set
    boolean cut = false;     // end each note when the next one starts
    int fadeMs = 30;         // fade-out at the end of the source note and of cut notes
    double falloff = 0;      // each later note is this % quieter than the one before
    boolean reverse = false; // play the source backwards
    int rate = 0;            // output sample rate in Hz, 0 = same as the source
    double levelDb = -1;     // peak level of the exported files
    boolean numbered = false; // 01-name.wav so the folder sorts by pick order
    String prefix = "";
    // contact modes
    int vSyll = 150;         // length of one syllable in ms at normal speed
    int vPitch = 300;        // centre pitch of the voices, Hz
    int vBreath = 4;         // breathiness, percent
    int vMaxSyl = 4;         // longest name, in syllables
    boolean vFirst = true;   // use only the first word of a name
    int bNote = 110;         // blips: length of one blip in ms
    int cSep = 12;           // name patterns: how far apart two contacts' patterns must be before one is moved
    // phoneme blips: one consistent voice, every sound of the name has its own pitch
    double pbPitch = 0;      // pitch of the whole voice, semitones
    int pbSpeed = 100;       // speed of the rhythm, percent (blip length is not affected)
    int pbLen = 90;          // length of a vowel blip, ms
    int pbVowelDur = 120;    // time from a vowel to the next sound, ms
    int pbConsDur = 55;      // time from a consonant to the next sound, ms
    int pbConsLen = 100;     // consonant blip length against its default, percent
    boolean pbEffects = true;// clicks, hisses, soft starts and glides on consonants
    String pbTable = "";     // phoneme table overrides, e.g. "a=0 e=3 s=8/80/70"; empty = the defaults
    // ringtone (used when an app asks for a ringtone or alarm tone, and by the ringtone export)
    String rtPre = "", rtSuf = "";         // prefix and suffix sound: "" none, "b:<built-in id>", "u:<file uri>"
    int rtPreGap = 150, rtSufGap = 150;    // silence between prefix / sound / suffix, ms
    int rtAuxMs = 400;       // length of the prefix and suffix sounds, ms
    int rtAuxVol = 100;      // prefix and suffix volume against the sound, percent
    int rtRepeat = 1;        // how many times the sound is played in one ring cycle
    int rtRepGap = 250;      // silence between repeats, ms
    int rtLoopGap = 1500;    // silence at the end, so the loop has a gap (Android repeats the file by itself)
    int rtTotal = 0;         // pad one cycle to at least this many ms, 0 = natural length
    int rtTextStyle = 0;     // typed prefix/suffix text is made as: 0 phoneme blips, 1 contact voice, 2 name blips
    int rtFType = 0;         // filter over the whole ringtone: 0 off, 1 low-pass, 2 high-pass, 3 band-pass, 4 notch
    int rtFFreq = 2000;      // filter frequency, Hz
    double rtFQ = 0.707;     // filter resonance
    double rtLevel = -1;     // peak level of ringtones, dBFS

    Params copy() { return parse(serialize()); }

    private boolean ch;

    private int atLeast(int v, int lo) {
        if (v < lo) { ch = true; return lo; }
        return v;
    }

    private double atLeast(double v, double lo) {
        if (Double.isNaN(v) || v < lo) { ch = true; return lo; }
        return v;
    }

    private int[] cleanGaps(int[] a, int[] fallback) {
        ArrayList<Integer> l = new ArrayList<>();
        for (int v : a) {
            if (v < 0) { ch = true; continue; }
            if (!l.contains(v)) l.add(v);
        }
        Collections.sort(l);
        if (l.isEmpty()) { ch = true; return fallback.clone(); }
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    private double[] cleanSemis(double[] a, double[] fallback) {
        ArrayList<Double> l = new ArrayList<>();
        for (double v : a) {
            if (Double.isNaN(v) || Double.isInfinite(v)) { ch = true; continue; }
            if (!l.contains(v)) l.add(v);
        }
        Collections.sort(l);
        if (l.isEmpty()) { ch = true; return fallback.clone(); }
        double[] r = new double[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    /** Fixes only what cannot work at all; returns true if anything had to be changed. */
    boolean normalize() {
        ch = false;
        maxMs = atLeast(maxMs, 1);
        noteMs = atLeast(noteMs, 1);
        gaps = cleanGaps(gaps, new int[]{200, 300, 400});
        semis = cleanSemis(semis, new double[]{-5, -2, 0, 3, 7});
        minNotes = atLeast(minNotes, 1);
        maxNotes = atLeast(maxNotes, 1);
        if (minNotes > maxNotes) { minNotes = maxNotes; ch = true; }
        rhythmW = atLeast(rhythmW, 0);
        fadeMs = atLeast(fadeMs, 0);
        falloff = Math.min(100, atLeast(falloff, -1000));   // 100 = silent after the first note
        if (rate < 0) { rate = 0; ch = true; }
        if (Double.isNaN(levelDb)) { levelDb = -1; ch = true; }
        StringBuilder b = new StringBuilder();
        for (char c : prefix.toCharArray())
            if (c >= 32 && "\\/:*?\"<>|".indexOf(c) < 0) b.append(c);
        if (!b.toString().equals(prefix)) ch = true;
        prefix = b.toString();
        vSyll = atLeast(vSyll, 1);
        vPitch = atLeast(vPitch, 1);
        vBreath = atLeast(vBreath, 0);
        vMaxSyl = atLeast(vMaxSyl, 1);
        bNote = atLeast(bNote, 1);
        cSep = atLeast(cSep, 0);
        rtPreGap = atLeast(rtPreGap, 0);
        rtSufGap = atLeast(rtSufGap, 0);
        rtAuxMs = atLeast(rtAuxMs, 1);
        rtAuxVol = atLeast(rtAuxVol, 0);
        rtRepeat = atLeast(rtRepeat, 1);
        rtRepGap = atLeast(rtRepGap, 0);
        rtLoopGap = atLeast(rtLoopGap, 0);
        rtTotal = atLeast(rtTotal, 0);
        rtFType = atLeast(rtFType, 0);
        rtTextStyle = atLeast(rtTextStyle, 0);
        pbSpeed = atLeast(pbSpeed, 1);
        pbLen = atLeast(pbLen, 1);
        pbVowelDur = atLeast(pbVowelDur, 0);
        pbConsDur = atLeast(pbConsDur, 0);
        pbConsLen = atLeast(pbConsLen, 0);
        if (Double.isNaN(pbPitch)) { pbPitch = 0; ch = true; }
        pbTable = pbTable.replace('\n', ' ').replace(';', ' ').trim();
        rtFFreq = atLeast(rtFFreq, 0);
        if (Double.isNaN(rtFQ) || rtFQ <= 0) { rtFQ = 0.707; ch = true; }
        if (Double.isNaN(rtLevel)) { rtLevel = -1; ch = true; }
        return ch;
    }

    // ---------------------------------------------------------------- text forms

    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    static int[] parseList(String s) {
        ArrayList<Integer> l = new ArrayList<>();
        for (String t : s.split("[\\s,;]+")) {
            if (t.isEmpty()) continue;
            try { l.add((int) Math.round(Double.parseDouble(t))); } catch (NumberFormatException ignored) { }
        }
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    static double[] parseDoubles(String s) {
        ArrayList<Double> l = new ArrayList<>();
        for (String t : s.split("[\\s,;]+")) {
            if (t.isEmpty()) continue;
            try { l.add(Double.parseDouble(t)); } catch (NumberFormatException ignored) { }
        }
        double[] r = new double[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    static String list(int[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(' '); b.append(a[i]); }
        return b.toString();
    }

    static String list(double[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(' '); b.append(fmt(a[i])); }
        return b.toString();
    }

    /** Every setting as key to text, in a fixed order. */
    Map<String, String> toMap() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("maxMs", String.valueOf(maxMs)); m.put("noteMs", String.valueOf(noteMs));
        m.put("gaps", list(gaps)); m.put("minNotes", String.valueOf(minNotes)); m.put("maxNotes", String.valueOf(maxNotes));
        m.put("semis", list(semis)); m.put("rhythmW", String.valueOf(rhythmW)); m.put("seed", String.valueOf(seed));
        m.put("cut", String.valueOf(cut)); m.put("fadeMs", String.valueOf(fadeMs)); m.put("falloff", fmt(falloff));
        m.put("reverse", String.valueOf(reverse)); m.put("rate", String.valueOf(rate)); m.put("levelDb", fmt(levelDb));
        m.put("numbered", String.valueOf(numbered)); m.put("prefix", prefix);
        m.put("vSyll", String.valueOf(vSyll)); m.put("vPitch", String.valueOf(vPitch)); m.put("vBreath", String.valueOf(vBreath));
        m.put("vMaxSyl", String.valueOf(vMaxSyl)); m.put("vFirst", String.valueOf(vFirst));
        m.put("bNote", String.valueOf(bNote)); m.put("cSep", String.valueOf(cSep));
        m.put("pbPitch", fmt(pbPitch)); m.put("pbSpeed", String.valueOf(pbSpeed)); m.put("pbLen", String.valueOf(pbLen));
        m.put("pbVowelDur", String.valueOf(pbVowelDur)); m.put("pbConsDur", String.valueOf(pbConsDur));
        m.put("pbConsLen", String.valueOf(pbConsLen)); m.put("pbEffects", String.valueOf(pbEffects)); m.put("pbTable", pbTable);
        m.put("rtTextStyle", String.valueOf(rtTextStyle));
        m.put("rtPre", rtPre); m.put("rtSuf", rtSuf); m.put("rtPreGap", String.valueOf(rtPreGap)); m.put("rtSufGap", String.valueOf(rtSufGap));
        m.put("rtAuxMs", String.valueOf(rtAuxMs)); m.put("rtAuxVol", String.valueOf(rtAuxVol)); m.put("rtRepeat", String.valueOf(rtRepeat));
        m.put("rtRepGap", String.valueOf(rtRepGap)); m.put("rtLoopGap", String.valueOf(rtLoopGap)); m.put("rtTotal", String.valueOf(rtTotal));
        m.put("rtFType", String.valueOf(rtFType)); m.put("rtFFreq", String.valueOf(rtFFreq)); m.put("rtFQ", fmt(rtFQ));
        m.put("rtLevel", fmt(rtLevel));
        return m;
    }

    String serialize() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : toMap().entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return b.toString();
    }

    private static int i(String v) { return (int) Math.round(Double.parseDouble(v.trim())); }

    /** Applies "key=value" lines on top of the current values; unknown keys and unreadable values are skipped. */
    void setFromLines(String s) {
        if (s == null) return;
        for (String line : s.split("\n")) {
            int e = line.indexOf('=');
            if (e < 0) continue;
            String k = line.substring(0, e), v = line.substring(e + 1).trim();
            try {
                switch (k) {
                    case "maxMs": maxMs = i(v); break;
                    case "noteMs": noteMs = i(v); break;
                    case "gaps": gaps = parseList(v); break;
                    case "minNotes": minNotes = i(v); break;
                    case "maxNotes": maxNotes = i(v); break;
                    case "semis": semis = parseDoubles(v); break;
                    case "rhythmW": rhythmW = i(v); break;
                    case "seed": seed = i(v); break;
                    case "cut": cut = Boolean.parseBoolean(v); break;
                    case "fadeMs": fadeMs = i(v); break;
                    case "falloff": falloff = Double.parseDouble(v); break;
                    case "reverse": reverse = Boolean.parseBoolean(v); break;
                    case "rate": rate = i(v); break;
                    case "levelDb": levelDb = Double.parseDouble(v); break;
                    case "numbered": numbered = Boolean.parseBoolean(v); break;
                    case "prefix": prefix = v; break;
                    case "vSyll": vSyll = i(v); break;
                    case "vPitch": vPitch = i(v); break;
                    case "vBreath": vBreath = i(v); break;
                    case "vMaxSyl": vMaxSyl = i(v); break;
                    case "vFirst": vFirst = Boolean.parseBoolean(v); break;
                    case "bNote": bNote = i(v); break;
                    case "cSep": cSep = i(v); break;
                    case "pbPitch": pbPitch = Double.parseDouble(v); break;
                    case "pbSpeed": pbSpeed = i(v); break;
                    case "pbLen": pbLen = i(v); break;
                    case "pbVowelDur": pbVowelDur = i(v); break;
                    case "pbConsDur": pbConsDur = i(v); break;
                    case "pbConsLen": pbConsLen = i(v); break;
                    case "pbEffects": pbEffects = Boolean.parseBoolean(v); break;
                    case "pbTable": pbTable = v; break;
                    case "rtTextStyle": rtTextStyle = i(v); break;
                    case "rtPre": rtPre = v; break;
                    case "rtSuf": rtSuf = v; break;
                    case "rtPreGap": rtPreGap = i(v); break;
                    case "rtSufGap": rtSufGap = i(v); break;
                    case "rtAuxMs": rtAuxMs = i(v); break;
                    case "rtAuxVol": rtAuxVol = i(v); break;
                    case "rtRepeat": rtRepeat = i(v); break;
                    case "rtRepGap": rtRepGap = i(v); break;
                    case "rtLoopGap": rtLoopGap = i(v); break;
                    case "rtTotal": rtTotal = i(v); break;
                    case "rtFType": rtFType = i(v); break;
                    case "rtFFreq": rtFFreq = i(v); break;
                    case "rtFQ": rtFQ = Double.parseDouble(v); break;
                    case "rtLevel": rtLevel = Double.parseDouble(v); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) { }
        }
    }

    static Params parse(String s) {
        Params p = new Params();
        p.setFromLines(s);
        p.normalize();
        return p;
    }

    /** These settings with one sound's overrides ("key=value;key=value") on top. The output rate is never overridden. */
    Params overlay(String kv) {
        Params p = copy();
        if (kv != null && !kv.isEmpty()) p.setFromLines(kv.replace(';', '\n'));
        p.rate = rate;
        p.normalize();
        return p;
    }

    @Override
    public String toString() { return list(gaps) + " / " + list(semis); }
}
