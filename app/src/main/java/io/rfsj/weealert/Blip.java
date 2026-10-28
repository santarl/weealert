package io.rfsj.weealert;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Turns a name into electronic blips instead of speech: one short blip per syllable, made from one of the
 * built-in source sounds. The vowel sets the pitch (bright vowels high, dark ones low), the first consonant
 * sets how the blip starts (click, hiss, soft or gliding) and the syllable structure sets the rhythm.
 * There is no voice model at all, so it reads as a synthesised sound, not as speech. Pure Java.
 */
final class Blip {
    static final int RATE = Synth.BUILTIN_RATE;

    /** Vowel brightness in semitones: front vowels (ee, eh) high, back vowels (oh, oo) low. */
    static int vowelSemi(char v) {
        switch (v) {
            case 'i': return 4;
            case 'e': return 2;
            case 'a': return -2;
            case 'o': return -4;
            case 'u': return -6;
            default: return 0;
        }
    }

    private static boolean in(String set, char c) { return c != 0 && set.indexOf(c) >= 0; }

    private static int xs(int s) { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; return s; }

    static float[] render(ArrayList<Say.Syl> syls, Contacts.C c, Params p, int seed) {
        if (syls.isEmpty()) return new float[0];
        Params pb = p.copy();
        pb.noteMs = p.bNote;
        pb.rate = 0;
        Synth.Note note = Synth.prepare(Synth.builtin(Synth.hasBuiltin(c.bsrc) ? c.bsrc : 0), RATE, pb);
        float[] src = note.data;
        if (src.length < 64) return new float[0];
        int n = syls.size();
        double S = p.vSyll / (c.bspd / 100.0);
        int rs = seed == 0 ? 0x9e3779b9 : seed;
        double[] off = new double[n];
        for (int j = 0; j < n; j++) {
            switch (c.bcon) {
                case 1: off[j] = Math.min(4, j * 1.0); break;
                case 2: off[j] = -Math.min(4, j * 1.0); break;
                case 3: off[j] = j % 2 == 0 ? 1.5 : -1.5; break;
                default: rs = xs(rs); off[j] = ((rs >>> 8) % 9 - 4) * 0.5; break;
            }
        }
        float[][] notes = new float[n][];
        int[] at = new int[n];
        double cur = 0;
        int total = 0;
        for (int j = 0; j < n; j++) {
            Say.Syl s = syls.get(j);
            char on = s.on.length() > 0 ? s.on.charAt(0) : 0;
            rs = xs(rs);
            notes[j] = blip(src, c.bpit + vowelSemi(s.nuc) + off[j], on, rs, j == 0 ? 1f : 0.82f);
            at[j] = (int) (cur * RATE / 1000);
            total = Math.max(total, at[j] + notes[j].length);
            cur += S * (1 + (s.co.length() > 0 ? 0.25 : 0) + (s.on.length() > 1 ? 0.15 : 0));
        }
        float[] mix = new float[total];
        for (int j = 0; j < n; j++)
            for (int i = 0; i < notes[j].length; i++) mix[at[j] + i] += notes[j][i];
        Synth.normalize(mix, 0.9f);
        int fo = Math.min(total / 4, RATE / 100);
        for (int i = 0; i < fo; i++) mix[total - 1 - i] *= i / (float) fo;
        return mix;
    }

    /** One blip: the source note at a pitch, started in the way the first consonant asks for. */
    private static float[] blip(float[] src, double semi, char on, int rnd, float gain) {
        double r0 = Math.pow(2, semi / 12.0);
        double glide = in("lrw", on) ? -2 : on == 'y' ? 2 : 0;
        double rmin = r0 * Math.pow(2, Math.min(0, glide) / 12.0);
        int cap = (int) (src.length / rmin) + 8;
        float[] o = new float[cap];
        int glideN = RATE * 30 / 1000, len = 0;
        double pos = 0;
        for (int i = 0; i < cap; i++) {
            double r = r0;
            if (glide != 0 && i < glideN) r = r0 * Math.pow(2, glide * (1 - i / (double) glideN) / 12.0);
            int i0 = (int) pos;
            if (i0 >= src.length - 1) break;
            float f = (float) (pos - i0);
            o[i] = (src[i0] * (1 - f) + src[i0 + 1] * f) * gain;
            pos += r;
            len = i + 1;
        }
        o = Arrays.copyOf(o, len);
        int nz = rnd | 1;
        double prev = 0;
        if (in("mnN", on)) {                                   // nasals start softly
            int fi = Math.min(len / 2, RATE * 18 / 1000);
            for (int i = 0; i < fi; i++) o[i] *= i / (float) fi;
            for (int i = 0; i < len; i++) o[i] *= 0.9f;
        }
        if (in("pbtdkgCJ", on)) {                              // plosives start with a click
            int cl = Math.min(len, RATE * 3 / 1000);
            float amp = in("ptkC", on) ? 0.5f : 0.3f;
            for (int i = 0; i < cl; i++) {
                nz = xs(nz);
                double w = (nz >> 8) / 8388608.0;
                o[i] += (float) ((w - prev) * amp * (1 - i / (float) cl) * 0.7);
                prev = w;
            }
        }
        if (in("fvszSZTh", on)) {                              // fricatives start with a hiss
            boolean bright = in("szSZ", on);
            int hl = Math.min(len, RATE * 28 / 1000);
            float amp = bright ? 0.35f : 0.22f;
            for (int i = 0; i < hl; i++) {
                nz = xs(nz);
                double w = (nz >> 8) / 8388608.0;
                double v = bright ? (w - prev) : (w + prev) * 0.5;
                prev = w;
                o[i] += (float) (v * amp * (1 - i / (float) hl) * 0.8);
            }
        }
        return o;
    }
}
