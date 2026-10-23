package io.rfsj.weealert;

/** Turns one short source sound into the notes of a pattern. Pure Java, no Android classes. */
final class Synth {
    static final float NOTE_SEC = 0.35f;   // longest piece of the source that is used per note
    static final int BELL_RATE = 44100;

    /** Built-in source: a soft bell, used when no source file is chosen. */
    static float[] bell() {
        int n = (int) (BELL_RATE * NOTE_SEC);
        float[] o = new float[n];
        double[] ratio = {1, 2.76, 5.40}, amp = {1, 0.5, 0.25}, tau = {0.12, 0.07, 0.04};
        for (int i = 0; i < n; i++) {
            double t = i / (double) BELL_RATE, s = 0;
            for (int k = 0; k < 3; k++) s += amp[k] * Math.sin(2 * Math.PI * 660 * ratio[k] * t) * Math.exp(-t / tau[k]);
            o[i] = (float) s;
        }
        return prepare(o, BELL_RATE);
    }

    /** Trim leading silence, cap the length, fade the edges and normalise. */
    static float[] prepare(float[] in, int rate) {
        float peak = 0;
        for (float v : in) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0) return new float[0];
        int start = 0;
        while (start < in.length && Math.abs(in[start]) < 0.02f * peak) start++;
        start = Math.max(0, start - rate / 500);
        int len = Math.min(in.length - start, (int) (rate * NOTE_SEC));
        float[] o = new float[len];
        System.arraycopy(in, start, o, 0, len);
        int fi = Math.min(len / 4, rate / 500), fo = Math.min(len / 4, rate * 30 / 1000);
        for (int i = 0; i < fi; i++) o[i] *= i / (float) fi;
        for (int i = 0; i < fo; i++) o[len - 1 - i] *= i / (float) fo;
        normalize(o, 0.9f);
        return o;
    }

    private static void normalize(float[] a, float target) {
        float p = 0;
        for (float v : a) p = Math.max(p, Math.abs(v));
        if (p > 0) for (int i = 0; i < a.length; i++) a[i] *= target / p;
    }

    static short[] render(float[] src, int rate, Codebook.Pattern p) {
        int k = p.semis.length, total = 0;
        float[][] notes = new float[k][];
        int[] at = new int[k];
        for (int i = 0; i < k; i++) {
            double r = Math.pow(2, p.semis[i] / 12.0);
            int len = Math.max(1, (int) (src.length / r));
            float[] n = new float[len];
            for (int j = 0; j < len; j++) {
                double pos = j * r;
                int i0 = (int) pos;
                int i1 = Math.min(i0 + 1, src.length - 1);
                float f = (float) (pos - i0);
                n[j] = src[Math.min(i0, src.length - 1)] * (1 - f) + src[i1] * f;
            }
            notes[i] = n;
            at[i] = (int) ((long) p.onsetUnits(i) * Codebook.UNIT_MS * rate / 1000);
            total = Math.max(total, at[i] + len);
        }
        float[] mix = new float[total];
        for (int i = 0; i < k; i++)
            for (int j = 0; j < notes[i].length; j++) mix[at[i] + j] += notes[i][j];
        normalize(mix, 0.89f);
        short[] o = new short[total];
        for (int i = 0; i < total; i++) o[i] = (short) Math.round(mix[i] * 32767f);
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
