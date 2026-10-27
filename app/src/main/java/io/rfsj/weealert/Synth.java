package io.rfsj.weealert;

/** Built-in source sounds, and turning one short source note into the notes of a pattern. Pure Java. */
final class Synth {
    static final int BUILTIN_RATE = 44100;
    /** Names by stable id. Ids 4 and 8 are retired (null): saved choices keep their meaning, retired ones fall back to the bell. */
    static final String[] BUILTIN = {"bell", "glass", "marimba", "kalimba", null, "soft sine",
            "beep", "water drop", null, "wood tock"};

    static boolean hasBuiltin(int id) { return id >= 0 && id < BUILTIN.length && BUILTIN[id] != null; }

    // ---------------------------------------------------------------- built-in sources
    // All generated from maths, so they cost no APK size. Each is longer than needed; prepare() cuts it.

    private static float[] modal(double f0, double[] ratio, double[] amp, double[] tau, double sec) {
        int n = (int) (BUILTIN_RATE * sec);
        float[] o = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) BUILTIN_RATE, s = 0;
            for (int k = 0; k < ratio.length; k++)
                s += amp[k] * Math.sin(2 * Math.PI * f0 * ratio[k] * t) * Math.exp(-t / tau[k]);
            o[i] = (float) s;
        }
        return o;
    }

    static float[] builtin(int idx) {
        final int R = BUILTIN_RATE;
        switch (idx) {
            case 1: // glass: bright, long ring, strongly inharmonic
                return modal(1250, new double[]{1, 2.32, 4.25, 6.63}, new double[]{1, 0.6, 0.35, 0.2},
                        new double[]{0.5, 0.32, 0.2, 0.12}, 1.2);
            case 2: // marimba: wooden bar, partials near 1 : 4 : 9
                return modal(520, new double[]{1, 3.9, 9.0}, new double[]{1, 0.4, 0.12},
                        new double[]{0.22, 0.06, 0.025}, 0.8);
            case 3: // kalimba: tine, partials near 1 : 5.4 : 12.8
                return modal(880, new double[]{1, 5.4, 12.8}, new double[]{1, 0.3, 0.08},
                        new double[]{0.4, 0.09, 0.03}, 1.0);
            case 5: { // soft sine ping
                int n = (int) (R * 0.6);
                float[] o = new float[n];
                for (int i = 0; i < n; i++) {
                    double t = i / (double) R;
                    o[i] = (float) ((Math.sin(2 * Math.PI * 880 * t) * Math.exp(-t / 0.12)
                            + 0.15 * Math.sin(2 * Math.PI * 1760 * t) * Math.exp(-t / 0.05)) * (1 - Math.exp(-t / 0.004)));
                }
                return o;
            }
            case 6: { // digital beep: square-ish, odd harmonics
                int n = (int) (R * 0.2);
                float[] o = new float[n];
                for (int i = 0; i < n; i++) {
                    double t = i / (double) R, s = 0;
                    for (int h = 1; h <= 7; h += 2) s += Math.sin(2 * Math.PI * 1000 * h * t) / h;
                    double env = Math.min(1, t / 0.002) * (t < 0.09 ? 1 : Math.max(0, 1 - (t - 0.09) / 0.04));
                    o[i] = (float) (s * env);
                }
                return o;
            }
            case 7: { // water drop: pitch glides up while it fades
                int n = (int) (R * 0.5);
                float[] o = new float[n];
                for (int i = 0; i < n; i++) {
                    double t = i / (double) R;
                    double phase = 500 * t + 900 * (t - 0.035 * (1 - Math.exp(-t / 0.035)));
                    o[i] = (float) (Math.sin(2 * Math.PI * phase) * (1 - Math.exp(-t / 0.003)) * Math.exp(-t / 0.1));
                }
                return o;
            }
            case 9: // wood tock: very short, dry
                return modal(1200, new double[]{1, 2.4, 4.1}, new double[]{1, 0.5, 0.25},
                        new double[]{0.03, 0.02, 0.012}, 0.3);
            default: // 0: bell, three inharmonic partials, each dying at its own rate
                return modal(660, new double[]{1, 2.76, 5.40}, new double[]{1, 0.5, 0.25},
                        new double[]{0.12, 0.07, 0.04}, 1.2);
        }
    }

    // ---------------------------------------------------------------- source note

    static final class Note {
        float[] data = new float[0];
        int rate;
    }

    /** Trim leading silence, cap the length, optionally reverse, fade the edges, normalise, resample. */
    static Note prepare(float[] in, int rate, Params p) {
        Note r = new Note();
        r.rate = rate;
        float peak = 0;
        for (float v : in) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0) return r;
        int start = 0;
        while (start < in.length && Math.abs(in[start]) < 0.02f * peak) start++;
        start = Math.max(0, start - rate / 500);
        int len = (int) Math.min(in.length - start, (long) rate * p.noteMs / 1000);
        float[] o = new float[len];
        System.arraycopy(in, start, o, 0, len);
        if (p.reverse) for (int i = 0, j = len - 1; i < j; i++, j--) { float t = o[i]; o[i] = o[j]; o[j] = t; }
        int fi = Math.min(len / 4, rate / 500), fo = Math.min(len / 4, (int) ((long) rate * p.fadeMs / 1000));
        for (int i = 0; i < fi; i++) o[i] *= i / (float) fi;
        for (int i = 0; i < fo; i++) o[len - 1 - i] *= i / (float) fo;
        normalize(o, 0.9f);
        if (p.rate > 0 && p.rate != rate) { o = resample(o, rate, p.rate); r.rate = p.rate; }
        r.data = o;
        return r;
    }

    static void normalize(float[] a, float target) {
        float p = 0;
        for (float v : a) p = Math.max(p, Math.abs(v));
        if (p > 0) for (int i = 0; i < a.length; i++) a[i] *= target / p;
    }

    /** Linear resampler; averages first when going down so high partials do not fold back. */
    static float[] resample(float[] a, int from, int to) {
        float[] s = a;
        if (to < from) {
            int w = Math.max(2, Math.round(from / (float) to)), h = w / 2;
            s = new float[a.length];
            for (int i = 0; i < a.length; i++) {
                float sum = 0;
                int c = 0;
                for (int j = i - h; j < i - h + w; j++) if (j >= 0 && j < a.length) { sum += a[j]; c++; }
                s[i] = sum / c;
            }
        }
        int n = (int) ((long) a.length * to / from);
        float[] o = new float[n];
        double step = from / (double) to;
        for (int j = 0; j < n; j++) {
            double pos = j * step;
            int i0 = (int) pos, i1 = Math.min(i0 + 1, s.length - 1);
            float f = (float) (pos - i0);
            o[j] = s[Math.min(i0, s.length - 1)] * (1 - f) + s[i1] * f;
        }
        return o;
    }

    // ---------------------------------------------------------------- render

    static short[] render(Note src, Codebook.Pattern p, Params prm) {
        int rate = src.rate, k = p.semis.length, total = 0;
        float[] s = src.data;
        float[][] notes = new float[k][];
        int[] at = new int[k];
        int fadeS = Math.max(rate / 200, (int) ((long) rate * prm.fadeMs / 1000));
        double fall = 1 - prm.falloff / 100.0, gain = 1;
        for (int i = 0; i < k; i++) {
            double r = Math.pow(2, p.semis[i] / 12.0);
            int len = Math.max(1, (int) (s.length / r));
            float[] n = new float[len];
            for (int j = 0; j < len; j++) {
                double pos = j * r;
                int i0 = (int) pos, i1 = Math.min(i0 + 1, s.length - 1);
                float f = (float) (pos - i0);
                n[j] = (s[Math.min(i0, s.length - 1)] * (1 - f) + s[i1] * f) * (float) gain;
            }
            if (prm.cut && i < k - 1) {
                int lim = (int) ((long) p.gaps[i] * rate / 1000);
                if (lim < len) {
                    n = java.util.Arrays.copyOf(n, lim);
                    int fo = Math.min(fadeS, lim / 2);
                    for (int j = 0; j < fo; j++) n[lim - 1 - j] *= j / (float) fo;
                }
            }
            notes[i] = n;
            at[i] = (int) ((long) p.onsetMs(i) * rate / 1000);
            total = Math.max(total, at[i] + n.length);
            gain *= fall;
        }
        float[] mix = new float[total];
        for (int i = 0; i < k; i++)
            for (int j = 0; j < notes[i].length; j++) mix[at[i] + j] += notes[i][j];
        int maxS = (int) ((long) prm.maxMs * rate / 1000);
        if (total > maxS) {            // the cap is a hard guarantee
            mix = java.util.Arrays.copyOf(mix, maxS);
            int fo = Math.min(rate / 40, maxS / 2);
            for (int j = 0; j < fo; j++) mix[maxS - 1 - j] *= j / (float) fo;
        }
        normalize(mix, (float) Math.pow(10, prm.levelDb / 20.0));
        short[] o = new short[mix.length];
        for (int i = 0; i < o.length; i++) o[i] = (short) Math.round(mix[i] * 32767f);
        return o;
    }

    /** 16-bit mono PCM WAV. */
    static byte[] wav(short[] pcm, int rate) {
        int dl = pcm.length * 2;
        byte[] b = new byte[44 + dl];
        put(b, 0, "RIFF"); le32(b, 4, 36 + dl); put(b, 8, "WAVEfmt ");
        le32(b, 16, 16); le16(b, 20, 1); le16(b, 22, 1); le32(b, 24, rate); le32(b, 28, rate * 2);
        le16(b, 32, 2); le16(b, 34, 16); put(b, 36, "data"); le32(b, 40, dl);
        for (int i = 0; i < pcm.length; i++) le16(b, 44 + i * 2, pcm[i]);
        return b;
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    private static void le32(byte[] b, int o, int v) { le16(b, o, v); le16(b, o + 2, v >> 16); }
}
