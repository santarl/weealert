package io.rfsj.weealert;

import java.util.ArrayList;

/**
 * A small formant speech synthesizer (source and filter, in the spirit of Klatt / Speak & Spell).
 * A buzzy glottal pulse train goes through three resonant filters whose centre frequencies move from
 * phone to phone; fricatives and plosive bursts are filtered noise. The result is a fast, cute mumble
 * of the name, not intelligible speech. Pure Java, deterministic.
 */
final class Voice {
    static final int RATE = 22050;
    private static final int STEP = 32;                  // control update interval in samples

    /** One phone: kind 0 vowel, 1 nasal, 2 liquid or glide, 3 fricative, 4 plosive. */
    private static final class Ph {
        int kind;
        boolean voiced;
        float dur;               // in syllable lengths
        float[] f;               // formants, null = take from the neighbour
        float fn = 3000, bw = 2000, an;   // noise band and level
        float av = 1;            // voicing level
    }

    private static Ph ph(char c) {
        Ph p = new Ph();
        switch (c) {
            case 'a': return vow(p, 800, 1250, 2800);
            case 'e': return vow(p, 550, 1900, 2600);
            case 'i': return vow(p, 320, 2250, 3000);
            case 'o': return vow(p, 550, 950, 2650);
            case 'u': return vow(p, 350, 850, 2500);
            case '@': return vow(p, 600, 1450, 2700);
            case 'm': return sonor(p, 1, 0.30f, 0.60f, 250, 1000, 2200);
            case 'n': return sonor(p, 1, 0.30f, 0.60f, 250, 1500, 2500);
            case 'N': return sonor(p, 1, 0.32f, 0.55f, 250, 1800, 2400);
            case 'l': return sonor(p, 2, 0.28f, 0.80f, 350, 1150, 2800);
            case 'r': return sonor(p, 2, 0.28f, 0.80f, 420, 1300, 1700);
            case 'w': return sonor(p, 2, 0.24f, 0.80f, 300, 700, 2300);
            case 'y': return sonor(p, 2, 0.22f, 0.80f, 270, 2150, 3000);
            case 'f': return fric(p, false, 0.30f, 5500, 5000, 0.25f);
            case 'v': return fric(p, true, 0.30f, 5500, 5000, 0.15f);
            case 's': return fric(p, false, 0.34f, 6500, 2500, 0.55f);
            case 'z': return fric(p, true, 0.32f, 6500, 2500, 0.35f);
            case 'S': return fric(p, false, 0.34f, 3300, 2000, 0.55f);
            case 'Z': return fric(p, true, 0.32f, 3300, 2000, 0.35f);
            case 'T': return fric(p, false, 0.28f, 5000, 5000, 0.15f);
            case 'h': return fric(p, false, 0.22f, 1800, 2500, 0.30f);
            case 'p': return plos(p, false, 0.26f, 1000, 1500, 0.45f);
            case 'b': return plos(p, true, 0.26f, 1000, 1500, 0.30f);
            case 't': return plos(p, false, 0.26f, 4500, 2500, 0.50f);
            case 'd': return plos(p, true, 0.26f, 4500, 2500, 0.30f);
            case 'k': return plos(p, false, 0.28f, 2200, 1600, 0.50f);
            case 'g': return plos(p, true, 0.28f, 2200, 1600, 0.30f);
            case 'C': return plos(p, false, 0.34f, 3300, 2000, 0.55f);
            case 'J': return plos(p, true, 0.34f, 3300, 2000, 0.40f);
            default: return plos(p, false, 0.2f, 3000, 2000, 0.3f);
        }
    }

    private static Ph vow(Ph p, float f1, float f2, float f3) {
        p.kind = 0; p.voiced = true; p.dur = 0.62f; p.f = new float[]{f1, f2, f3};
        return p;
    }

    private static Ph sonor(Ph p, int kind, float dur, float av, float f1, float f2, float f3) {
        p.kind = kind; p.voiced = true; p.dur = dur; p.av = av; p.f = new float[]{f1, f2, f3};
        return p;
    }

    private static Ph fric(Ph p, boolean voiced, float dur, float fn, float bw, float an) {
        p.kind = 3; p.voiced = voiced; p.dur = dur; p.fn = fn; p.bw = bw; p.an = an; p.av = voiced ? 0.4f : 0;
        return p;
    }

    private static Ph plos(Ph p, boolean voiced, float dur, float fn, float bw, float an) {
        p.kind = 4; p.voiced = voiced; p.dur = dur; p.fn = fn; p.bw = bw; p.an = an; p.av = voiced ? 0.25f : 0;
        return p;
    }

    // ---------------------------------------------------------------- resonator

    private static final class Res {
        double a, b, c, y1, y2;
        void set(double f, double bw) {
            double t = 1.0 / RATE;
            f = Math.min(f, RATE * 0.45);
            c = -Math.exp(-2 * Math.PI * bw * t);
            b = 2 * Math.exp(-Math.PI * bw * t) * Math.cos(2 * Math.PI * f * t);
            a = 1 - b - c;
        }
        double run(double x) {
            double y = a * x + b * y1 + c * y2;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    // ---------------------------------------------------------------- control track

    /** Keyframe: t(ms), f1, f2, f3, av, an, fn, bw. */
    private static void kf(ArrayList<float[]> k, float t, float[] f, float av, float an, float fn, float bw) {
        k.add(new float[]{t, f[0], f[1], f[2], av, an, fn, bw});
    }

    private static float[] at(ArrayList<float[]> k, float t, int[] idx) {
        int i = idx[0];
        while (i + 1 < k.size() && k.get(i + 1)[0] <= t) i++;
        idx[0] = i;
        float[] a = k.get(i);
        if (i + 1 >= k.size() || t <= a[0]) return a;
        float[] b = k.get(i + 1);
        float u = (t - a[0]) / Math.max(1e-3f, b[0] - a[0]);
        float[] r = new float[a.length];
        for (int j = 0; j < a.length; j++) r[j] = a[j] + (b[j] - a[j]) * u;
        return r;
    }

    private static int xs(int s) { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; return s; }

    // ---------------------------------------------------------------- render

    /**
     * @param pitchSt   voice pitch in semitones from the centre pitch
     * @param fscalePct vocal tract size: 100 = normal, higher = smaller (all formants rise)
     * @param speedPct  speaking speed, 100 = normal
     * @param contour   0 wander, 1 rising, 2 falling, 3 bouncy
     */
    static float[] render(ArrayList<Say.Syl> syls, double pitchSt, double fscalePct, double speedPct, int contour,
                          Params p, int seed) {
        if (syls.isEmpty()) return new float[0];
        double S = p.vSyll / (Math.max(1, speedPct) / 100.0);
        double fs = Math.max(1, fscalePct) / 100.0;
        int rs = seed == 0 ? 0x9e3779b9 : seed;
        int ns = syls.size();

        // phones with durations, formants filled from neighbours
        ArrayList<Ph> phs = new ArrayList<>();
        ArrayList<Integer> sylOf = new ArrayList<>();
        ArrayList<Boolean> isNuc = new ArrayList<>();
        for (int j = 0; j < ns; j++) {
            Say.Syl s = syls.get(j);
            for (int i = 0; i < s.on.length(); i++) { phs.add(ph(s.on.charAt(i))); sylOf.add(j); isNuc.add(false); }
            Ph v = ph(s.nuc);
            if (j == 0) v.dur *= 1.15f;
            phs.add(v); sylOf.add(j); isNuc.add(true);
            for (int i = 0; i < s.co.length(); i++) { phs.add(ph(s.co.charAt(i))); sylOf.add(j); isNuc.add(false); }
        }
        for (int i = 0; i < phs.size(); i++) {
            if (phs.get(i).f != null) continue;
            float[] f = null;
            for (int k = i + 1; k < phs.size() && f == null; k++) if (phs.get(k).kind == 0) f = phs.get(k).f;
            for (int k = i - 1; k >= 0 && f == null; k--) f = phs.get(k).f;
            phs.get(i).f = f != null ? f : new float[]{600, 1450, 2700};
        }

        // syllable pitch offsets (semitones) and glide
        double[] off = new double[ns], glide = new double[ns];
        for (int j = 0; j < ns; j++) {
            switch (contour) {
                case 1: off[j] = Math.min(4, j * 1.0); glide[j] = 1.2; break;
                case 2: off[j] = -Math.min(4, j * 1.0); glide[j] = -1.2; break;
                case 3: off[j] = j % 2 == 0 ? 1.5 : -1.5; glide[j] = j % 2 == 0 ? 1.0 : -1.0; break;
                default: rs = xs(rs); off[j] = ((rs >>> 8) % 9 - 4) * 0.5; glide[j] = 0; break;
            }
        }

        ArrayList<float[]> k = new ArrayList<>();
        ArrayList<float[]> f0 = new ArrayList<>();
        float t = 10;
        float[] f00 = phs.get(0).f;
        kf(k, 0, f00, 0, 0, 3000, 2000);
        for (int i = 0; i < phs.size(); i++) {
            Ph h = phs.get(i);
            int j = sylOf.get(i);
            double amp = j == 0 ? 1.0 : 0.82;
            float d = (float) (h.dur * S);
            float[] f = new float[]{(float) (h.f[0] * fs), (float) (h.f[1] * fs), (float) (h.f[2] * fs)};
            float fn = (float) (h.fn * Math.sqrt(fs)), bw = h.bw;
            switch (h.kind) {
                case 0:
                    kf(k, t + 0.18f * d, f, (float) amp, 0, fn, bw);
                    kf(k, t + 0.82f * d, f, (float) amp, 0, fn, bw);
                    f0.add(new float[]{t + 0.1f * d, (float) (off[j] - glide[j] / 2)});
                    f0.add(new float[]{t + 0.9f * d, (float) (off[j] + glide[j] / 2)});
                    break;
                case 1: case 2:
                    kf(k, t + 0.2f * d, f, (float) (h.av * amp), 0, fn, bw);
                    kf(k, t + 0.8f * d, f, (float) (h.av * amp), 0, fn, bw);
                    break;
                case 3:
                    kf(k, t + 0.08f * d, f, (float) (h.av * amp), 0, fn, bw);
                    kf(k, t + 0.25f * d, f, (float) (h.av * amp), h.an, fn, bw);
                    kf(k, t + 0.75f * d, f, (float) (h.av * amp), h.an, fn, bw);
                    kf(k, t + 0.95f * d, f, (float) (h.av * amp), 0, fn, bw);
                    break;
                default:
                    kf(k, t + 0.05f * d, f, (float) (h.av * amp), 0, fn, bw);
                    kf(k, t + 0.55f * d, f, (float) (h.av * amp), 0, fn, bw);
                    kf(k, t + 0.58f * d, f, (float) (h.av * amp), h.an, fn, bw);
                    kf(k, t + 0.78f * d, f, (float) (h.av * amp), h.an * 0.5f, fn, bw);
                    kf(k, t + 0.98f * d, f, 0, 0, fn, bw);
                    break;
            }
            t += d;
        }
        float[] fl = phs.get(phs.size() - 1).f;
        kf(k, t + 25, new float[]{(float) (fl[0] * fs), (float) (fl[1] * fs), (float) (fl[2] * fs)}, 0, 0, 3000, 2000);
        float T = t + 30;
        if (!f0.isEmpty()) f0.get(f0.size() - 1)[1] -= 1.2f;   // the last syllable falls a little

        double base = p.vPitch * Math.pow(2, pitchSt / 12.0);
        int n = (int) (T / 1000.0 * RATE);
        float[] out = new float[n];
        Res r1 = new Res(), r2 = new Res(), r3 = new Res(), rn = new Res();
        int[] ik = {0}, i0 = {0};
        double phase = 0, gPrev = 0, nPrev = 0;
        double breath = p.vBreath / 100.0;
        float[] c = k.get(0);
        double st = 0;
        int nz = rs | 1;
        for (int s = 0; s < n; s++) {
            double tm = s * 1000.0 / RATE;
            if (s % STEP == 0) {
                c = at(k, (float) tm, ik);
                r1.set(c[1], 70);
                r2.set(c[2], 90);
                r3.set(c[3], 130);
                rn.set(c[6], c[7]);
                if (f0.isEmpty()) st = 0;
                else {
                    int a = 0;
                    while (a + 1 < f0.size() && f0.get(a + 1)[0] <= tm) a++;
                    float[] x = f0.get(a);
                    if (tm <= x[0] || a + 1 >= f0.size()) st = x[1];
                    else { float[] y = f0.get(a + 1); st = x[1] + (y[1] - x[1]) * (tm - x[0]) / Math.max(1e-3, y[0] - x[0]); }
                }
            }
            double f = base * Math.pow(2, st / 12.0);
            phase += f / RATE;
            if (phase >= 1) phase -= 1;
            double g = phase < 0.40 ? 0.5 * (1 - Math.cos(Math.PI * phase / 0.40))
                    : phase < 0.56 ? Math.cos(Math.PI / 2 * (phase - 0.40) / 0.16) : 0;
            double src = (g - gPrev) * 12;
            gPrev = g;
            nz = xs(nz);
            double white = (nz >> 8) / 8388608.0;
            double av = c[4];
            double v = av * (src + breath * white * 0.6);
            double y = r3.run(r2.run(r1.run(v)));
            double hp = white - nPrev;
            nPrev = white;
            double fr = rn.run(hp) * c[5];
            out[s] = (float) (y * 0.05 + fr * 0.17);
        }
        float pk = 0;
        for (float x : out) pk = Math.max(pk, Math.abs(x));
        if (pk > 0) for (int i = 0; i < n; i++) out[i] *= 0.9f / pk;
        int fi = Math.min(n / 4, RATE / 500), fo = Math.min(n / 4, RATE / 125);
        for (int i = 0; i < fi; i++) out[i] *= i / (float) fi;
        for (int i = 0; i < fo; i++) out[n - 1 - i] *= i / (float) fo;
        return out;
    }
}
